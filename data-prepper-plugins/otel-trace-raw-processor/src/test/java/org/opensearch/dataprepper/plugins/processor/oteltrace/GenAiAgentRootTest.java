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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.opensearch.dataprepper.metrics.PluginMetrics;
import org.opensearch.dataprepper.model.configuration.PipelineDescription;
import org.opensearch.dataprepper.model.record.Record;
import org.opensearch.dataprepper.model.trace.DefaultTraceGroupFields;
import org.opensearch.dataprepper.model.trace.JacksonSpan;
import org.opensearch.dataprepper.model.trace.Span;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.opensearch.dataprepper.plugins.processor.oteltrace.GenAiEnrichmentHelper.DERIVED_GEN_AI_IS_ROOT_KEY;

/**
 * Every {@code invoke_agent} and {@code invoke_workflow} span is marked as an agent root, from the
 * span alone: the result does not depend on how a trace's spans are split into batches or on the
 * order the batches arrive in. Consumers take the earliest marked span of a trace as its agent root.
 */
class GenAiAgentRootTest {

    private static final String TRACE_ID = "AGENT_ROOT_TRACE";

    @Test
    void marksTheAgentSpanUnderAnHttpRoot() {
        final List<Span> spans = httpAgentTrace();
        Collections.shuffle(spans);

        GenAiEnrichmentHelper.enrichBatch(spans);

        assertEquals(List.of("agent"), markedSpanIds(spans));
        final Span http = byId(spans, "http");
        assertNull(http.getAttributes().get("gen_ai.operation.name"));
        assertNull(http.getAttributes().get(DERIVED_GEN_AI_IS_ROOT_KEY));
    }

    @Test
    void aggregatesDescendantTokensOntoTheAgentRootWithoutDoubleCountingTheTraceRoot() {
        final List<Span> spans = httpAgentTrace();

        GenAiEnrichmentHelper.enrichBatch(spans, GenAiRootPropagation.ALL_ROOTS);

        final Map<String, Object> agent = byId(spans, "agent").getAttributes();
        assertEquals(15L, ((Number) agent.get("gen_ai.usage.input_tokens")).longValue());
        assertEquals(7L, ((Number) agent.get("gen_ai.usage.output_tokens")).longValue());
        final Map<String, Object> http = byId(spans, "http").getAttributes();
        assertEquals(15L, ((Number) http.get("gen_ai.usage.input_tokens")).longValue());
        assertEquals(7L, ((Number) http.get("gen_ai.usage.output_tokens")).longValue());
    }

    @Test
    void propagatesTheOuterAgentNameToTheTraceRootWhateverTheBatchOrder() {
        final List<Span> spans = httpAgentTrace();
        spans.add(span("inner", "chat1", "2026-01-01T00:00:03Z", Map.of(
                "gen_ai.operation.name", "invoke_agent", "gen_ai.agent.name", "Weather Agent")));
        // Inner agent first in the batch: first-child-wins would pick it.
        Collections.reverse(spans);

        GenAiEnrichmentHelper.enrichBatch(spans, GenAiRootPropagation.ALL_ROOTS);

        assertEquals("Travel Planner", byId(spans, "http").getAttributes().get("gen_ai.agent.name"));
    }

    @Test
    void countsAnAgentsReportedTotalOnceWhenItsCallsAlsoReportUsage() {
        // Strands-style: invoke_agent reports the total of its own chat calls.
        final List<Span> spans = new ArrayList<>();
        spans.add(span("http", "", "2026-01-01T00:00:00Z", Map.of("http.method", "POST")));
        spans.add(span("agent", "http", "2026-01-01T00:00:01Z", Map.of(
                "gen_ai.operation.name", "invoke_agent",
                "gen_ai.usage.input_tokens", 15L, "gen_ai.usage.output_tokens", 7L)));
        spans.add(span("chat1", "agent", "2026-01-01T00:00:02Z", Map.of(
                "gen_ai.operation.name", "chat",
                "gen_ai.usage.input_tokens", 10L, "gen_ai.usage.output_tokens", 4L)));
        spans.add(span("chat2", "agent", "2026-01-01T00:00:03Z", Map.of(
                "gen_ai.operation.name", "chat",
                "gen_ai.usage.input_tokens", 5L, "gen_ai.usage.output_tokens", 3L)));

        GenAiEnrichmentHelper.enrichBatch(spans, GenAiRootPropagation.ALL_ROOTS);

        final Map<String, Object> root = byId(spans, "http").getAttributes();
        assertEquals(15L, ((Number) root.get("gen_ai.usage.input_tokens")).longValue());
        assertEquals(7L, ((Number) root.get("gen_ai.usage.output_tokens")).longValue());
        final Map<String, Object> agent = byId(spans, "agent").getAttributes();
        assertEquals(15L, ((Number) agent.get("gen_ai.usage.input_tokens")).longValue());
    }

    @Test
    void countsANestedAgentsReportedTotalOnceOnTheMarkedSpan() {
        // The outer agent reports no usage; a sub-agent reports the total of its own chat call.
        final List<Span> spans = new ArrayList<>();
        spans.add(span("http", "", "2026-01-01T00:00:00Z", Map.of("http.method", "POST")));
        spans.add(span("agent", "http", "2026-01-01T00:00:01Z", Map.of("gen_ai.operation.name", "invoke_agent")));
        spans.add(span("chat1", "agent", "2026-01-01T00:00:02Z", Map.of(
                "gen_ai.operation.name", "chat",
                "gen_ai.usage.input_tokens", 10L, "gen_ai.usage.output_tokens", 4L)));
        spans.add(span("tool", "agent", "2026-01-01T00:00:03Z", Map.of("gen_ai.operation.name", "execute_tool")));
        spans.add(span("sub", "tool", "2026-01-01T00:00:04Z", Map.of(
                "gen_ai.operation.name", "invoke_agent",
                "gen_ai.usage.input_tokens", 5L, "gen_ai.usage.output_tokens", 3L)));
        spans.add(span("subchat", "sub", "2026-01-01T00:00:05Z", Map.of(
                "gen_ai.operation.name", "chat",
                "gen_ai.usage.input_tokens", 5L, "gen_ai.usage.output_tokens", 3L)));

        GenAiEnrichmentHelper.enrichBatch(spans, GenAiRootPropagation.ALL_ROOTS);

        // The sub-agent is marked too; it keeps the usage it reports.
        assertEquals(List.of("agent", "sub"), markedSpanIds(spans));
        assertEquals(5L, ((Number) byId(spans, "sub").getAttributes().get("gen_ai.usage.input_tokens")).longValue());
        for (final String id : List.of("agent", "http")) {
            final Map<String, Object> attrs = byId(spans, id).getAttributes();
            assertEquals(15L, ((Number) attrs.get("gen_ai.usage.input_tokens")).longValue(), id);
            assertEquals(7L, ((Number) attrs.get("gen_ai.usage.output_tokens")).longValue(), id);
        }
    }

