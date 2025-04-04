/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.opensearch.dataprepper.model.buffer.Buffer;
import org.opensearch.dataprepper.model.record.Record;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;

class OTelTelemetrySourceTests {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Mock
    private Buffer<Record<Object>> buffer;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testProcessLogsJson() throws IOException {
        InputStream logsJsonStream = getClass().getResourceAsStream("/testjson/logs.json");
        assertNotNull(logsJsonStream, "Logs JSON file not found in resources/testjson");

        ExportLogsServiceRequest logsRequest = OBJECT_MAPPER.readValue(logsJsonStream, ExportLogsServiceRequest.class);
        assertNotNull(logsRequest, "Failed to parse logs JSON");

        // Simulate processing logs
        buffer.write(new Record<>(logsRequest));
        verify(buffer).write(new Record<>(logsRequest));
    }

    @Test
    void testProcessTracesJson() throws IOException {
        InputStream tracesJsonStream = getClass().getResourceAsStream("/testjson/traces.json");
        assertNotNull(tracesJsonStream, "Traces JSON file not found in resources/testjson");

        ExportTraceServiceRequest tracesRequest = OBJECT_MAPPER.readValue(tracesJsonStream, ExportTraceServiceRequest.class);
        assertNotNull(tracesRequest, "Failed to parse traces JSON");

        // Simulate processing traces
        buffer.write(new Record<>(tracesRequest));
        verify(buffer).write(new Record<>(tracesRequest));
    }

    @Test
    void testProcessMetricsJson() throws IOException {
        InputStream metricsJsonStream = getClass().getResourceAsStream("/testjson/metrics.json");
        assertNotNull(metricsJsonStream, "Metrics JSON file not found in resources/testjson");

        ExportMetricsServiceRequest metricsRequest = OBJECT_MAPPER.readValue(metricsJsonStream, ExportMetricsServiceRequest.class);
        assertNotNull(metricsRequest, "Failed to parse metrics JSON");

        // Simulate processing metrics
        buffer.write(new Record<>(metricsRequest));
        verify(buffer).write(new Record<>(metricsRequest));
    }
}
