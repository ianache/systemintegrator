package com.cl2.integration.integration.sync;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SyncPolicy(String cronExpression, Integer overlapBufferSeconds) {

    public int overlapBufferSecondsOrZero() {
        return overlapBufferSeconds == null ? 0 : overlapBufferSeconds;
    }
}
