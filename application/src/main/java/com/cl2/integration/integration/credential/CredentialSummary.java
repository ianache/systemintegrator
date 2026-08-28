package com.cl2.integration.integration.credential;

import java.time.Instant;
import java.util.List;

public record CredentialSummary(
        String ref,
        String type,
        List<String> usedBy,
        Instant rotatedAt,
        String state
) {
}