    @Test
    void marksAGenAiRootItself() {
        final List<Span> spans = new ArrayList<>();
        spans.add(span("agent", "", "2026-01-01T00:00:00Z", Map.of("gen_ai.operation.name", "invoke_agent")));
        spans.add(span("chat1", "agent", "2026-01-01T00:00:01Z", Map.of(
                "gen_ai.operation.name", "chat",
                "gen_ai.usage.input_tokens", 10L, "gen_ai.usage.output_tokens", 4L)));

        GenAiEnrichmentHelper.enrichBatch(spans);

        assertEquals(List.of("agent"), markedSpanIds(spans));
        assertEquals(10L, ((Number) byId(spans, "agent").getAttributes().get("gen_ai.usage.input_tokens")).longValue());
    }

    @Test
    void marksEachParallelTopLevelAgent() {
        final List<Span> spans = new ArrayList<>();
        spans.add(span("http", "", "2026-01-01T00:00:00Z", Map.of("http.method", "POST")));
        spans.add(span("agentA", "http", "2026-01-01T00:00:01Z", Map.of("gen_ai.operation.name", "invoke_agent")));
        spans.add(span("agentB", "http", "2026-01-01T00:00:02Z", Map.of("gen_ai.operation.name", "invoke_agent")));
        spans.add(span("chatA", "agentA", "2026-01-01T00:00:03Z", Map.of("gen_ai.operation.name", "chat")));

        GenAiEnrichmentHelper.enrichBatch(spans);

        assertEquals(List.of("agentA", "agentB"), markedSpanIds(spans));
    }

    @Test
    void marksTheAgentSpanInAnOrphanBatchWithoutTheRoot() {
        // Children flushed after trace_flush_interval, before the root arrived.
        final List<Span> spans = httpAgentTrace().stream()
                .filter(s -> !"http".equals(s.getSpanId()))
                .collect(Collectors.toCollection(ArrayList::new));

        GenAiEnrichmentHelper.enrichBatch(spans);

        assertEquals(List.of("agent"), markedSpanIds(spans));
    }

    @Test
    void keepsAMarkerSetAtTheSourceAndStillMarksAgentSpans() {
        final List<Span> spans = httpAgentTrace();
        // The SDK marked the HTTP handler span as the agent root.
        spans.set(0, span("http", "", "2026-01-01T00:00:00Z", Map.of(DERIVED_GEN_AI_IS_ROOT_KEY, true)));

        GenAiEnrichmentHelper.enrichBatch(spans);

        // Marking is decided per span: the source marker does not stop the agent span being marked.
        assertEquals(List.of("agent", "http"), markedSpanIds(spans));
    }

    @Test
    void marksEveryInvokeAgentAndInvokeWorkflowSpan() {
        // invoke_workflow > invoke_agent > invoke_agent > chat, plus a chat directly under the workflow.
        final List<Span> spans = nestedWorkflowTrace();

        GenAiEnrichmentHelper.enrichBatch(spans);

        assertEquals(List.of("inner", "outer", "workflow"), markedSpanIds(spans));
        assertNull(byId(spans, "chat").getAttributes().get(DERIVED_GEN_AI_IS_ROOT_KEY));
        assertNull(byId(spans, "plan").getAttributes().get(DERIVED_GEN_AI_IS_ROOT_KEY));
    }

    @ParameterizedTest
    @ValueSource(strings = {"chat", "execute_tool", "create_agent", "embeddings", "text_completion",
            "generate_content", "Invoke_Agent", "invoke_agent_v2"})
    void neverMarksOtherOperations(final String operation) {
        final List<Span> spans = new ArrayList<>();
        // A root and a child with the operation: neither position makes it an agent root.
        spans.add(span("root", "", "2026-01-01T00:00:00Z", Map.of("gen_ai.operation.name", operation)));
        spans.add(span("child", "root", "2026-01-01T00:00:01Z", Map.of("gen_ai.operation.name", operation)));

        GenAiEnrichmentHelper.enrichBatch(spans);

        assertEquals(List.of(), markedSpanIds(spans));
        for (final Span span : spans) {
            assertFalse(span.getAttributes().containsKey(DERIVED_GEN_AI_IS_ROOT_KEY), span.getSpanId());
        }
    }

    @Test
    void marksAVendorAgentSpanByItsNormalizedOperation() {
        final List<Span> spans = new ArrayList<>();
        spans.add(span("chain", "", "2026-01-01T00:00:00Z", Map.of("openinference.span.kind", "CHAIN")));
        spans.add(span("llm", "chain", "2026-01-01T00:00:01Z", Map.of("openinference.span.kind", "LLM")));

        GenAiEnrichmentHelper.enrichBatch(spans);

        assertEquals("invoke_agent", byId(spans, "chain").getAttributes().get("gen_ai.operation.name"));
        assertEquals(List.of("chain"), markedSpanIds(spans));
    }

