package com.cl2.integration.adapter.out.http;

import com.cl2.integration.adapter.out.generic.security.OAuth2TokenCacheManager;
import com.cl2.integration.integration.security.AuthType;
import com.cl2.integration.integration.security.ResolvedSecret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

@Component
public class HttpOutboundClient {

    private static final Logger log = LoggerFactory.getLogger(HttpOutboundClient.class);

    private final RestClient restClient;
    private final OAuth2TokenCacheManager tokenCacheManager;

    public HttpOutboundClient() {
        this(RestClient.builder(), new OAuth2TokenCacheManager());
    }

    public HttpOutboundClient(RestClient.Builder builder) {
        this(builder, new OAuth2TokenCacheManager(builder));
    }

    @Autowired
    public HttpOutboundClient(RestClient.Builder builder, @Autowired(required = false) OAuth2TokenCacheManager tokenCacheManager) {
        this.restClient = builder.build();
        this.tokenCacheManager = tokenCacheManager != null ? tokenCacheManager : new OAuth2TokenCacheManager(builder);
    }

    public HttpOutboundClient(RestClient restClient) {
        this(restClient, new OAuth2TokenCacheManager());
    }

    public HttpOutboundClient(RestClient restClient, OAuth2TokenCacheManager tokenCacheManager) {
        this.restClient = restClient;
        this.tokenCacheManager = tokenCacheManager != null ? tokenCacheManager : new OAuth2TokenCacheManager();
    }

    public void send(String endpoint, ResolvedSecret secret, String payload) {
        send(endpoint, secret, payload, null);
    }

    public void send(String endpoint, ResolvedSecret secret, String payload, UUID tenantId) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("Endpoint URL cannot be null or blank");
        }

        try {
            restClient.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(httpHeaders -> applyAuthHeaders(httpHeaders, secret, tenantId))
                    .body(payload != null ? payload : "")
                    .retrieve()
                    .toBodilessEntity();
            log.debug("Successfully sent outbound HTTP event to endpoint: {}", endpoint);
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            String responseBody = ex.getResponseBodyAsString();
            log.warn("HTTP outbound dispatch to {} returned status {}: {}", endpoint, status, responseBody);
            throw new HttpOutboundException(
                    "HTTP request to " + endpoint + " failed with status " + status + ": " + responseBody,
                    status,
                    responseBody,
                    ex
            );
        } catch (ResourceAccessException ex) {
            log.warn("HTTP outbound dispatch to {} failed due to network/timeout error: {}", endpoint, ex.getMessage());
            throw new HttpOutboundException(
                    "HTTP request to " + endpoint + " failed due to network/timeout error: " + ex.getMessage(),
                    ex
            );
        } catch (Exception ex) {
            log.warn("HTTP outbound dispatch to {} failed unexpectedly: {}", endpoint, ex.getMessage());
            throw new HttpOutboundException(
                    "HTTP request to " + endpoint + " failed: " + ex.getMessage(),
                    ex
            );
        }
    }

    private void applyAuthHeaders(HttpHeaders httpHeaders, ResolvedSecret secret, UUID tenantId) {
        if (secret == null) {
            return;
        }

        if (secret.headers() != null) {
            secret.headers().forEach(httpHeaders::set);
        }

        AuthType authType = secret.authType();

        if (authType == AuthType.BEARER || (authType == null && secret.token() != null && !secret.token().isBlank())) {
            if (secret.token() != null && !secret.token().isBlank()) {
                httpHeaders.set(HttpHeaders.AUTHORIZATION, "Bearer " + secret.token());
            }
        } else if (authType == AuthType.BASIC || (authType == null && secret.username() != null)) {
            if (secret.username() != null && secret.password() != null) {
                String credentials = secret.username() + ":" + secret.password();
                String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
                httpHeaders.set(HttpHeaders.AUTHORIZATION, "Basic " + encoded);
            }
        } else if (authType == AuthType.API_KEY || (authType == null && secret.apiKey() != null && !secret.apiKey().isBlank())) {
            if (secret.apiKey() != null && !secret.apiKey().isBlank()) {
                if (!httpHeaders.containsKey("X-API-Key") && !httpHeaders.containsKey("x-api-key")) {
                    httpHeaders.set("X-API-Key", secret.apiKey());
                }
            }
        } else if (authType == AuthType.OAUTH2_CLIENT_CREDENTIALS) {
            if (secret.token() != null && !secret.token().isBlank()) {
                httpHeaders.set(HttpHeaders.AUTHORIZATION, "Bearer " + secret.token());
            } else if (secret.tokenUrl() != null && !secret.tokenUrl().isBlank()
                    && secret.clientId() != null && !secret.clientId().isBlank()
                    && tokenCacheManager != null) {
                String tenantIdStr = tenantId != null ? tenantId.toString() : null;
                String token = tokenCacheManager.getAccessToken(
                        tenantIdStr,
                        secret.tokenUrl(),
                        secret.clientId(),
                        secret.clientSecret(),
                        secret.scope()
                );
                if (token != null && !token.isBlank()) {
                    httpHeaders.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
                }
            }
        }
    }
}
