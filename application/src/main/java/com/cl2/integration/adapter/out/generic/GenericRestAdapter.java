package com.cl2.integration.adapter.out.generic;

import com.cl2.integration.adapter.out.generic.model.ExtractionConfig;
import com.cl2.integration.adapter.out.generic.security.OAuth2TokenCacheManager;
import com.cl2.integration.adapter.out.http.SensitiveDataRedactor;
import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.integration.security.AuthType;
import com.cl2.integration.integration.security.ResolvedSecret;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class GenericRestAdapter {

    private static final Logger log = LoggerFactory.getLogger(GenericRestAdapter.class);
    private static final ParameterizedTypeReference<String> STRING_RESPONSE = new ParameterizedTypeReference<>() {};

    private final RestClient restClient;
    private final OAuth2TokenCacheManager tokenCacheManager;
    private final ObjectMapper objectMapper;

    public GenericRestAdapter(
            RestClient.Builder restClientBuilder,
            OAuth2TokenCacheManager tokenCacheManager,
            ObjectMapper objectMapper
    ) {
        this.restClient = restClientBuilder.build();
        this.tokenCacheManager = tokenCacheManager;
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> extract(
            IntegrationProfile profile,
            ExtractionConfig config,
            ResolvedSecret secret,
            Instant watermarkTimestamp
    ) {
        HttpMethod method = resolveMethod(config.method());
        URI requestUri = buildRequestUri(profile, config, watermarkTimestamp);
        HttpHeaders headers = buildHeaders(profile, config, secret);

        if (log.isDebugEnabled()) {
            Map<String, String> loggableHeaders = new LinkedHashMap<>();
            headers.forEach((name, values) -> loggableHeaders.put(name, String.join(", ", values)));
            log.debug("Generic REST request -> {} {}\nHeaders: {}",
                    method,
                    requestUri,
                    SensitiveDataRedactor.redactHeaders(loggableHeaders));
        }

        String body;
        try {
            body = restClient.method(method)
                    .uri(requestUri)
                    .headers(httpHeaders -> httpHeaders.putAll(headers))
                    .retrieve()
                    .body(STRING_RESPONSE);
        } catch (RestClientResponseException ex) {
            throw new IllegalArgumentException(
                    "Generic REST extraction failed with HTTP " + ex.getStatusCode().value() + " for " + requestUri.getPath()
            );
        }

        return extractRows(body, config.responseJsonPath());
    }

    private URI buildRequestUri(IntegrationProfile profile, ExtractionConfig config, Instant watermarkTimestamp) {
        IntegrationProfileConfiguration profileConfiguration = requireConfiguration(profile);
        URI endpoint = validateBaseEndpoint(profileConfiguration.endpoint());
        URI resolved = resolvePath(endpoint, config.path());
        UriComponentsBuilder builder = UriComponentsBuilder.fromUri(resolved);

        Map<String, String> queryParams = config.queryParams() != null ? new LinkedHashMap<>(config.queryParams()) : Map.of();
        for (Map.Entry<String, String> entry : queryParams.entrySet()) {
            builder.queryParam(
                    entry.getKey(),
                    resolveQueryValue(entry.getValue(), config.watermarkParam(), config.watermarkFormat(), watermarkTimestamp)
            );
        }

        return builder.build(true).toUri();
    }

    private HttpHeaders buildHeaders(IntegrationProfile profile, ExtractionConfig config, ResolvedSecret secret) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        if (config.headers() != null) {
            config.headers().forEach(headers::set);
        }
        if (secret != null && secret.headers() != null) {
            secret.headers().forEach(headers::set);
        }

        applyAuthentication(headers, profile, secret);
        return headers;
    }

    private void applyAuthentication(HttpHeaders headers, IntegrationProfile profile, ResolvedSecret secret) {
        if (secret == null) {
            return;
        }

        AuthType authType = secret.authType();
        if (authType == AuthType.BASIC || (authType == null && secret.username() != null)) {
            applyBasicAuth(headers, secret.username(), secret.password());
            return;
        }
        if (authType == AuthType.BEARER || (authType == null && secret.token() != null && !secret.token().isBlank())) {
            if (secret.token() != null && !secret.token().isBlank()) {
                headers.setBearerAuth(secret.token());
            }
            return;
        }
        if (authType == AuthType.API_KEY || (authType == null && secret.apiKey() != null && !secret.apiKey().isBlank())) {
            if (!headers.containsKey("X-API-Key") && !headers.containsKey("x-api-key") && secret.apiKey() != null && !secret.apiKey().isBlank()) {
                headers.set("X-API-Key", secret.apiKey());
            }
            headers.remove(HttpHeaders.AUTHORIZATION);
            return;
        }
        if (authType == AuthType.OAUTH2_CLIENT_CREDENTIALS) {
            String token = secret.token();
            if (token == null || token.isBlank()) {
                token = tokenCacheManager.getAccessToken(
                        profile.tenantId().toString(),
                        secret.tokenUrl(),
                        secret.clientId(),
                        secret.clientSecret(),
                        secret.scope()
                );
            }
            if (token != null && !token.isBlank()) {
                headers.setBearerAuth(token);
            }
        }
    }

    private void applyBasicAuth(HttpHeaders headers, String username, String password) {
        if (username == null || password == null) {
            return;
        }
        String credentials = username + ":" + password;
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        headers.set(HttpHeaders.AUTHORIZATION, "Basic " + encoded);
    }

    private List<Map<String, Object>> extractRows(String body, String jsonPath) {
        if (body == null || body.isBlank()) {
            return List.of();
        }

        String normalizedJsonPath = jsonPath != null && !jsonPath.isBlank() ? jsonPath : "$";
        Object parsedBody = parseResponseBody(body);
        Object extracted = readJsonPath(parsedBody, normalizedJsonPath);

        if (extracted instanceof List<?> items) {
            if (items.isEmpty()) {
                throw new IllegalArgumentException("JSONPath did not match any records: " + normalizedJsonPath);
            }
            List<Map<String, Object>> rows = new ArrayList<>(items.size());
            for (Object item : items) {
                rows.add(convertToMap(item, normalizedJsonPath));
            }
            return rows;
        }

        return List.of(convertToMap(extracted, normalizedJsonPath));
    }

    private Object parseResponseBody(String body) {
        try {
            return objectMapper.readValue(body, Object.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Malformed JSON response");
        }
    }

    private Object readJsonPath(Object body, String jsonPath) {
        try {
            JsonPath.compile(jsonPath);
        } catch (InvalidPathException ex) {
            throw new IllegalArgumentException("Invalid JSONPath: " + jsonPath);
        }

        try {
            return JsonPath.read(body, jsonPath);
        } catch (PathNotFoundException ex) {
            throw new IllegalArgumentException("JSONPath did not match any records: " + jsonPath);
        }
    }

    private Map<String, Object> convertToMap(Object value, String jsonPath) {
        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> copy = new LinkedHashMap<>();
            mapValue.forEach((key, itemValue) -> copy.put(String.valueOf(key), itemValue));
            return copy;
        }

        throw new IllegalArgumentException("JSONPath must resolve to an object or array of objects: " + jsonPath);
    }

    private IntegrationProfileConfiguration requireConfiguration(IntegrationProfile profile) {
        if (profile == null || profile.configuration() == null) {
            throw new IllegalArgumentException("Integration profile configuration is required");
        }
        return profile.configuration();
    }

    private URI validateBaseEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("Profile endpoint must not be blank");
        }

        URI uri = URI.create(endpoint);
        String scheme = uri.getScheme();
        if (!uri.isAbsolute() || scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("Profile endpoint must be an absolute http or https URL");
        }
        if (uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
            throw new IllegalArgumentException("Profile endpoint must not contain userinfo credentials");
        }
        return uri;
    }

    private URI resolvePath(URI endpoint, String path) {
        if (path == null || path.isBlank()) {
            return endpoint;
        }
        return endpoint.resolve(path);
    }

    private String resolveQueryValue(String value, String watermarkParam, String watermarkFormat, Instant watermarkTimestamp) {
        if (value == null) {
            return null;
        }

        String expectedPlaceholder = ":" + (watermarkParam == null || watermarkParam.isBlank() ? "lastSyncWithBuffer" : watermarkParam);
        if (value.equals(expectedPlaceholder) || value.equals(":lastSyncWithBuffer")) {
            return formatWatermark(watermarkTimestamp, watermarkFormat);
        }
        return value;
    }

    private HttpMethod resolveMethod(String configuredMethod) {
        String normalizedMethod = configuredMethod == null || configuredMethod.isBlank()
                ? "GET"
                : configuredMethod.trim().toUpperCase();

        return switch (normalizedMethod) {
            case "GET" -> HttpMethod.GET;
            case "POST" -> HttpMethod.POST;
            case "PUT" -> HttpMethod.PUT;
            case "PATCH" -> HttpMethod.PATCH;
            case "DELETE", "TRACE", "CONNECT", "HEAD", "OPTIONS" ->
                throw new IllegalArgumentException("Unsupported extraction HTTP method: " + normalizedMethod);
            default -> throw new IllegalArgumentException("Unsupported extraction HTTP method: " + normalizedMethod);
        };
    }

    private String formatWatermark(Instant watermarkTimestamp, String watermarkFormat) {
        if (watermarkTimestamp == null) {
            throw new IllegalArgumentException("Watermark timestamp is required");
        }

        String normalizedFormat = watermarkFormat == null || watermarkFormat.isBlank()
                ? "ISO_8601"
                : watermarkFormat.trim().toUpperCase();

        if (!"ISO_8601".equals(normalizedFormat)) {
            throw new IllegalArgumentException("Unsupported watermark format: " + normalizedFormat);
        }

        return watermarkTimestamp.toString();
    }
}
