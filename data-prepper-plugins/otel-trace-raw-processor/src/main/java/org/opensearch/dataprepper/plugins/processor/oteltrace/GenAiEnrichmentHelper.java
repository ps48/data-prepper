/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 */

package org.opensearch.dataprepper.plugins.processor.oteltrace;

import org.opensearch.dataprepper.model.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Enriches GenAI agent traces by propagating attributes from child spans to root
 * and stripping conflicting flattened sub-keys.
 *
 * @see <a href="https://opentelemetry.io/docs/specs/semconv/gen-ai/">OTel GenAI Semantic Conventions v1.39.0</a>
 */
final class GenAiEnrichmentHelper {
    private static final Logger LOG = LoggerFactory.getLogger(GenAiEnrichmentHelper.class);

    private static final String GEN_AI_SYSTEM_KEY = "gen_ai.system";
    private static final String GEN_AI_PROVIDER_NAME_KEY = "gen_ai.provider.name";
    private static final String GEN_AI_AGENT_NAME_KEY = "gen_ai.agent.name";
    private static final String GEN_AI_REQUEST_MODEL_KEY = "gen_ai.request.model";
    private static final String GEN_AI_INPUT_TOKENS_KEY = "gen_ai.usage.input_tokens";
    private static final String GEN_AI_OUTPUT_TOKENS_KEY = "gen_ai.usage.output_tokens";
    private static final String GEN_AI_OPERATION_NAME_KEY = "gen_ai.operation.name";
    /**
     * Marks every agent or workflow invocation span: a span whose {@code gen_ai.operation.name} is
     * {@code invoke_agent} or {@code invoke_workflow}. The decision depends on the span alone, so it
     * is the same whatever batch the span arrives in. Consumers take the earliest marked span of a
     * trace as its agent root. A source sets it to {@code true} or {@code false} to decide itself;
     * the processor never changes a value the source set.
     */
    static final String DERIVED_GEN_AI_IS_ROOT_KEY = "derived.gen_ai.is_root";
    /** The {@code gen_ai.operation.name} values that mark a span as an agent root. */
    static final Set<String> AGENT_ROOT_OPERATIONS = Set.of("invoke_agent", "invoke_workflow");
    private static final String ATTRIBUTES_PREFIX = "attributes/";

    private static final String[] PROPAGATED_STRING_KEYS = {
            GEN_AI_SYSTEM_KEY, GEN_AI_PROVIDER_NAME_KEY, GEN_AI_AGENT_NAME_KEY, GEN_AI_REQUEST_MODEL_KEY
    };

    private static final String[] FLATTENED_PARENT_KEYS = {
            "llm.input_messages", "llm.output_messages",
            "gen_ai.prompt", "gen_ai.completion"
    };

    private GenAiEnrichmentHelper() {}

    /**
     * Enriches a batch of spans: normalizes vendor attributes, strips flattened sub-keys,
     * propagates select gen_ai attributes from children to the root spans that are AI roots,
     * then marks every agent and workflow invocation span.
     */
    static void enrichBatch(final List<Span> spans) {
        enrichBatch(spans, GenAiRootPropagation.AI_ROOT);
    }

    /**
     * Same as {@link #enrichBatch(List)}, propagating attributes to the roots {@code propagation}
     * selects. Propagation and token totals use only the spans of this batch; marking uses only
     * each span's own attributes. Nothing is kept between batches.
     */
    static void enrichBatch(final List<Span> spans, final GenAiRootPropagation propagation) {
        for (final Span span : spans) {
            normalizeAttributes(span);
            stripFlattenedSubkeys(span);
        }

        final Map<String, List<Span>> spansByTrace = new LinkedHashMap<>();
        for (final Span span : spans) {
            spansByTrace.computeIfAbsent(span.getTraceId(), k -> new ArrayList<>()).add(span);
        }

        for (final Map.Entry<String, List<Span>> trace : spansByTrace.entrySet()) {
            final TraceIndex index = new TraceIndex(trace.getValue());
            if (!index.hasGenAiData()) {
                continue;
            }
            enrichTrace(index, propagation);
        }
    }

    /** Enriches the spans of one trace that are in the batch. */
    private static void enrichTrace(final TraceIndex index, final GenAiRootPropagation propagation) {
        // Root first: it sums the spans' own tokens, before marking adds aggregates to agent roots.
        final SpanInfo root = index.root();
        if (root != null && index.size() > 1 && propagatesTo(root, propagation)) {
            enrichRootSpan(index, root);
        }
        markAgentRoots(index);
    }

