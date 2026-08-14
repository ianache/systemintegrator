package com.cl2.integration.application.exception;

public class TenantRequiredException extends RuntimeException {

    public TenantRequiredException() {
        super("An active tenant is required");
    }
}
