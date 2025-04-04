/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.dataprepper.plugins.source.oteltelemetry;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;
import io.grpc.ServerInterceptors;
import io.grpc.protobuf.services.ProtoReflectionService;
import org.opensearch.dataprepper.model.annotations.DataPrepperPlugin;
import org.opensearch.dataprepper.model.annotations.DataPrepperPluginConstructor;
import org.opensearch.dataprepper.model.buffer.Buffer;
import org.opensearch.dataprepper.model.configuration.PipelineDescription;
import org.opensearch.dataprepper.model.plugin.PluginFactory;
import org.opensearch.dataprepper.model.record.Record;
import org.opensearch.dataprepper.plugins.otel.codec.OTelLogsDecoder;
import org.opensearch.dataprepper.plugins.otel.codec.OTelMetricsDecoder;
import org.opensearch.dataprepper.plugins.otel.codec.OTelTracesDecoder;
import org.opensearch.dataprepper.plugins.source.otellogs.OTelLogsGrpcService;
import org.opensearch.dataprepper.plugins.source.otellogs.OTelMetricsGrpcService;
import org.opensearch.dataprepper.plugins.source.otellogs.OTelTracesGrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@DataPrepperPlugin(name = "otel_telemetry_source", pluginType = Source.class, pluginConfigurationType = OTelTelemetrySourceConfig.class)
public class OTelTelemetrySource implements Source<Record<Object>> {
    private static final Logger LOG = LoggerFactory.getLogger(OTelTelemetrySource.class);

    private final OTelTelemetrySourceConfig config;
    private final String pipelineName;
    private final OTelLogsDecoder logsDecoder;
    private final OTelMetricsDecoder metricsDecoder;
    private final OTelTracesDecoder tracesDecoder;
    private Server server;

    @DataPrepperPluginConstructor
    public OTelTelemetrySource(final OTelTelemetrySourceConfig config,
                                final PluginFactory pluginFactory,
                                final PipelineDescription pipelineDescription) {
        this.config = config;
        this.pipelineName = pipelineDescription.getPipelineName();
        this.logsDecoder = new OTelLogsDecoder();
        this.metricsDecoder = new OTelMetricsDecoder();
        this.tracesDecoder = new OTelTracesDecoder();
    }

    @Override
    public void start(Buffer<Record<Object>> buffer) {
        if (buffer == null) {
            throw new IllegalStateException("Buffer provided is null");
        }

        final GrpcServiceBuilder grpcServiceBuilder = GrpcService.builder()
                .useBlockingTaskExecutor(true);

        grpcServiceBuilder.addService("/v1/logs", ServerInterceptors.intercept(
            new OTelLogsGrpcService(buffer, record -> {
                record.getData().put("event.type", TelemetryData.Type.LOG.getValue()); // Add event.type for routing
                buffer.write(record);
            })));
        grpcServiceBuilder.addService("/v1/metrics", ServerInterceptors.intercept(
            new OTelMetricsGrpcService(buffer, record -> {
                record.getData().put("event.type", TelemetryData.Type.METRIC.getValue()); // Add event.type for routing
                buffer.write(record);
            })));
        grpcServiceBuilder.addService("/v1/traces", ServerInterceptors.intercept(
            new OTelTracesGrpcService(buffer, record -> {
                record.getData().put("event.type", TelemetryData.Type.TRACE.getValue()); // Add event.type for routing
                buffer.write(record);
            })));

        if (config.isEnableReflection()) {
            grpcServiceBuilder.addService(ProtoReflectionService.newInstance());
        }

        final ServerBuilder serverBuilder = Server.builder()
                .http(config.getPort())
                .service(grpcServiceBuilder.build());

        server = serverBuilder.build();
        try {
            server.start().get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to start OTelTelemetrySource server", e);
        }

        LOG.info("Started OTelTelemetrySource on port {}", config.getPort());
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop();
            LOG.info("Stopped OTelTelemetrySource.");
        }
    }
}
