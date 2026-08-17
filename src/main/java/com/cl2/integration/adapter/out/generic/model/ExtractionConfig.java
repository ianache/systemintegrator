package com.cl2.integration.adapter.out.generic.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ExtractionConfig(
        String query,
        String watermarkParam,
        String keyColumn,
        Integer fetchSize,
        String method,
        String path,
        Map<String, String> queryParams,
        Map<String, String> headers,
        String responseJsonPath,
        String watermarkFormat,
        String keyProperty
) {
    public ExtractionConfig {
        if (watermarkParam == null || watermarkParam.isBlank()) {
            watermarkParam = "lastSyncWithBuffer";
        }
        if (fetchSize == null || fetchSize <= 0) {
            fetchSize = 500;
        }
        if (method == null || method.isBlank()) {
            method = "GET";
        }
        if (responseJsonPath == null || responseJsonPath.isBlank()) {
            responseJsonPath = "$";
        }
        if (watermarkFormat == null || watermarkFormat.isBlank()) {
            watermarkFormat = "ISO_8601";
        }
    }
}
