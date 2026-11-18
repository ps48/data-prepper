# OTel Trace Raw Processor

This is a processor that receives collection of [Span](../../data-prepper-api/src/main/java/com/amazon/dataprepper/model/trace/Span.java) records sent from [otel-trace-source](../dataPrepper-plugins/otel-trace-source), does stateful processing on extracting and filling-in trace group related fields.

> Note: `otel_trace_raw` processor has been renamed to `otel_traces`. You can use either name for now but the support for `otel_trace_raw` will be removed in major version 3.0.

## Usages
Example `.yaml` configuration
```
processor:
    - otel_trace_raw:
```

## Configuration

* `trace_flush_interval`: An `int` represents the time interval in seconds to flush all the descendant spans without any root span. Default to 180.
* `trace_group_cache_ttl`: A `Duration` represents the time-to-live for traces in the trace group cache. Defaults to 15 seconds.
* `trace_group_cache_max_size`: An `int` representing the total number of traces to keep in the trace group cache.
* `disable_genai_enrichment`: A `boolean` that turns off [GenAI agent trace enrichment](#genai-agent-trace-enrichment). Default is `false`. When `true`, `genai_root_propagation` cannot be set.
* `genai_root_propagation`: Which trace root spans get GenAI attributes and token totals copied from their children: `ai_root`, `all_roots` or `none`, written exactly so (see [Root span enrichment](#root-span-enrichment)). Best effort when spans of a trace arrive in different batches. Default is `ai_root`.
* `genai_coverage_cache_max_size`: **Deprecated**, has no effect and will be removed in 3.0. Agent root marking no longer keeps a cache of span ids, so the value is ignored. Pipelines that set it still start, and the processor logs a warning at startup. Remove it from your configuration.

## GenAI agent trace enrichment

The processor enriches traces from GenAI agents and LLM applications, so that agent runs can be found, listed and measured from the spans themselves. It is on by default and does nothing to traces without GenAI data. Set `disable_genai_enrichment: true` to turn it off: span attributes are then left as they arrive, with no normalized attributes, no sub-key stripping, no markers and no root enrichment.

It needs the source to use `output_format: otel`, because it matches attributes by their OTel key names (for example `gen_ai.operation.name`). The default `opensearch` format rewrites keys (`http.method` becomes `span.attributes.http@method`) and the enrichment then finds nothing. See the [otel-trace-source README](../otel-trace-source/README.md#output-format).

Example with the GenAI settings at their defaults:

```
processor:
    - otel_traces:
        disable_genai_enrichment: false
        genai_root_propagation: ai_root
```

Each batch is enriched in this order, per trace. The processor keeps no GenAI state between batches.

1. [Vendor attribute normalization](#vendor-attribute-normalization)
2. [Flattened sub-key stripping](#flattened-sub-key-stripping)
3. [Root span enrichment](#root-span-enrichment)
4. [Agent root marker](#agent-root-marker)

### Vendor attribute normalization

Instrumentation libraries name the same data differently: input tokens are `llm.token_count.prompt` in OpenInference, `llm.usage.prompt_tokens` in OpenLLMetry and `gen_ai.usage.input_tokens` in the [OTel GenAI semantic conventions](https://opentelemetry.io/docs/specs/semconv/gen-ai/). The processor copies vendor attributes to their `gen_ai.*` names, so queries, dashboards and the rest of the enrichment only need the standard names.

* The mappings are listed in [`genai-attribute-mappings.yaml`](src/main/resources/genai-attribute-mappings.yaml), grouped by source: `openinference`, `openllmetry` (Traceloop) and `claude_code`.
* Original attributes are kept; the `gen_ai.*` copy is added next to them.
* A `gen_ai.*` attribute already on the span is never overwritten.
* When several attributes on one span map to the same `gen_ai.*` name, the one listed first in the file wins. For example OpenInference `session.id` wins over Traceloop's `traceloop.association.properties.session_id`; both map to `gen_ai.conversation.id`.
* Operation kinds are mapped to `gen_ai.operation.name` values, for example OpenInference `LLM` to `chat`, `AGENT` and `CHAIN` to `invoke_agent`, `TOOL` to `execute_tool` (see `operation_name_values` in the file).
* Some mappings wrap a single value in a JSON array (`wrap_as_array`), for example a finish reason into `gen_ai.response.finish_reasons`.

### Flattened sub-key stripping

Some instrumentations emit the same field twice: as a JSON string (`llm.input_messages`) and as flattened sub-keys (`llm.input_messages.0.message.content`). OpenSearch rejects such documents with a mapping conflict. When the parent string is present, the processor removes the indexed sub-keys. When only sub-keys are present, they are kept, so no data is lost.

### Agent root marker

Consumers need to find the span that represents each agent run, but agents usually run behind a service: the trace's root is an HTTP or RPC span, such as `POST /plan`, and the agent span is below it. The processor marks agent spans with `derived.gen_ai.is_root: true`.

* **What is marked.** Every span whose `gen_ai.operation.name`, after [vendor attribute normalization](#vendor-attribute-normalization), is `invoke_agent` or `invoke_workflow`. The values are matched exactly. That includes a GenAI root, an agent below a non-GenAI root, parallel agents, and agents nested in other agents or called by them over HTTP: each of them is marked.
* **What is not marked.** Any other operation, such as `chat`, `execute_tool`, `create_agent` or `embeddings`, and spans without `gen_ai.operation.name`. A trace made only of model calls has no marked span.
* **Decided per span.** The marker depends only on the span's own attributes, not on its parent, the other spans in the batch or earlier batches. Spans of one trace can arrive in any order and in any number of batches, through any pipeline worker or Data Prepper node, and are marked the same way.
* **Markers set at the source.** An SDK or the user can set the marker (for example with `mark_agent_root()` in the GenAI observability SDK for Python, or `markAgentRoot()` in TypeScript). When a span already has `derived.gen_ai.is_root`, `true` or `false`, the processor leaves it unchanged; set it to `false` to keep an agent span from being marked. A marker set on one span does not change how the other spans of the trace are marked.
* **Token totals.** A span the processor marks that reports no token usage of its own gets the usage of its descendants in the same batch, counted as in [Token usage](#token-usage). This is best effort: descendants that arrive in another batch are not counted, and the span is not updated later. Sum token usage from the model-call spans (`chat`, `text_completion`, `generate_content`) when you need exact totals.
* **Consumers.** Take the earliest marked span of a trace (by start time) as its agent root, and list the trace's other marked spans as its sub-agents or parallel agents. For data indexed before the marker existed, fall back to root spans that are GenAI spans.

Before this rule, the processor marked the outermost GenAI span of each trace, found from the other spans of the batch and a cache of span ids seen in earlier batches. That result depended on how spans were split into batches, so a child that arrived before its parent could be marked as a second agent root.

### Root span enrichment

The processor can copy `gen_ai.system`, `gen_ai.provider.name`, `gen_ai.agent.name` and `gen_ai.request.model`, and the trace's token totals, from child spans onto the trace's root span. Attributes the root already has are kept.

`genai_root_propagation` chooses which roots get them:

| Value | Roots that get the copies |
|---|---|
| `ai_root` (default) | Only a root that is its trace's AI root: a GenAI span, or a span marked `derived.gen_ai.is_root: true` at the source. |
| `all_roots` | Every root, including non-GenAI roots such as the HTTP span an agent runs behind. This was the behavior before `ai_root`. |
| `none` | No root. |

With `ai_root`, a non-GenAI root keeps its own attributes. Copying agent data onto it made the HTTP span look like it called a model and used tokens, collapsed parallel agents into one agent name, and counted the trace's tokens again for any consumer that sums usage across spans. The marked agent spans carry that data instead. Use `all_roots` if a dashboard or query reads agent data from root spans and has not moved to the marked spans yet.

Values are taken from the outermost GenAI spans first (earliest start time), then other GenAI spans, then the remaining children.

Propagation is best effort. It reads only the children in the same batch as the root, and a root is never updated after it is written. When spans of a trace arrive in different batches (a child exported after its root, for example from a streaming response, a background call after the agent replied, or a tool in another process), the root can be stored without the model, agent name or token totals, or with partial totals. Consumers should read the model and agent name from the marked agent spans, and sum tokens from the model-call spans rather than reading them from the root.

Marking does not depend on this setting: with `none`, agent spans are still marked, and a marked agent span still gets its descendants' token totals.

### Token usage

Wherever the processor sums `gen_ai.usage.input_tokens` and `gen_ai.usage.output_tokens` (onto a root or onto a marked agent span), it counts only spans with no ancestor that also reports usage. Agent spans such as `invoke_agent` often report the total of their own LLM calls, so adding every span with usage would count those calls twice.

* This assumes an ancestor's reported usage includes its subtree. A sub-agent that runs its own LLM calls under a parent that reports only its own usage is not added.
* A span that reports either token count is treated as reporting usage, and keeps its own values.
* Token counts sent as numeric strings are read as numbers; other values are ignored.

## Metrics
In addition to the metrics from [AbstractProcessor](https://github.com/opensearch-project/data-prepper/blob/main/data-prepper-api/src/main/java/org/opensearch/dataprepper/model/processor/AbstractProcessor.java):

* `traceGroupCacheCount` - (gauge) The count of trace groups in the trace group cache
* `spanSetCount` - (gauge) The count of span sets in the span set collection

## Developer Guide
This plugin is compatible with Java 8. See 
- [CONTRIBUTING](https://github.com/opensearch-project/data-prepper/blob/main/CONTRIBUTING.md) 
- [monitoring](https://github.com/opensearch-project/data-prepper/blob/main/docs/monitoring.md)
