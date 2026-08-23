package com.cl2.integration.gateway.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;

class TrustedIssuersAuthenticationManagerResolverTest {

    private static final String ISSUER_MICROSERVICIOS = "https://issuer.example.test/realms/microservicios";
    private static final String ISSUER_APPS = "https://issuer.example.test/realms/Apps";
    private static final UUID TENANT_ID = UUID.fromString("24a4a27e-98ff-4d55-882e-4b3741e4dd3e");

    private static JwtEncoder microserviciosEncoder;
    private static JwtEncoder appsEncoder;
    private static WebTestClient client;

    @BeforeAll
    static void setUp() throws Exception {
        KeyPair microserviciosKeys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        KeyPair appsKeys = KeyPairGenerator.getInstance("RSA").generateKeyPair();

        microserviciosEncoder = encoder(microserviciosKeys, "microservicios-key");
        appsEncoder = encoder(appsKeys, "apps-key");

        NimbusReactiveJwtDecoder microserviciosDecoder =
                NimbusReactiveJwtDecoder.withPublicKey((RSAPublicKey) microserviciosKeys.getPublic()).build();
        microserviciosDecoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER_MICROSERVICIOS));

        NimbusReactiveJwtDecoder appsDecoder =
                NimbusReactiveJwtDecoder.withPublicKey((RSAPublicKey) appsKeys.getPublic()).build();
        appsDecoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER_APPS));

        var resolver = TrustedIssuersAuthenticationManagerResolver.from(Map.of(
                ISSUER_MICROSERVICIOS, microserviciosDecoder,
                ISSUER_APPS, appsDecoder));

        SecurityWebFilterChain filterChain = ServerHttpSecurity.http()
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges.anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.authenticationManagerResolver(resolver))
                .build();

        client = WebTestClient
                .bindToRouterFunction(RouterFunctions.route(GET("/**"), request -> ServerResponse.ok().build()))
                .webFilter(new WebFilterChainProxy(filterChain))
                .build();
    }

    @Test
    void acceptsTokenSignedByMicroserviciosRealm() {
        String token = token(microserviciosEncoder, ISSUER_MICROSERVICIOS, "microservicios-key");

        client.get().uri("/api/v1/integration-profiles")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void acceptsTokenSignedByAppsRealm() {
        String token = token(appsEncoder, ISSUER_APPS, "apps-key");

        client.get().uri("/api/v1/integration-profiles")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void rejectsTokenFromUntrustedIssuer() {
        String token = token(microserviciosEncoder, "https://untrusted.example.test/realms/other", "microservicios-key");

        client.get().uri("/api/v1/integration-profiles")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    private static String token(JwtEncoder encoder, String issuer, String keyId) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject("test-user")
                .claim("tenant_id", TENANT_ID.toString())
                .issuedAt(Instant.parse("2026-08-15T00:00:00Z"))
                .expiresAt(Instant.parse("2099-01-01T00:00:00Z"))
                .build();
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build(), claims)).getTokenValue();
    }

    private static JwtEncoder encoder(KeyPair keyPair, String keyId) {
        RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID(keyId)
                .build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(rsaKey)));
    }
}
