/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.dataprepper.plugins.source.oteltelemetry;

import com.fasterxml.jackson.annotation.JsonProperty;

public class OTelTelemetrySourceConfig {
    @JsonProperty("port")
    private int port = 21892;

    @JsonProperty("enable_reflection")
    private boolean enableReflection = false;

    public int getPort() {
        return port;
    }

    public boolean isEnableReflection() {
        return enableReflection;
    }
}