    /**
     * Whether the processor marks {@code info} as an agent root: its operation is
     * {@code invoke_agent} or {@code invoke_workflow} and the source did not set the marker, to any value.
     */
    private static boolean isAgentRoot(final SpanInfo info) {
        return !info.hasMarker && info.operationName != null && AGENT_ROOT_OPERATIONS.contains(info.operationName);
    }

    /**
     * Whether the root gets its children's GenAI attributes and token totals. With
     * {@code ai_root}, only a root that is its trace's AI root: a GenAI span, or a span marked
     * {@code true} at the source. A non-GenAI root, such as the HTTP span an agent runs behind,
     * would otherwise carry a model, agent name and tokens it did not produce, and every token
     * would be counted again by consumers that sum usage across spans.
     */
    private static boolean propagatesTo(final SpanInfo root, final GenAiRootPropagation propagation) {
        switch (propagation) {
            case ALL_ROOTS:
                return true;
            case NONE:
                return false;
            case AI_ROOT:
            default:
                return root.genAi || Boolean.TRUE.equals(root.marker);
        }
    }

    /** One span with the attributes this helper reads, deserialized once. */
    private static final class SpanInfo {
        private final Span span;
        private final Map<String, Object> attributes;
        private final boolean genAi;
        private final String operationName;
        private final Boolean marker;
        private final boolean hasMarker;
        private final Long inputTokens;
        private final Long outputTokens;
        private final Instant startTime;
        private final boolean marked;

        private SpanInfo(final Span span) {
            this.span = span;
            final Map<String, Object> attrs = span.getAttributes();
            this.attributes = attrs == null ? Map.of() : attrs;
            final Object operation = attributes.get(GEN_AI_OPERATION_NAME_KEY);
            this.genAi = operation != null;
            this.operationName = operation == null ? null : operation.toString().trim();
            this.marker = toBoolean(attributes.get(DERIVED_GEN_AI_IS_ROOT_KEY));
            this.hasMarker = attributes.containsKey(DERIVED_GEN_AI_IS_ROOT_KEY);
            this.marked = Boolean.TRUE.equals(marker);
            this.inputTokens = toLong(attributes.get(GEN_AI_INPUT_TOKENS_KEY));
            this.outputTokens = toLong(attributes.get(GEN_AI_OUTPUT_TOKENS_KEY));
            this.startTime = toInstant(span.getStartTime());
        }

        private String spanId() {
            return span.getSpanId();
        }

        private String parentSpanId() {
            return span.getParentSpanId();
        }

        private boolean hasTokens() {
            return inputTokens != null || outputTokens != null;
        }

        private boolean isRoot() {
            final String parentSpanId = parentSpanId();
            return parentSpanId == null || parentSpanId.isEmpty() || "0000000000000000".equals(parentSpanId);
        }
    }

    private static final Comparator<SpanInfo> BY_START_TIME = Comparator.comparing(
            (SpanInfo info) -> info.startTime, Comparator.nullsLast(Comparator.naturalOrder()));

    /** The spans of one trace in a batch, indexed once. */
    private static final class TraceIndex {
        private final List<SpanInfo> spans = new ArrayList<>();
        private final Map<String, SpanInfo> bySpanId = new HashMap<>();
        private final Map<String, List<SpanInfo>> childrenByParent = new HashMap<>();

        private TraceIndex(final Collection<Span> traceSpans) {
            for (final Span span : traceSpans) {
                final SpanInfo info = new SpanInfo(span);
                spans.add(info);
                if (info.spanId() != null) {
                    bySpanId.put(info.spanId(), info);
                }
                if (info.parentSpanId() != null) {
                    childrenByParent.computeIfAbsent(info.parentSpanId(), k -> new ArrayList<>()).add(info);
                }
            }
        }

        private int size() {
            return spans.size();
        }

