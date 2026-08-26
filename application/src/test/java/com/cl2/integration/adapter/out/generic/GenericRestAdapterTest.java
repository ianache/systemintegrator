package com.cl2.integration.adapter.out.generic;

import com.cl2.integration.adapter.out.generic.model.ExtractionConfig;
import com.cl2.integration.adapter.out.generic.security.OAuth2TokenCacheManager;
import com.cl2.integration.adapter.out.http.HttpOutboundClient;
import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.domain.model.IntegrationProtocol;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import com.cl2.integration.infrastructure.metrics.IntegrationMetrics;
import com.cl2.integration.integration.security.ResolvedSecret;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@SpringJUnitConfig(GenericRestAdapterTest.TestContextConfiguration.class)
class GenericRestAdapterTest {

    private static final String ADAPTER_CLASS_NAME = "com.cl2.integration.adapter.out.generic.GenericRestAdapter";
    private static final Instant WATERMARK = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant NON_CONTRACT_CLOCK_TIME = Instant.parse("2026-02-01T00:00:00Z");
    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PROFILE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private OAuth2TokenCacheManager tokenCacheManager;

    private WireMockServer wireMockServer;
    private String baseUrl;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
        baseUrl = wireMockServer.baseUrl();
        reset(tokenCacheManager);
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null && wireMockServer.isRunning()) {
            wireMockServer.stop();
        }
    }

    @Test
    @DisplayName("Should GET customers with watermark substitution, limit, and JSONPath extraction")
    void shouldGetCustomersWithWatermarkSubstitutionLimitAndJsonPathExtraction() {
        IntegrationProfile profile = restProfile();
        ExtractionConfig config = extractionConfig(
                "/api/customers",
                Map.of(),
                Map.of("updatedSince", ":lastSyncWithBuffer", "limit", "100")
        );
        ResolvedSecret secret = ResolvedSecret.bearer("vault:secret/data/customers", "test-token");

        wireMockServer.stubFor(get(urlPathEqualTo("/api/customers"))
                .withQueryParam("updatedSince", equalTo("2026-01-01T00:00:00Z"))
                .withQueryParam("limit", equalTo("100"))
                .willReturn(okJson("{\"items\":[{\"customerId\":\"c-1\"}]}")));

        List<Map<String, Object>> result = extract(profile, config, secret, WATERMARK);

        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/api/customers"))
                .withQueryParam("updatedSince", equalTo("2026-01-01T00:00:00Z"))
                .withQueryParam("limit", equalTo("100")));
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("customerId", "c-1");
    }

    @Test
    @DisplayName("Should send Basic authentication in Authorization header")
    void shouldSendBasicAuthenticationHeader() {
        IntegrationProfile profile = restProfile();
        ExtractionConfig config = extractionConfig(
                "/api/customers/basic",
                Map.of(),
                Map.of("updatedSince", ":lastSyncWithBuffer", "limit", "100")
        );
        ResolvedSecret secret = ResolvedSecret.basic("vault:secret/data/basic", "user-1", "pass-1");
        String expectedAuth = "Basic " + Base64.getEncoder().encodeToString("user-1:pass-1".getBytes(StandardCharsets.UTF_8));

        wireMockServer.stubFor(get(urlPathEqualTo("/api/customers/basic"))
                .withQueryParam("updatedSince", equalTo("2026-01-01T00:00:00Z"))
                .withQueryParam("limit", equalTo("100"))
                .withHeader("Authorization", equalTo(expectedAuth))
                .willReturn(okJson("{\"items\":[{\"customerId\":\"c-1\"}]}")));

        List<Map<String, Object>> result = extract(profile, config, secret, WATERMARK);

        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/api/customers/basic"))
                .withQueryParam("updatedSince", equalTo("2026-01-01T00:00:00Z"))
                .withQueryParam("limit", equalTo("100"))
                .withHeader("Authorization", equalTo(expectedAuth)));
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("customerId", "c-1");
    }

    @Test
    @DisplayName("Should send Bearer token in Authorization header")
    void shouldSendBearerAuthenticationHeader() {
        IntegrationProfile profile = restProfile();
        ExtractionConfig config = extractionConfig(
                "/api/customers/bearer",
                Map.of(),
                Map.of("updatedSince", ":lastSyncWithBuffer", "limit", "100")
        );
        ResolvedSecret secret = ResolvedSecret.bearer("vault:secret/data/bearer", "test-token");

        wireMockServer.stubFor(get(urlPathEqualTo("/api/customers/bearer"))
                .withQueryParam("updatedSince", equalTo("2026-01-01T00:00:00Z"))
                .withQueryParam("limit", equalTo("100"))
                .withHeader("Authorization", equalTo("Bearer test-token"))
                .willReturn(okJson("{\"items\":[{\"customerId\":\"c-1\"}]}")));

        List<Map<String, Object>> result = extract(profile, config, secret, WATERMARK);

        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/api/customers/bearer"))
                .withQueryParam("updatedSince", equalTo("2026-01-01T00:00:00Z"))
                .withQueryParam("limit", equalTo("100"))
                .withHeader("Authorization", equalTo("Bearer test-token")));
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("customerId", "c-1");
    }

    @Test
    @DisplayName("Should send API Key header")
    void shouldSendApiKeyHeader() {
        IntegrationProfile profile = restProfile();
        ExtractionConfig config = extractionConfig(
                "/api/customers/api-key",
                Map.of(),
                Map.of("updatedSince", ":lastSyncWithBuffer", "limit", "100")
        );
        ResolvedSecret secret = ResolvedSecret.apiKey("vault:secret/data/api-key", "test-api-key");

        wireMockServer.stubFor(get(urlPathEqualTo("/api/customers/api-key"))
                .withQueryParam("updatedSince", equalTo("2026-01-01T00:00:00Z"))
                .withQueryParam("limit", equalTo("100"))
                .withHeader("X-API-Key", equalTo("test-api-key"))
                .willReturn(okJson("{\"items\":[{\"customerId\":\"c-1\"}]}")));

        List<Map<String, Object>> result = extract(profile, config, secret, WATERMARK);

        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/api/customers/api-key"))
                .withQueryParam("updatedSince", equalTo("2026-01-01T00:00:00Z"))
                .withQueryParam("limit", equalTo("100"))
                .withHeader("X-API-Key", equalTo("test-api-key")));
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("customerId", "c-1");
    }

    @Test
    @DisplayName("Should use OAuth2 token cache boundary and forward configured headers")
    void shouldUseOAuth2TokenCacheBoundaryAndForwardConfiguredHeaders() {
        IntegrationProfile profile = restProfile();
        ExtractionConfig config = extractionConfig(
                "/api/customers/oauth2",
                Map.of("X-Client-App", "inbound-sync", "X-Source-System", "crm"),
                Map.of("updatedSince", ":lastSyncWithBuffer", "limit", "100")
        );
        ResolvedSecret secret = ResolvedSecret.oauth2(
                "vault:secret/data/oauth2",
                "https://auth.example.com/token",
                "client-123",
                "secret-123",
                "read:customers"
        );

        when(tokenCacheManager.getAccessToken(
                eq(TENANT_ID.toString()),
                eq("https://auth.example.com/token"),
                eq("client-123"),
                eq("secret-123"),
                eq("read:customers")
        )).thenReturn("oauth-access-token");

        wireMockServer.stubFor(get(urlPathEqualTo("/api/customers/oauth2"))
                .withQueryParam("updatedSince", equalTo("2026-01-01T00:00:00Z"))
                .withQueryParam("limit", equalTo("100"))
                .withHeader("Authorization", equalTo("Bearer oauth-access-token"))
                .withHeader("X-Client-App", equalTo("inbound-sync"))
                .withHeader("X-Source-System", equalTo("crm"))
                .willReturn(okJson("{\"items\":[{\"customerId\":\"c-1\"}]}")));

        List<Map<String, Object>> result = extract(profile, config, secret, WATERMARK);

        verify(tokenCacheManager).getAccessToken(
                eq(TENANT_ID.toString()),
                eq("https://auth.example.com/token"),
                eq("client-123"),
                eq("secret-123"),
                eq("read:customers")
        );
        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/api/customers/oauth2"))
                .withQueryParam("updatedSince", equalTo("2026-01-01T00:00:00Z"))
                .withQueryParam("limit", equalTo("100"))
                .withHeader("Authorization", equalTo("Bearer oauth-access-token"))
                .withHeader("X-Client-App", equalTo("inbound-sync"))
                .withHeader("X-Source-System", equalTo("crm")));
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("customerId", "c-1");
    }

    @Test
    @DisplayName("Should honor POST extraction method")
    void shouldHonorPostExtractionMethod() {
        IntegrationProfile profile = restProfile();
        ExtractionConfig config = new ExtractionConfig(
                null,
                "lastSyncWithBuffer",
                "customerId",
                100,
                "POST",
                "/api/customers/post",
                Map.of("updatedSince", ":lastSyncWithBuffer", "limit", "100"),
                Map.of(),
                "$.items[*]",
                "ISO_8601",
                "customerId",
                "updatedAt"
        );
        ResolvedSecret secret = ResolvedSecret.bearer("vault:secret/data/post", "test-token");

        wireMockServer.stubFor(post(urlPathEqualTo("/api/customers/post"))
                .withQueryParam("updatedSince", equalTo("2026-01-01T00:00:00Z"))
                .withQueryParam("limit", equalTo("100"))
                .withHeader("Authorization", equalTo("Bearer test-token"))
                .willReturn(okJson("{\"items\":[{\"customerId\":\"c-2\"}]}")));

        List<Map<String, Object>> result = extract(profile, config, secret, WATERMARK);

        wireMockServer.verify(1, postRequestedFor(urlPathEqualTo("/api/customers/post"))
                .withQueryParam("updatedSince", equalTo("2026-01-01T00:00:00Z"))
                .withQueryParam("limit", equalTo("100"))
                .withHeader("Authorization", equalTo("Bearer test-token")));
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("customerId", "c-2");
    }

    @Test
    @DisplayName("Should reject unsupported watermark formats explicitly")
    void shouldRejectUnsupportedWatermarkFormatsExplicitly() {
        IntegrationProfile profile = restProfile();
        ExtractionConfig config = new ExtractionConfig(
                null,
                "lastSyncWithBuffer",
                "customerId",
                100,
                "GET",
                "/api/customers",
                Map.of("updatedSince", ":lastSyncWithBuffer"),
                Map.of(),
                "$.items[*]",
                "UNIX_EPOCH",
                "customerId",
                "updatedAt"
        );

        assertThatThrownBy(() -> extract(profile, config, ResolvedSecret.bearer("vault:secret/data/customers", "test-token"), WATERMARK))
                .isInstanceOf(IllegalStateException.class)
                .rootCause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported watermark format");
    }

    @Test
    @DisplayName("Should reject endpoints containing userinfo")
    void shouldRejectEndpointsContainingUserinfo() {
        IntegrationProfile profile = restProfile(baseUrl.replace("://", "://user:pass@"));
        ExtractionConfig config = extractionConfig(
                "/api/customers",
                Map.of(),
                Map.of("updatedSince", ":lastSyncWithBuffer")
        );

        assertThatThrownBy(() -> extract(profile, config, ResolvedSecret.bearer("vault:secret/data/customers", "test-token"), WATERMARK))
                .isInstanceOf(IllegalStateException.class)
                .rootCause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userinfo");
    }

    private List<Map<String, Object>> extract(
            IntegrationProfile profile,
            ExtractionConfig config,
            ResolvedSecret secret,
            Instant watermarkTimestamp
    ) {
        Object adapter = resolveAdapterBean();
        try {
            Object value = adapter.getClass()
                    .getMethod("extract", IntegrationProfile.class, ExtractionConfig.class, ResolvedSecret.class, Instant.class)
                    .invoke(adapter, profile, config, secret, watermarkTimestamp);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) value;
            return rows;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Unable to invoke extract on " + ADAPTER_CLASS_NAME, ex);
        }
    }

    private Object resolveAdapterBean() {
        try {
            Class<?> adapterClass = Class.forName(ADAPTER_CLASS_NAME);
            return applicationContext.getBean(adapterClass);
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("Production adapter class is absent: " + ADAPTER_CLASS_NAME, ex);
        } catch (NoSuchBeanDefinitionException ex) {
            throw new IllegalStateException("Production adapter bean is absent: " + ADAPTER_CLASS_NAME, ex);
        }
    }

    private IntegrationProfile restProfile() {
        return restProfile(baseUrl);
    }

    private IntegrationProfile restProfile(String endpoint) {
        IntegrationProfileConfiguration configuration = new IntegrationProfileConfiguration(
                IntegrationProtocol.REST,
                "generic-rest",
                "generic-rest",
                endpoint,
                "vault:secret/data/rest",
                null,
                null,
                null,
                null,
                null,
                null
        );

        return IntegrationProfile.create(
                PROFILE_ID,
                TENANT_ID,
                "customers",
                "external-api",
                SyncDirection.INBOUND,
                SourceOfTruth.EXTERNAL,
                configuration
        );
    }

    private ExtractionConfig extractionConfig(String path, Map<String, String> headers, Map<String, String> queryParams) {
        return new ExtractionConfig(
                null,
                "lastSyncWithBuffer",
                "customerId",
                100,
                "GET",
                path,
                queryParams,
                headers,
                "$.items[*]",
                "ISO_8601",
                "customerId",
                "updatedAt"
        );
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = {GenericJdbcAdapter.class, HttpOutboundClient.class})
    static class TestContextConfiguration {

        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        Clock clock() {
            return Clock.fixed(NON_CONTRACT_CLOCK_TIME, ZoneOffset.UTC);
        }

        @Bean
        OAuth2TokenCacheManager tokenCacheManager() {
            return mock(OAuth2TokenCacheManager.class);
        }

        @Bean
        IntegrationMetrics integrationMetrics() {
            return mock(IntegrationMetrics.class);
        }
    }
}