    @ParameterizedTest
    @MethodSource("sourceMarkers")
    void neverChangesAMarkerSetAtTheSource(final Object marker) {
        final List<Span> spans = new ArrayList<>();
        spans.add(span("http", "", "2026-01-01T00:00:00Z", Map.of("http.method", "POST")));
        spans.add(span("agent", "http", "2026-01-01T00:00:01Z", Map.of(
                "gen_ai.operation.name", "invoke_agent", DERIVED_GEN_AI_IS_ROOT_KEY, marker)));
        spans.add(span("chat", "agent", "2026-01-01T00:00:02Z", Map.of(
                "gen_ai.operation.name", "chat", DERIVED_GEN_AI_IS_ROOT_KEY, marker,
                "gen_ai.usage.input_tokens", 10L)));

        GenAiEnrichmentHelper.enrichBatch(spans);

        assertEquals(marker, byId(spans, "agent").getAttributes().get(DERIVED_GEN_AI_IS_ROOT_KEY));
        assertEquals(marker, byId(spans, "chat").getAttributes().get(DERIVED_GEN_AI_IS_ROOT_KEY));
        // A span the source marked is left as it is: it gets no token totals either.
        assertNull(byId(spans, "agent").getAttributes().get("gen_ai.usage.input_tokens"));
        assertNull(byId(spans, "http").getAttributes().get(DERIVED_GEN_AI_IS_ROOT_KEY));
    }

    static Stream<Object> sourceMarkers() {
        return Stream.of(true, false, "true", "false", "yes");
    }

    @ParameterizedTest
    @MethodSource("traceShapes")
    void marksTheSameSpansWhateverTheBatchSplitAndOrder(final String name, final Supplier<List<Span>> trace,
                                                        final List<String> expected) {
        final int size = trace.get().size();
        final List<List<List<Integer>>> splits = new ArrayList<>();
        // One batch, every span alone in arrival order, every span alone in reverse order (child first).
        splits.add(List.of(range(0, size)));
        splits.add(range(0, size).stream().map(List::of).collect(Collectors.toList()));
        final List<List<Integer>> reversed = range(0, size).stream().map(List::of).collect(Collectors.toList());
        Collections.reverse(reversed);
        splits.add(reversed);
        // Root alone first (root first), root alone last (child first), and random splits.
        splits.add(List.of(List.of(0), range(1, size)));
        splits.add(List.of(range(1, size), List.of(0)));
        final Random random = new Random(42);
        for (int i = 0; i < 20; i++) {
            final List<Integer> order = range(0, size);
            Collections.shuffle(order, random);
            final List<List<Integer>> batches = new ArrayList<>();
            int from = 0;
            while (from < size) {
                final int to = Math.min(size, from + 1 + random.nextInt(size));
                batches.add(new ArrayList<>(order.subList(from, to)));
                from = to;
            }
            splits.add(batches);
        }

        for (final List<List<Integer>> batches : splits) {
            final List<Span> spans = trace.get();
            for (final List<Integer> batch : batches) {
                GenAiEnrichmentHelper.enrichBatch(batch.stream().map(spans::get).collect(Collectors.toList()));
            }
            assertEquals(expected, markedSpanIds(spans), name + " " + batches);
        }
    }

    static Stream<Arguments> traceShapes() {
        final Supplier<List<Span>> agentRoot = () -> new ArrayList<>(List.of(
                span("agent", "", "2026-01-01T00:00:00Z", Map.of("gen_ai.operation.name", "invoke_agent")),
                span("chat", "agent", "2026-01-01T00:00:01Z", Map.of(
                        "gen_ai.operation.name", "chat", "gen_ai.usage.input_tokens", 150L))));
        return Stream.of(
                Arguments.of("agent root", agentRoot, List.of("agent")),
                Arguments.of("agent behind an HTTP root", (Supplier<List<Span>>) GenAiAgentRootTest::httpAgentTrace,
                        List.of("agent")),
                Arguments.of("nested agents", (Supplier<List<Span>>) GenAiAgentRootTest::nestedWorkflowTrace,
                        List.of("inner", "outer", "workflow")),
                Arguments.of("agent calling an agent over HTTP", (Supplier<List<Span>>) GenAiAgentRootTest::crossServiceTrace,
                        List.of("planner", "weather")));
    }

    @Test
    void marksTheSameSpansThroughTheProcessorWhateverTheArrivalOrder() {
        // The doc's cases: A child first, B root first, and one request split across two batches.
        for (final boolean rootFirst : List.of(true, false)) {
            final OTelTraceRawProcessor processor = processor(true, GenAiRootPropagation.AI_ROOT, mock(PluginMetrics.class));
            final List<Span> spans = httpAgentTrace();
            final List<Span> root = List.of(spans.get(0), spans.get(1));
            final List<Span> rest = spans.subList(2, spans.size());

            final List<Span> out = new ArrayList<>();
            out.addAll(execute(processor, rootFirst ? root : rest));
            out.addAll(execute(processor, rootFirst ? rest : root));

            assertEquals(spans.size(), out.size(), "rootFirst=" + rootFirst);
            assertEquals(List.of("agent"), markedSpanIds(out), "rootFirst=" + rootFirst);
        }
    }

    @Test
    void marksEveryAgentWhenWorkersProcessBatchesOfOneTraceConcurrently() throws Exception {
        // Case C: 20 traces, each request split into a root batch and a child batch that four
        // workers process at the same time, in any order.
        final OTelTraceRawProcessor processor = processor(true, GenAiRootPropagation.AI_ROOT, mock(PluginMetrics.class));
        final List<List<Span>> batches = new ArrayList<>();
        final List<Span> all = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            final String traceId = "TRACE_" + i;
            final Span agent = span(traceId, "agent" + i, "", "2026-01-01T00:00:00Z",
                    Map.of("gen_ai.operation.name", "invoke_agent"));
            final Span chat = span(traceId, "chat" + i, "agent" + i, "2026-01-01T00:00:01Z",
                    Map.of("gen_ai.operation.name", "chat", "gen_ai.usage.input_tokens", 150L));
            batches.add(List.of(agent));
            batches.add(List.of(chat));
            all.add(agent);
            all.add(chat);
        }
        Collections.shuffle(batches, new Random(7));