        private boolean hasGenAiData() {
            for (final SpanInfo info : spans) {
                if (info.genAi || info.marker != null || info.hasTokens()) {
                    return true;
                }
                for (final String key : PROPAGATED_STRING_KEYS) {
                    if (info.attributes.containsKey(key)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private SpanInfo root() {
            for (final SpanInfo info : spans) {
                if (info.isRoot()) {
                    return info;
                }
            }
            return null;
        }

        /** Whether an ancestor of {@code info} in the batch matches. */
        private boolean hasAncestor(final SpanInfo info, final Predicate<SpanInfo> matches) {
            final Set<String> seen = new HashSet<>();
            SpanInfo parent = bySpanId.get(info.parentSpanId());
            while (parent != null && seen.add(parent.spanId())) {
                if (matches.test(parent)) {
                    return true;
                }
                parent = bySpanId.get(parent.parentSpanId());
            }
            return false;
        }

        private List<SpanInfo> descendantsOf(final SpanInfo ancestor) {
            final List<SpanInfo> descendants = new ArrayList<>();
            final List<SpanInfo> pending = new ArrayList<>(childrenByParent.getOrDefault(ancestor.spanId(), List.of()));
            final Set<String> seen = new HashSet<>();
            seen.add(ancestor.spanId());
            while (!pending.isEmpty()) {
                final SpanInfo descendant = pending.remove(pending.size() - 1);
                if (!seen.add(descendant.spanId())) {
                    continue;
                }
                descendants.add(descendant);
                pending.addAll(childrenByParent.getOrDefault(descendant.spanId(), List.of()));
            }
            return descendants;
        }

        /**
         * Sums token usage over {@code subset}, counting only spans with no ancestor in the subset
         * that also reports usage. Agent spans such as invoke_agent often report the total of their
         * own LLM calls, so adding every span with usage would count those calls twice.
         */
        private TokenTotals sumTopmostTokens(final Collection<SpanInfo> subset) {
            final Set<SpanInfo> members = new HashSet<>(subset);
            final TokenTotals totals = new TokenTotals();
            for (final SpanInfo info : subset) {
                if (!info.hasTokens()
                        || hasAncestor(info, parent -> members.contains(parent) && parent.hasTokens())) {
                    continue;
                }
                totals.found = true;
                totals.input += info.inputTokens != null ? info.inputTokens : 0;
                totals.output += info.outputTokens != null ? info.outputTokens : 0;
            }
            return totals;
        }
    }

    private static final class TokenTotals {
        private long input;
        private long output;
        private boolean found;
    }

    /**
     * Marks every {@code invoke_agent} and {@code invoke_workflow} span of the batch with
     * {@code derived.gen_ai.is_root: true}. A span whose marker was set at the source, to
     * {@code true} or {@code false}, is left as it is. A marked span that reports no token usage
     * of its own gets the usage of its descendants in the same batch (best effort: descendants in
     * other batches are not counted). Totals are read from the spans' own usage, so they do not
     * depend on the order the agents are marked in.
     */
    private static void markAgentRoots(final TraceIndex index) {
        for (final SpanInfo agentRoot : index.spans) {
            if (!isAgentRoot(agentRoot)) {
                continue;
            }
            agentRoot.span.put(ATTRIBUTES_PREFIX + DERIVED_GEN_AI_IS_ROOT_KEY, true);
            LOG.debug("Marked span {} of trace {} as a GenAI agent root", agentRoot.spanId(), agentRoot.span.getTraceId());
            if (agentRoot.hasTokens()) {
                continue;
            }
            final TokenTotals tokens = index.sumTopmostTokens(index.descendantsOf(agentRoot));
            if (tokens.found) {
                agentRoot.span.put(ATTRIBUTES_PREFIX + GEN_AI_INPUT_TOKENS_KEY, tokens.input);
                agentRoot.span.put(ATTRIBUTES_PREFIX + GEN_AI_OUTPUT_TOKENS_KEY, tokens.output);
            }
        }
    }

    /**
     * Propagates gen_ai.* string attributes and aggregated token counts from children to root.
     * Skips the attributes the root already has. Values come from the outermost GenAI spans
     * first, so the trace gets the outer agent's name and model whatever order the batch is in.
     * Only children in the same batch are read: this is best effort when spans of a trace arrive
     * in different batches, and a root is not updated later.
     */
    private static void enrichRootSpan(final TraceIndex index, final SpanInfo root) {
        final Map<String, String> toPropagate = new LinkedHashMap<>();
        for (final String key : PROPAGATED_STRING_KEYS) {
            if (!root.attributes.containsKey(key)) {
                toPropagate.put(key, null);
            }
        }
        if (toPropagate.isEmpty() && root.hasTokens()) {
            return;
        }

        final List<SpanInfo> children = new ArrayList<>();
        for (final SpanInfo info : index.spans) {
            if (info != root) {
                children.add(info);
            }
        }
        if (!toPropagate.isEmpty()) {
            // Outermost among the children: GenAI spans with no GenAI ancestor below the root.
            final Set<SpanInfo> outer = new HashSet<>();
            for (final SpanInfo child : children) {
                if (child.genAi && !index.hasAncestor(child, parent -> parent != root && (parent.genAi || parent.marked))) {
                    outer.add(child);
                }
            }
            final List<SpanInfo> ordered = new ArrayList<>(children);
            ordered.sort(Comparator.<SpanInfo>comparingInt(info -> outer.contains(info) ? 0 : info.genAi ? 1 : 2)
                    .thenComparing(BY_START_TIME));
            for (final SpanInfo child : ordered) {
                for (final Map.Entry<String, String> entry : toPropagate.entrySet()) {
                    final Object value = child.attributes.get(entry.getKey());
                    if (entry.getValue() == null && value != null) {
                        entry.setValue(value.toString());
                    }
                }
            }
            for (final Map.Entry<String, String> entry : toPropagate.entrySet()) {
                if (entry.getValue() != null) {
                    root.span.put(ATTRIBUTES_PREFIX + entry.getKey(), entry.getValue());
                    LOG.debug("Propagated {} = {} to root span {}", entry.getKey(), entry.getValue(), root.spanId());
                }
            }
        }

        if (!root.hasTokens()) {
            final TokenTotals tokens = index.sumTopmostTokens(children);
            if (tokens.found) {
                root.span.put(ATTRIBUTES_PREFIX + GEN_AI_INPUT_TOKENS_KEY, tokens.input);
                root.span.put(ATTRIBUTES_PREFIX + GEN_AI_OUTPUT_TOKENS_KEY, tokens.output);
                LOG.debug("Aggregated tokens (input={}, output={}) to root span {}", tokens.input, tokens.output, root.spanId());
            }
        }
    }

    /** A token count from a number or a numeric string; anything else is treated as absent. */
    private static Long toLong(final Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return (long) Double.parseDouble(((String) value).trim());
            } catch (final NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Boolean toBoolean(final Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            final String text = ((String) value).trim();
            if ("true".equalsIgnoreCase(text)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(text)) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    /** Start times as instants: ISO-8601 strings with 0 to 9 fractional digits do not sort as text. */
    private static Instant toInstant(final String time) {
        if (time == null) {
            return null;
        }
        try {
            return Instant.parse(time);
        } catch (final DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Normalizes vendor-specific attributes to OTel GenAI Semantic Conventions.
     * Copies values to the standard gen_ai.* key, keeping originals intact.
     * Skips if the target attribute already exists on the span. When several source
     * attributes map to the same target, the one listed first in the mappings file wins.
     */
    static void normalizeAttributes(final Span span) {
        final Map<String, Object> attrs = span.getAttributes();
        if (attrs == null) {
            return;
        }

        // Pick one source per target first: span.put does not update attrs, so checking attrs
        // while writing would let every later source overwrite the earlier one.
        final Map<String, Map.Entry<String, Object>> sources = new HashMap<>();
        for (final Map.Entry<String, Object> entry : attrs.entrySet()) {
            final GenAiAttributeMappings.MappingTarget target =
                    GenAiAttributeMappings.getLookupTable().get(entry.getKey());
            if (target == null || attrs.containsKey(target.getKey())) {
                continue;
            }
            final Map.Entry<String, Object> current = sources.get(target.getKey());
            if (current == null || target.getPriority()
                    < GenAiAttributeMappings.getLookupTable().get(current.getKey()).getPriority()) {
                sources.put(target.getKey(), entry);
            }
        }

        for (final Map.Entry<String, Object> entry : sources.values()) {
            final GenAiAttributeMappings.MappingTarget target =
                    GenAiAttributeMappings.getLookupTable().get(entry.getKey());

            Object value = entry.getValue();
            if (value instanceof String) {
                final String strVal = (String) value;
                if ("gen_ai.operation.name".equals(target.getKey())) {
                    final String mapped = GenAiAttributeMappings.getOperationNameValues()
                            .get(strVal.toLowerCase());
                    if (mapped != null) {
                        value = mapped;
                    }
                }
                if (target.isWrapAsArray()) {
                    value = "[\"" + value + "\"]";
                }
            }

            span.put(ATTRIBUTES_PREFIX + target.getKey(), value);
        }
    }

    /**
     * Removes flattened sub-keys (e.g. "llm.input_messages.0.message.content") that
     * conflict with parent string values (e.g. "llm.input_messages"), preventing OpenSearch mapping failures.
     * Only strips when the parent string value exists. If only sub-keys exist, they are preserved.
     */
    static void stripFlattenedSubkeys(final Span span) {
        final Map<String, Object> attrs = span.getAttributes();
        if (attrs == null) {
            return;
        }

        final List<String> toRemove = new ArrayList<>();
        for (final String parentKey : FLATTENED_PARENT_KEYS) {
            if (!attrs.containsKey(parentKey)) {
                continue;
            }
            for (final String key : attrs.keySet()) {
                if (key.startsWith(parentKey + ".") && key.length() > parentKey.length() + 1
                        && Character.isDigit(key.charAt(parentKey.length() + 1))) {
                    toRemove.add(key);
                }
            }
        }

        for (final String key : toRemove) {
            try {
                span.delete(ATTRIBUTES_PREFIX + key);
            } catch (final Exception e) {
                LOG.warn("Failed to delete flattened sub-key {}: {}", key, e.getMessage());
            }
        }
    }
}
