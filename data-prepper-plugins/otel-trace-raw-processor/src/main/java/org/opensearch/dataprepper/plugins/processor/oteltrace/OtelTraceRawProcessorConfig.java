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

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import jakarta.validation.constraints.AssertTrue;

import java.time.Duration;

@JsonPropertyOrder
@JsonClassDescription("The <code>otel_traces</code> processor completes trace-group-related fields in all incoming " +
        "span records by state caching the root span information for each <code>traceId</code>.")
public class OtelTraceRawProcessorConfig {
    static final long DEFAULT_TG_FLUSH_INTERVAL_SEC = 180L;
    static final Duration DEFAULT_TRACE_ID_TTL = Duration.ofSeconds(15L);
    static final long MAX_TRACE_ID_CACHE_SIZE = 1_000_000L;

    @JsonProperty(value = "trace_flush_interval", defaultValue = "180")
    @JsonPropertyDescription("Represents the time interval in seconds to flush all the descendant spans without any " +
            "root span. Default is <code>180</code>.")
    private long traceFlushInterval = DEFAULT_TG_FLUSH_INTERVAL_SEC;

    @JsonProperty(value = "trace_group_cache_ttl", defaultValue = "PT15S")
    @JsonPropertyDescription("Represents the time-to-live to cache a trace group details. " +
            "The value may be an ISO 8601 notation such as <code>PT1M30S</code> or a duration and unit such as <code>45s</code>. " +
            "Default is 15 seconds.")
    private Duration traceGroupCacheTimeToLive = DEFAULT_TRACE_ID_TTL;

    @JsonProperty(value = "trace_group_cache_max_size", defaultValue = "1000000")
    @JsonPropertyDescription("Represents the maximum size of the cache to store the trace group details from root spans. " +
            "Default is <code>1000000</code>.")
    private long traceGroupCacheMaxSize = MAX_TRACE_ID_CACHE_SIZE;

    @JsonProperty(value = "disable_genai_enrichment", defaultValue = "false")
    @JsonPropertyDescription("Turns off GenAI agent trace enrichment: normalizing vendor attributes to <code>gen_ai.*</code>, " +
            "stripping flattened sub-keys that conflict with their parent, marking agent root spans and copying agent data " +
            "onto trace roots. Traces without GenAI data are left unchanged either way. Default is <code>false</code>.")
    private boolean disableGenAiEnrichment = false;

    /**
     * @deprecated GenAI agent root marking no longer keeps a cache. The setting is accepted so
     * pipelines that set it still start, its value is ignored, and it will be removed in 3.0.
     */
    @Deprecated
    @JsonProperty(value = "genai_coverage_cache_max_size")
    @JsonPropertyDescription("Deprecated. Has no effect and will be removed in 3.0. GenAI agent root marking no longer " +
            "keeps a cache of span ids, so the value is ignored and a warning is logged at startup when it is set.")
    private Long genAiCoverageCacheMaxSize;

    @JsonProperty(value = "genai_root_propagation", defaultValue = "ai_root")
    @JsonPropertyDescription("Which trace root spans get <code>gen_ai.system</code>, <code>gen_ai.provider.name</code>, " +
            "<code>gen_ai.agent.name</code>, <code>gen_ai.request.model</code> and token totals copied from their children. " +
            "<code>ai_root</code> copies them only onto a root that is its trace's AI root (a GenAI span, or a span marked " +
            "<code>derived.gen_ai.is_root: true</code> at the source). <code>all_roots</code> also copies them onto " +
            "non-GenAI roots, such as the HTTP span an agent runs behind. <code>none</code> copies nothing. " +
            "Values come only from spans in the same batch as the root, so this is best effort when spans of a trace " +
            "arrive in different batches; consumers should sum tokens from model-call spans. " +
            "Default is <code>ai_root</code>.")
    private GenAiRootPropagation genAiRootPropagation;

    public long getTraceFlushIntervalSeconds() {
        return traceFlushInterval;
    }

    public Duration getTraceGroupCacheTimeToLive() {
        return traceGroupCacheTimeToLive;
    }

    public long getTraceGroupCacheMaxSize() {
        return traceGroupCacheMaxSize;
    }

    public boolean isGenAiEnrichmentDisabled() {
        return disableGenAiEnrichment;
    }

    /**
     * Whether the deprecated {@code genai_coverage_cache_max_size} is set. Its value is ignored;
     * the processor only logs a deprecation warning.
     *
     * @return {@code true} when the setting is present in the pipeline configuration
     */
    public boolean isGenAiCoverageCacheMaxSizeSet() {
        return genAiCoverageCacheMaxSize != null;
    }

    public GenAiRootPropagation getGenAiRootPropagation() {
        return genAiRootPropagation != null ? genAiRootPropagation : GenAiRootPropagation.AI_ROOT;
    }

    @AssertTrue(message = "genai_root_propagation cannot be set when disable_genai_enrichment is true")
    boolean isGenAiSettingsValid() {
        return !disableGenAiEnrichment || genAiRootPropagation == null;
    }
}