        final ExecutorService workers = Executors.newFixedThreadPool(4);
        try {
            final List<Future<Collection<Record<Span>>>> results = new ArrayList<>();
            for (final List<Span> batch : batches) {
                results.add(workers.submit(() -> processor.doExecute(records(batch))));
            }
            final List<Span> out = new ArrayList<>();
            for (final Future<Collection<Record<Span>>> result : results) {
                result.get().forEach(record -> out.add(record.getData()));
            }
            processor.prepareForShutdown();
            execute(processor, List.of()).forEach(out::add);

            assertEquals(all.size(), out.size());
        } finally {
            workers.shutdownNow();
        }
        final List<String> expected = all.stream().map(Span::getSpanId).filter(id -> id.startsWith("agent"))
                .sorted().collect(Collectors.toList());
        assertEquals(expected, markedSpanIds(all));
    }

    @Test
    void marksNothingWithoutGenAiSpans() {
        final List<Span> spans = new ArrayList<>();
        spans.add(span("http", "", "2026-01-01T00:00:00Z", Map.of("http.method", "GET")));
        spans.add(span("db", "http", "2026-01-01T00:00:01Z", Map.of("db.system", "postgresql")));

        GenAiEnrichmentHelper.enrichBatch(spans);

        assertEquals(List.of(), markedSpanIds(spans));
    }

    @Test
    void marksBothAgentsWhenAnAgentCallsAnotherAgentOverHttp() {
        // POST /runs > invoke_agent Planner > HTTP client > HTTP server (other service)
        //   > invoke_agent Weather > MCP tool
        final List<Span> spans = crossServiceTrace();
        Collections.shuffle(spans);

        GenAiEnrichmentHelper.enrichBatch(spans);

        // Consumers take the earliest marked span, the planner, as the trace's agent root.
        assertEquals(List.of("planner", "weather"), markedSpanIds(spans));
    }

    @Test
    void marksTheRemoteAgentWhenItsCallerIsMissingFromTheBatch() {
        // The remote service's spans were flushed after trace_flush_interval without the caller's.
        final List<Span> spans = crossServiceTrace().stream()
                .filter(s -> List.of("server", "weather", "mcp").contains(s.getSpanId()))
                .collect(Collectors.toCollection(ArrayList::new));

        GenAiEnrichmentHelper.enrichBatch(spans);

        assertEquals(List.of("weather"), markedSpanIds(spans));
    }

    @Test
    void doesNotMarkALateChildThroughTheProcessor() {
        final OTelTraceRawProcessor processor = processor(true, GenAiRootPropagation.AI_ROOT, mock(PluginMetrics.class));

        final List<Span> spans = httpAgentTrace();
        final Span lateChat = spans.remove(spans.size() - 1);
        final Span root = spans.remove(0);
        processor.doExecute(records(spans));
        final List<Span> flushed = processor.doExecute(records(List.of(root))).stream()
                .map(Record::getData).collect(Collectors.toList());
        // Exported after the root: emitted on its own, without its ancestors.
        final List<Span> late = processor.doExecute(records(List.of(lateChat))).stream()
                .map(Record::getData).collect(Collectors.toList());

        assertEquals(List.of("agent"), markedSpanIds(flushed));
        assertEquals(1, late.size());
        assertEquals(List.of(), markedSpanIds(late));
    }

    @Test
    void marksTheAgentSpanThroughTheProcessorWhenTheRootArrivesLast() {
        final OTelTraceRawProcessor processor = processor(true, GenAiRootPropagation.AI_ROOT, mock(PluginMetrics.class));

        final List<Span> spans = httpAgentTrace();
        final Span root = spans.remove(0);
        // Children are exported first and held until their root arrives.
        assertEquals(0, processor.doExecute(records(spans)).size());
        final Collection<Record<Span>> flushed = processor.doExecute(records(List.of(root)));

        final List<Span> out = flushed.stream().map(Record::getData).collect(Collectors.toList());
        assertEquals(List.of("agent"), markedSpanIds(out));
        assertFalse(byId(out, "http").getAttributes().containsKey(DERIVED_GEN_AI_IS_ROOT_KEY));
    }

    private static List<Span> crossServiceTrace() {
        final List<Span> spans = new ArrayList<>();
        spans.add(span("http", "", "2026-01-01T00:00:00Z", Map.of("http.method", "POST")));
        spans.add(span("planner", "http", "2026-01-01T00:00:01Z", Map.of(
                "gen_ai.operation.name", "invoke_agent", "gen_ai.agent.name", "Planner")));
        spans.add(span("client", "planner", "2026-01-01T00:00:02Z", Map.of("http.method", "POST")));
        spans.add(span("server", "client", "2026-01-01T00:00:03Z", Map.of("http.route", "/weather")));
        spans.add(span("weather", "server", "2026-01-01T00:00:04Z", Map.of(
                "gen_ai.operation.name", "invoke_agent", "gen_ai.agent.name", "Weather")));
        spans.add(span("mcp", "weather", "2026-01-01T00:00:05Z", Map.of(
                "gen_ai.operation.name", "execute_tool")));
        return spans;
    }

    /** POST /runs > invoke_agent > chat, execute_tool > chat. */
    @Test
    void ordersAgentsByStartTimeWhateverTheFractionalPrecision() {
        // As text, "...:01Z" sorts after "...:01.500Z"; as instants it is earlier.
        final List<Span> spans = new ArrayList<>();
        spans.add(span("http", "", "2026-01-01T00:00:00Z", Map.of("http.method", "POST")));
        spans.add(span("late", "http", "2026-01-01T00:00:01.500Z", Map.of(
                "gen_ai.operation.name", "invoke_agent", "gen_ai.agent.name", "Late Agent")));
        spans.add(span("early", "http", "2026-01-01T00:00:01Z", Map.of(
                "gen_ai.operation.name", "invoke_agent", "gen_ai.agent.name", "Early Agent")));

        GenAiEnrichmentHelper.enrichBatch(spans, GenAiRootPropagation.ALL_ROOTS);

        assertEquals("Early Agent", byId(spans, "http").getAttributes().get("gen_ai.agent.name"));
        assertEquals(List.of("early", "late"), markedSpanIds(spans));
    }

    @Test
    void keepsTheOutputTokensOfAnAgentThatReportsOnlyOutputTokens() {
        final List<Span> spans = httpAgentTrace();
        spans.set(1, span("agent", "http", "2026-01-01T00:00:01Z", Map.of(
                "gen_ai.operation.name", "invoke_agent", "gen_ai.usage.output_tokens", 700L)));

        GenAiEnrichmentHelper.enrichBatch(spans);

        final Map<String, Object> agent = byId(spans, "agent").getAttributes();
        assertEquals(700L, ((Number) agent.get("gen_ai.usage.output_tokens")).longValue());
        assertNull(agent.get("gen_ai.usage.input_tokens"));
    }

    @Test
    void readsStringTokenCountsAndIgnoresNonNumericOnes() {
        final List<Span> spans = new ArrayList<>();
        spans.add(span("http", "", "2026-01-01T00:00:00Z", Map.of("http.method", "POST")));
        spans.add(span("agent", "http", "2026-01-01T00:00:01Z", Map.of("gen_ai.operation.name", "invoke_agent")));
        spans.add(span("chat1", "agent", "2026-01-01T00:00:02Z", Map.of(
                "gen_ai.operation.name", "chat", "gen_ai.usage.input_tokens", "12", "gen_ai.usage.output_tokens", "n/a")));
        spans.add(span("chat2", "agent", "2026-01-01T00:00:03Z", Map.of(
                "gen_ai.operation.name", "chat", "gen_ai.usage.input_tokens", 3L, "gen_ai.usage.output_tokens", 2L)));

        GenAiEnrichmentHelper.enrichBatch(spans);

        final Map<String, Object> agent = byId(spans, "agent").getAttributes();
        assertEquals(15L, ((Number) agent.get("gen_ai.usage.input_tokens")).longValue());
        assertEquals(2L, ((Number) agent.get("gen_ai.usage.output_tokens")).longValue());
    }

    @Test
    void neverMarksASpanSetToFalseAndStillMarksTheOthers() {
        final List<Span> spans = new ArrayList<>();
        spans.add(span("http", "", "2026-01-01T00:00:00Z", Map.of("http.method", "POST", DERIVED_GEN_AI_IS_ROOT_KEY, false)));
        spans.add(span("agentA", "http", "2026-01-01T00:00:01Z", Map.of(
                "gen_ai.operation.name", "invoke_agent", DERIVED_GEN_AI_IS_ROOT_KEY, false)));
        spans.add(span("agentB", "http", "2026-01-01T00:00:02Z", Map.of("gen_ai.operation.name", "invoke_agent")));

        GenAiEnrichmentHelper.enrichBatch(spans);

        assertEquals(List.of("agentB"), markedSpanIds(spans));
        assertEquals(false, byId(spans, "agentA").getAttributes().get(DERIVED_GEN_AI_IS_ROOT_KEY));
    }

    @Test
    void leavesTracesWithoutGenAiDataUntouched() {
        final List<Span> spans = new ArrayList<>();
        spans.add(span("http", "", "2026-01-01T00:00:00Z", Map.of("http.method", "POST")));
        spans.add(span("db", "http", "2026-01-01T00:00:01Z", Map.of("db.system", "postgresql")));

        GenAiEnrichmentHelper.enrichBatch(spans);

        assertEquals(Map.of("http.method", "POST"), byId(spans, "http").getAttributes());
        assertEquals(Map.of("db.system", "postgresql"), byId(spans, "db").getAttributes());
    }

    @Test
    void doesNotCopyAgentDataOntoANonGenAiRootByDefault() {
        final List<Span> spans = httpAgentTrace();

        GenAiEnrichmentHelper.enrichBatch(spans);

        // The HTTP span keeps its own attributes; the marked agent span carries the agent data.
        assertEquals(Map.of("http.method", "POST"), byId(spans, "http").getAttributes());
        final Map<String, Object> agent = byId(spans, "agent").getAttributes();
        assertEquals(true, agent.get(DERIVED_GEN_AI_IS_ROOT_KEY));
        assertEquals(15L, ((Number) agent.get("gen_ai.usage.input_tokens")).longValue());
    }

    @Test
    void copiesOntoAGenAiRootByDefault() {
        final List<Span> spans = new ArrayList<>();
        spans.add(span("agent", "", "2026-01-01T00:00:00Z", Map.of("gen_ai.operation.name", "invoke_agent")));
        spans.add(span("chat", "agent", "2026-01-01T00:00:01Z", Map.of(
                "gen_ai.operation.name", "chat", "gen_ai.request.model", "claude",
                "gen_ai.usage.input_tokens", 10L, "gen_ai.usage.output_tokens", 4L)));

        GenAiEnrichmentHelper.enrichBatch(spans);

        final Map<String, Object> root = byId(spans, "agent").getAttributes();
        assertEquals("claude", root.get("gen_ai.request.model"));
        assertEquals(10L, ((Number) root.get("gen_ai.usage.input_tokens")).longValue());
    }

    @Test
    void copiesOntoARootMarkedAtTheSourceByDefault() {
        final List<Span> spans = httpAgentTrace();
        spans.set(0, span("http", "", "2026-01-01T00:00:00Z", Map.of("http.method", "POST", DERIVED_GEN_AI_IS_ROOT_KEY, true)));

        GenAiEnrichmentHelper.enrichBatch(spans);

        final Map<String, Object> http = byId(spans, "http").getAttributes();
        assertEquals("Travel Planner", http.get("gen_ai.agent.name"));
        assertEquals(15L, ((Number) http.get("gen_ai.usage.input_tokens")).longValue());
    }

    @Test
    void copiesNoAttributesWithPropagationNone() {
        final List<Span> spans = new ArrayList<>();
        spans.add(span("agent", "", "2026-01-01T00:00:00Z", Map.of("gen_ai.operation.name", "invoke_agent")));
        spans.add(span("chat", "agent", "2026-01-01T00:00:01Z", Map.of(
                "gen_ai.operation.name", "chat", "gen_ai.request.model", "claude", "gen_ai.usage.input_tokens", 10L)));

        GenAiEnrichmentHelper.enrichBatch(spans, GenAiRootPropagation.NONE);

        final Map<String, Object> root = byId(spans, "agent").getAttributes();
        assertNull(root.get("gen_ai.request.model"));
        // Marking does not depend on propagation: the marked agent root still gets its
        // descendants' token totals.
        assertEquals(true, root.get(DERIVED_GEN_AI_IS_ROOT_KEY));
        assertEquals(10L, ((Number) root.get("gen_ai.usage.input_tokens")).longValue());
    }

    @Test
    void copiesOntoANonGenAiRootWithPropagationAllRoots() {
        final List<Span> spans = httpAgentTrace();

        GenAiEnrichmentHelper.enrichBatch(spans, GenAiRootPropagation.ALL_ROOTS);

        final Map<String, Object> http = byId(spans, "http").getAttributes();
        assertEquals("Travel Planner", http.get("gen_ai.agent.name"));
        assertEquals(15L, ((Number) http.get("gen_ai.usage.input_tokens")).longValue());
        assertNull(http.get(DERIVED_GEN_AI_IS_ROOT_KEY));
    }

    @Test
    void processor_withEnrichmentDisabled_leavesGenAiSpansUnchanged() {
        final List<Span> spans = vendorAgentTrace();
        final Map<String, Map<String, Object>> before = spans.stream()
                .collect(Collectors.toMap(Span::getSpanId, s -> new HashMap<>(s.getAttributes())));

        final List<Span> out = execute(processor(false, GenAiRootPropagation.ALL_ROOTS, mock(PluginMetrics.class)), spans);

        assertEquals(spans.size(), out.size());
        for (final Span span : out) {
            assertEquals(before.get(span.getSpanId()), span.getAttributes(), span.getSpanId());
        }
    }

    @Test
    void processor_withEnrichmentDisabled_marksNothing() {
        final List<Span> out = execute(processor(false, null, mock(PluginMetrics.class)), nestedWorkflowTrace());

        assertEquals(List.of(), markedSpanIds(out));
        for (final Span span : out) {
            assertFalse(span.getAttributes().containsKey(DERIVED_GEN_AI_IS_ROOT_KEY), span.getSpanId());
        }
    }

    @Test
    void processor_withEnrichmentEnabled_normalizesStripsAndMarks() {
        final List<Span> out = execute(processor(true, GenAiRootPropagation.AI_ROOT, mock(PluginMetrics.class)), vendorAgentTrace());

        assertEquals(List.of("agent"), markedSpanIds(out));
        final Map<String, Object> chat = byId(out, "chat").getAttributes();
        assertEquals(12L, ((Number) chat.get("gen_ai.usage.input_tokens")).longValue());
        assertEquals("gpt-4o", chat.get("gen_ai.request.model"));
        assertFalse(chat.containsKey("llm.input_messages.0.message.content"));
        assertEquals(Map.of("http.method", "POST"), byId(out, "http").getAttributes());
    }

    @Test
    void processor_registersNoGenAiCoverageMetrics() {
        final PluginMetrics pluginMetrics = mock(PluginMetrics.class);

        execute(processor(true, GenAiRootPropagation.AI_ROOT, pluginMetrics), httpAgentTrace());

        verify(pluginMetrics, never()).gauge(eq("genAiCoverageCount"), any(), any(ToDoubleFunction.class));
        verify(pluginMetrics, never()).gauge(eq("genAiCoverageSpanIdCount"), any(), any(ToDoubleFunction.class));
    }

    @Test
    void processor_withTheDeprecatedCoverageCacheSizeSet_startsAndMarks() {
        final OtelTraceRawProcessorConfig config = new ObjectMapper().convertValue(
                Map.of("genai_coverage_cache_max_size", 10), OtelTraceRawProcessorConfig.class);

        final OTelTraceRawProcessor processor = new OTelTraceRawProcessor(config, mock(PipelineDescription.class),
                mock(PluginMetrics.class));

        assertEquals(List.of("agent"), markedSpanIds(execute(processor, httpAgentTrace())));
    }

    @Test
    void warnOnDeprecatedSettings_logsOneWarningWhenTheCoverageCacheSizeIsSet() {
        final OtelTraceRawProcessorConfig config = new ObjectMapper().convertValue(
                Map.of("genai_coverage_cache_max_size", 500000), OtelTraceRawProcessorConfig.class);
        final Logger logger = mock(Logger.class);

        OTelTraceRawProcessor.warnOnDeprecatedSettings(config, logger);

        verify(logger, times(1)).warn(contains("genai_coverage_cache_max_size is deprecated and has no effect"));
        verifyNoMoreInteractions(logger);
    }

    @Test
    void warnOnDeprecatedSettings_logsNothingWhenTheCoverageCacheSizeIsNotSet() {
        final Logger logger = mock(Logger.class);

        OTelTraceRawProcessor.warnOnDeprecatedSettings(new OtelTraceRawProcessorConfig(), logger);

        verifyNoInteractions(logger);
    }

    @Test
    void processor_withAiRootPropagation_leavesTheHttpRootUnchanged() {
        final List<Span> out = execute(processor(true, GenAiRootPropagation.AI_ROOT, mock(PluginMetrics.class)), httpAgentTrace());

        assertEquals(Map.of("http.method", "POST"), byId(out, "http").getAttributes());
        assertEquals(List.of("agent"), markedSpanIds(out));
    }

    @Test
    void processor_withAllRootsPropagation_copiesOntoTheHttpRoot() {
        final List<Span> out = execute(processor(true, GenAiRootPropagation.ALL_ROOTS, mock(PluginMetrics.class)), httpAgentTrace());

        final Map<String, Object> http = byId(out, "http").getAttributes();
        assertEquals("Travel Planner", http.get("gen_ai.agent.name"));
        assertEquals(15L, ((Number) http.get("gen_ai.usage.input_tokens")).longValue());
        assertEquals(List.of("agent"), markedSpanIds(out));
    }

    @Test
    void processor_withNonePropagation_copiesOntoNoRootButStillMarks() {
        final List<Span> spans = new ArrayList<>();
        spans.add(span("agent", "", "2026-01-01T00:00:00Z", Map.of("gen_ai.operation.name", "invoke_agent")));
        spans.add(span("chat", "agent", "2026-01-01T00:00:01Z", Map.of(
                "gen_ai.operation.name", "chat", "gen_ai.request.model", "claude", "gen_ai.usage.input_tokens", 10L)));

        final List<Span> out = execute(processor(true, GenAiRootPropagation.NONE, mock(PluginMetrics.class)), spans);

        final Map<String, Object> root = byId(out, "agent").getAttributes();
        assertNull(root.get("gen_ai.request.model"));
        assertEquals(true, root.get(DERIVED_GEN_AI_IS_ROOT_KEY));
        assertEquals(10L, ((Number) root.get("gen_ai.usage.input_tokens")).longValue());
    }

    @Test
    void processor_withNoPropagationConfigured_defaultsToAiRoot() {
        final List<Span> out = execute(processor(true, null, mock(PluginMetrics.class)), httpAgentTrace());

        assertEquals(Map.of("http.method", "POST"), byId(out, "http").getAttributes());
        assertEquals(List.of("agent"), markedSpanIds(out));
    }

    @Test
    void copiesOntoAGenAiRootThatIsSetToFalseAtTheSource() {
        // Opting a span out of the marker does not stop it being its trace's GenAI root.
        final List<Span> spans = new ArrayList<>();
        spans.add(span("agent", "", "2026-01-01T00:00:00Z", Map.of(
                "gen_ai.operation.name", "invoke_agent", DERIVED_GEN_AI_IS_ROOT_KEY, false)));
        spans.add(span("chat", "agent", "2026-01-01T00:00:01Z", Map.of(
                "gen_ai.operation.name", "chat", "gen_ai.request.model", "claude", "gen_ai.usage.input_tokens", 10L)));

        GenAiEnrichmentHelper.enrichBatch(spans);

        final Map<String, Object> root = byId(spans, "agent").getAttributes();
        assertEquals("claude", root.get("gen_ai.request.model"));
        assertEquals(false, root.get(DERIVED_GEN_AI_IS_ROOT_KEY));
        assertEquals(List.of(), markedSpanIds(spans));
    }

    @Test
    void keepsTheRootsOwnAttributesWithPropagationAllRoots() {
        final List<Span> spans = httpAgentTrace();
        spans.set(0, span("http", "", "2026-01-01T00:00:00Z", Map.of(
                "http.method", "POST", "gen_ai.agent.name", "Gateway", "gen_ai.usage.input_tokens", 1L)));

        GenAiEnrichmentHelper.enrichBatch(spans, GenAiRootPropagation.ALL_ROOTS);

        final Map<String, Object> http = byId(spans, "http").getAttributes();
        assertEquals("Gateway", http.get("gen_ai.agent.name"));
        assertEquals(1L, ((Number) http.get("gen_ai.usage.input_tokens")).longValue());
    }

    @Test
    void copiesNothingOntoASingleSpanTraceInAnyMode() {
        for (final GenAiRootPropagation propagation : GenAiRootPropagation.values()) {
            final List<Span> spans = new ArrayList<>();
            spans.add(span("agent", "", "2026-01-01T00:00:00Z", Map.of("gen_ai.operation.name", "invoke_agent")));

            GenAiEnrichmentHelper.enrichBatch(spans, propagation);

            assertEquals(Map.of("gen_ai.operation.name", "invoke_agent", DERIVED_GEN_AI_IS_ROOT_KEY, true),
                    spans.get(0).getAttributes(), propagation.toString());
        }
    }

    @Test
    void copiesOntoAGenAiRootInEveryModeButNone() {
        for (final GenAiRootPropagation propagation : GenAiRootPropagation.values()) {
            final List<Span> spans = new ArrayList<>();
            spans.add(span("agent", "", "2026-01-01T00:00:00Z", Map.of("gen_ai.operation.name", "invoke_agent")));
            spans.add(span("chat", "agent", "2026-01-01T00:00:01Z", Map.of(
                    "gen_ai.operation.name", "chat", "gen_ai.provider.name", "openai")));

            GenAiEnrichmentHelper.enrichBatch(spans, propagation);

            final Object provider = byId(spans, "agent").getAttributes().get("gen_ai.provider.name");
            assertEquals(propagation == GenAiRootPropagation.NONE ? null : "openai", provider, propagation.toString());
        }
    }

    @Test
    void doesNotCopyOntoAnyRootOfParallelAgentsByDefault() {
        final List<Span> spans = new ArrayList<>();
        spans.add(span("http", "", "2026-01-01T00:00:00Z", Map.of("http.method", "POST")));
        spans.add(span("agentA", "http", "2026-01-01T00:00:01Z", Map.of(
                "gen_ai.operation.name", "invoke_agent", "gen_ai.agent.name", "A", "gen_ai.usage.input_tokens", 3L)));
        spans.add(span("agentB", "http", "2026-01-01T00:00:02Z", Map.of(
                "gen_ai.operation.name", "invoke_agent", "gen_ai.agent.name", "B", "gen_ai.usage.input_tokens", 4L)));

        GenAiEnrichmentHelper.enrichBatch(spans);

        assertEquals(Map.of("http.method", "POST"), byId(spans, "http").getAttributes());
        assertEquals(List.of("agentA", "agentB"), markedSpanIds(spans));
        assertEquals("A", byId(spans, "agentA").getAttributes().get("gen_ai.agent.name"));
        assertEquals("B", byId(spans, "agentB").getAttributes().get("gen_ai.agent.name"));
    }

    /** POST /chat > OpenInference AGENT > LLM, with a JSON string and its flattened sub-keys. */
    private static List<Span> vendorAgentTrace() {
        final List<Span> spans = new ArrayList<>();
        spans.add(span("http", "", "2026-01-01T00:00:00Z", Map.of("http.method", "POST")));
        spans.add(span("agent", "http", "2026-01-01T00:00:01Z", Map.of("openinference.span.kind", "AGENT")));
        spans.add(span("chat", "agent", "2026-01-01T00:00:02Z", Map.of(
                "openinference.span.kind", "LLM",
                "llm.model_name", "gpt-4o",
                "llm.token_count.prompt", 12L,
                "llm.input_messages", "[{\"role\":\"user\",\"content\":\"hi\"}]",
                "llm.input_messages.0.message.content", "hi")));
        return spans;
    }

    private static OTelTraceRawProcessor processor(final boolean enrichmentEnabled, final GenAiRootPropagation propagation,
                                                   final PluginMetrics pluginMetrics) {
        final OtelTraceRawProcessorConfig config = mock(OtelTraceRawProcessorConfig.class);
        when(config.getTraceFlushIntervalSeconds()).thenReturn(180L);
        when(config.getTraceGroupCacheMaxSize()).thenReturn(OtelTraceRawProcessorConfig.MAX_TRACE_ID_CACHE_SIZE);
        when(config.isGenAiEnrichmentDisabled()).thenReturn(!enrichmentEnabled);
        when(config.getGenAiRootPropagation()).thenReturn(propagation);
        when(config.getTraceGroupCacheTimeToLive()).thenReturn(OtelTraceRawProcessorConfig.DEFAULT_TRACE_ID_TTL);
        return new OTelTraceRawProcessor(config, mock(PipelineDescription.class), pluginMetrics);
    }

    private static List<Span> execute(final OTelTraceRawProcessor processor, final List<Span> spans) {
        return processor.doExecute(records(spans)).stream().map(Record::getData).collect(Collectors.toList());
    }

    private static List<Span> httpAgentTrace() {
        final List<Span> spans = new ArrayList<>();
        spans.add(span("http", "", "2026-01-01T00:00:00Z", Map.of("http.method", "POST")));
        spans.add(span("agent", "http", "2026-01-01T00:00:01Z", Map.of(
                "gen_ai.operation.name", "invoke_agent", "gen_ai.agent.name", "Travel Planner")));
        spans.add(span("chat1", "agent", "2026-01-01T00:00:02Z", Map.of(
                "gen_ai.operation.name", "chat",
                "gen_ai.usage.input_tokens", 10L, "gen_ai.usage.output_tokens", 4L)));
        spans.add(span("tool", "agent", "2026-01-01T00:00:04Z", Map.of(
                "gen_ai.operation.name", "execute_tool")));
        spans.add(span("chat2", "tool", "2026-01-01T00:00:05Z", Map.of(
                "gen_ai.operation.name", "chat",
                "gen_ai.usage.input_tokens", 5L, "gen_ai.usage.output_tokens", 3L)));
        return spans;
    }

    /** invoke_workflow > (plan: chat), invoke_agent outer > invoke_agent inner > chat. */
    private static List<Span> nestedWorkflowTrace() {
        final List<Span> spans = new ArrayList<>();
        spans.add(span("workflow", "", "2026-01-01T00:00:00Z", Map.of("gen_ai.operation.name", "invoke_workflow")));
        spans.add(span("plan", "workflow", "2026-01-01T00:00:01Z", Map.of(
                "gen_ai.operation.name", "chat", "gen_ai.usage.input_tokens", 3L, "gen_ai.usage.output_tokens", 1L)));
        spans.add(span("outer", "workflow", "2026-01-01T00:00:02Z", Map.of("gen_ai.operation.name", "invoke_agent")));
        spans.add(span("inner", "outer", "2026-01-01T00:00:03Z", Map.of("gen_ai.operation.name", "invoke_agent")));
        spans.add(span("chat", "inner", "2026-01-01T00:00:04Z", Map.of(
                "gen_ai.operation.name", "chat", "gen_ai.usage.input_tokens", 10L, "gen_ai.usage.output_tokens", 4L)));
        return spans;
    }

    private static List<Integer> range(final int from, final int to) {
        return IntStream.range(from, to).boxed().collect(Collectors.toCollection(ArrayList::new));
    }

    private static Span span(final String spanId, final String parentSpanId, final String startTime,
                             final Map<String, Object> attributes) {
        return span(TRACE_ID, spanId, parentSpanId, startTime, attributes);
    }

    private static Span span(final String traceId, final String spanId, final String parentSpanId, final String startTime,
                             final Map<String, Object> attributes) {
        return JacksonSpan.builder()
                .withTraceId(traceId)
                .withSpanId(spanId)
                .withParentSpanId(parentSpanId)
                .withTraceState("")
                .withName(spanId)
                .withKind("INTERNAL")
                .withDurationInNanos(1L)
                .withStartTime(startTime)
                .withEndTime("2026-01-01T00:01:00Z")
                .withTraceGroup(null)
                .withTraceGroupFields(DefaultTraceGroupFields.builder().build())
                .withAttributes(new HashMap<>(attributes))
                .build();
    }

    private static List<String> markedSpanIds(final Collection<Span> spans) {
        return spans.stream()
                .filter(s -> Boolean.TRUE.equals(s.getAttributes().get(DERIVED_GEN_AI_IS_ROOT_KEY)))
                .map(Span::getSpanId)
                .sorted()
                .collect(Collectors.toList());
    }

    private static Span byId(final Collection<Span> spans, final String spanId) {
        return spans.stream().filter(s -> spanId.equals(s.getSpanId())).findFirst().orElseThrow();
    }

    private static List<Record<Span>> records(final List<Span> spans) {
        return spans.stream().map(Record::new).collect(Collectors.toList());
    }
}
