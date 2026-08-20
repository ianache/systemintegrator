package com.cl2.integration.adapter.out.generic.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AuthConfig(
        String authType,
        String tokenUrl,
        String clientId,
        String clientSecretRef,
        String scope,
        String tokenRef,
        String credentialRef,
        String headerName,
        String keyRef
) {}
