package com.cl2.integration.adapter.out.http;

import com.cl2.integration.adapter.out.generic.security.OAuth2TokenCacheManager;
import com.cl2.integration.integration.security.AuthType;
import com.cl2.integration.integration.security.ResolvedSecret;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HttpOutboundClientTest {

    private WireMockServer wireMockServer;
    private HttpOutboundClient client;
    private String baseUrl;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
        baseUrl = "http://localhost:" + wireMockServer.port();
        client = new HttpOutboundClient(RestClient.builder());
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null && wireMockServer.isRunning()) {
            wireMockServer.stop();
        }
    }

    @Test
    @DisplayName("Should send POST request with Bearer token authentication")
    void shouldSendPostRequestWithBearerToken() {
        String endpoint = baseUrl + "/api/v1/events";
        String payload = "{\"event\":\"customer.created\",\"id\":\"12345\"}";
        ResolvedSecret secret = ResolvedSecret.bearer("vault:secret/data/bearer", "token-abc-123");

        wireMockServer.stubFor(post(urlEqualTo("/api/v1/events"))
                .withHeader("Content-Type", containing("application/json"))
                .withHeader("Authorization", equalTo("Bearer token-abc-123"))
                .withRequestBody(equalToJson(payload))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"success\"}")));

        client.send(endpoint, secret, payload);

        wireMockServer.verify(postRequestedFor(urlEqualTo("/api/v1/events"))
                .withHeader("Content-Type", containing("application/json"))
                .withHeader("Authorization", equalTo("Bearer token-abc-123"))
                .withRequestBody(equalToJson(payload)));
    }

    @Test
    @DisplayName("Should send POST request with Basic authentication")
    void shouldSendPostRequestWithBasicAuth() {
        String endpoint = baseUrl + "/api/v1/orders";
        String payload = "{\"orderId\":\"order-99\"}";
        ResolvedSecret secret = ResolvedSecret.basic("vault:secret/data/basic", "user1", "pass1");

        String expectedAuth = "Basic " + Base64.getEncoder().encodeToString("user1:pass1".getBytes(StandardCharsets.UTF_8));

        wireMockServer.stubFor(post(urlEqualTo("/api/v1/orders"))
                .withHeader("Content-Type", containing("application/json"))
                .withHeader("Authorization", equalTo(expectedAuth))
                .withRequestBody(equalToJson(payload))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"created\"}")));

        client.send(endpoint, secret, payload);

        wireMockServer.verify(postRequestedFor(urlEqualTo("/api/v1/orders"))
                .withHeader("Content-Type", containing("application/json"))
                .withHeader("Authorization", equalTo(expectedAuth))
                .withRequestBody(equalToJson(payload)));
    }

    @Test
    @DisplayName("Should send POST request with API Key header")
    void shouldSendPostRequestWithApiKey() {
        String endpoint = baseUrl + "/api/v1/webhooks";
        String payload = "{\"webhook\":\"ping\"}";
        ResolvedSecret secret = ResolvedSecret.apiKey("vault:secret/data/api-key", "my-secret-api-key");

        wireMockServer.stubFor(post(urlEqualTo("/api/v1/webhooks"))
                .withHeader("Content-Type", containing("application/json"))
                .withHeader("X-API-Key", equalTo("my-secret-api-key"))
                .withRequestBody(equalToJson(payload))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"received\"}")));

        client.send(endpoint, secret, payload);

        wireMockServer.verify(postRequestedFor(urlEqualTo("/api/v1/webhooks"))
                .withHeader("Content-Type", containing("application/json"))
                .withHeader("X-API-Key", equalTo("my-secret-api-key"))
                .withRequestBody(equalToJson(payload)));
    }

    @Test
    @DisplayName("Should send POST request with custom headers from secret")
    void shouldSendPostRequestWithCustomHeaders() {
        String endpoint = baseUrl + "/api/v1/custom";
        String payload = "{\"custom\":true}";
        ResolvedSecret secret = new ResolvedSecret(
                "vault:secret/data/custom",
                AuthType.CUSTOM,
                null,
                null,
                null,
                null,
                Map.of("X-Custom-Tenant", "tenant-42", "X-Partner-Id", "partner-99")
        );

        wireMockServer.stubFor(post(urlEqualTo("/api/v1/custom"))
                .withHeader("Content-Type", containing("application/json"))
                .withHeader("X-Custom-Tenant", equalTo("tenant-42"))
                .withHeader("X-Partner-Id", equalTo("partner-99"))
                .withRequestBody(equalToJson(payload))
                .willReturn(aResponse().withStatus(204)));

        client.send(endpoint, secret, payload);

        wireMockServer.verify(postRequestedFor(urlEqualTo("/api/v1/custom"))
                .withHeader("Content-Type", containing("application/json"))
                .withHeader("X-Custom-Tenant", equalTo("tenant-42"))
                .withHeader("X-Partner-Id", equalTo("partner-99")));
    }

    @Test
    @DisplayName("Should send POST request without auth headers when secret is null")
    void shouldSendPostRequestWithoutAuthWhenSecretIsNull() {
        String endpoint = baseUrl + "/api/v1/public";
        String payload = "{\"public\":true}";

        wireMockServer.stubFor(post(urlEqualTo("/api/v1/public"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(equalToJson(payload))
                .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));

        client.send(endpoint, null, payload);

        wireMockServer.verify(postRequestedFor(urlEqualTo("/api/v1/public"))
                .withoutHeader("Authorization")
                .withoutHeader("X-API-Key")
                .withRequestBody(equalToJson(payload)));
    }

    @Test
    @DisplayName("Should throw HttpOutboundException on 500 Internal Server Error")
    void shouldThrowHttpOutboundExceptionOn500InternalServerError() {
        String endpoint = baseUrl + "/api/v1/fail500";
        String payload = "{\"event\":\"fail\"}";

        wireMockServer.stubFor(post(urlEqualTo("/api/v1/fail500"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"Internal Server Error\"}")));

        assertThatThrownBy(() -> client.send(endpoint, null, payload))
                .isInstanceOf(HttpOutboundException.class)
                .satisfies(ex -> {
                    HttpOutboundException hoe = (HttpOutboundException) ex;
                    assertThat(hoe.getStatusCode()).isEqualTo(500);
                    assertThat(hoe.getResponseBody()).contains("Internal Server Error");
                });
    }

    @Test
    @DisplayName("Should throw HttpOutboundException on 401 Unauthorized")
    void shouldThrowHttpOutboundExceptionOn401Unauthorized() {
        String endpoint = baseUrl + "/api/v1/unauthorized";
        String payload = "{\"event\":\"secure\"}";
        ResolvedSecret secret = ResolvedSecret.bearer("vault:secret/data/bearer", "invalid-token");

        wireMockServer.stubFor(post(urlEqualTo("/api/v1/unauthorized"))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"Unauthorized\"}")));

        assertThatThrownBy(() -> client.send(endpoint, secret, payload))
                .isInstanceOf(HttpOutboundException.class)
                .satisfies(ex -> {
                    HttpOutboundException hoe = (HttpOutboundException) ex;
                    assertThat(hoe.getStatusCode()).isEqualTo(401);
                });
    }

    @Test
    @DisplayName("Should throw HttpOutboundException on 404 Not Found")
    void shouldThrowHttpOutboundExceptionOn404NotFound() {
        String endpoint = baseUrl + "/api/v1/nonexistent";
        String payload = "{\"event\":\"test\"}";

        wireMockServer.stubFor(post(urlEqualTo("/api/v1/nonexistent"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"Not Found\"}")));

        assertThatThrownBy(() -> client.send(endpoint, null, payload))
                .isInstanceOf(HttpOutboundException.class)
                .satisfies(ex -> {
                    HttpOutboundException hoe = (HttpOutboundException) ex;
                    assertThat(hoe.getStatusCode()).isEqualTo(404);
                });
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when endpoint is null or blank")
    void shouldThrowExceptionWhenEndpointIsInvalid() {
        assertThatThrownBy(() -> client.send(null, null, "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Endpoint URL cannot be null or blank");

        assertThatThrownBy(() -> client.send("   ", null, "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Endpoint URL cannot be null or blank");
    }

    @Test
    @DisplayName("Should fetch token via OAuth2TokenCacheManager and send POST request with Bearer token for OAUTH2_CLIENT_CREDENTIALS")
    void shouldFetchTokenViaOAuth2TokenCacheManagerAndSendPostRequest() {
        String endpoint = baseUrl + "/api/v1/units/brands";
        String payload = "{\"brand\":\"Toyota\"}";
        ResolvedSecret secret = ResolvedSecret.oauth2(
                "vault:secret/data/keycloak",
                "http://auth.keycloak.com/token",
                "client-1",
                "secret-1",
                "units.write"
        );

        OAuth2TokenCacheManager tokenCacheManager = mock(OAuth2TokenCacheManager.class);
        UUID tenantUuid = UUID.randomUUID();
        when(tokenCacheManager.getAccessToken(
                tenantUuid.toString(),
                "http://auth.keycloak.com/token",
                "client-1",
                "secret-1",
                "units.write"
        )).thenReturn("jwt-token-abc");

        HttpOutboundClient oauthClient = new HttpOutboundClient(RestClient.builder(), tokenCacheManager);

        wireMockServer.stubFor(post(urlEqualTo("/api/v1/units/brands"))
                .withHeader("Content-Type", containing("application/json"))
                .withHeader("Authorization", equalTo("Bearer jwt-token-abc"))
                .withRequestBody(equalToJson(payload))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"success\"}")));

        oauthClient.send(endpoint, secret, payload, tenantUuid);

        wireMockServer.verify(postRequestedFor(urlEqualTo("/api/v1/units/brands"))
                .withHeader("Content-Type", containing("application/json"))
                .withHeader("Authorization", equalTo("Bearer jwt-token-abc"))
                .withRequestBody(equalToJson(payload)));
    }
}
