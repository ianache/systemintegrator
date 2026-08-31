package com.cl2.integration.integration.extraction;

import java.util.List;
import java.util.Map;

public record ExtractionDryRunResult(List<Map<String, Object>> rows, Integer totalFetched, String error) {

    public static ExtractionDryRunResult success(List<Map<String, Object>> rows, int totalFetched) {
        return new ExtractionDryRunResult(rows, totalFetched, null);
    }

    public static ExtractionDryRunResult failure(String error) {
        return new ExtractionDryRunResult(null, null, error);
    }
}
