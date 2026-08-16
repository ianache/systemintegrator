package com.cl2.integration.integration.security;

public class SecretNotFoundException extends RuntimeException {
    private final String credentialRef;

    public SecretNotFoundException(String credentialRef) {
        super("Secret not found for credentialRef: " + credentialRef);
        this.credentialRef = credentialRef;
    }

    public String getCredentialRef() { return credentialRef; }
}
