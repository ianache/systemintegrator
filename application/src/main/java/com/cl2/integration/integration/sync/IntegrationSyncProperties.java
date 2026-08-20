package com.cl2.integration.integration.sync;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "integration.sync")
public class IntegrationSyncProperties {
    private int defaultRunLockAtMostForSeconds = 600;

    public int getDefaultRunLockAtMostForSeconds() { return defaultRunLockAtMostForSeconds; }
    public void setDefaultRunLockAtMostForSeconds(int defaultRunLockAtMostForSeconds) {
        this.defaultRunLockAtMostForSeconds = defaultRunLockAtMostForSeconds;
    }
}
