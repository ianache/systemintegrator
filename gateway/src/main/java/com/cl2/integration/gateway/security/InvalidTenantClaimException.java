package com.cl2.integration.gateway.security;

final class InvalidTenantClaimException extends RuntimeException {

    InvalidTenantClaimException() {
        super("The authenticated JWT must contain a valid tenant_id UUID claim");
    }
}
