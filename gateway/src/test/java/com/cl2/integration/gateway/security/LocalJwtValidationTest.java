package com.cl2.integration.gateway.security;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalJwtValidationTest {

    private static final String ISSUER = "https://issuer.example.test/realms/integration";
    private static final Instant ISSUED_AT = Instant.parse("2026-08-15T00:00:00Z");
    private static final UUID TENANT_ID = UUID.fromString("24a4a27e-98ff-4d55-882e-4b3741e4dd3e");

    private static JwtEncoder encoder;
    private static ReactiveJwtDecoder decoder;

    @BeforeAll
    static void setUpKeys() throws Exception {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        KeyPair keyPair = keyPairGenerator.generateKeyPair();
        RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID("local-test-key")
                .build();
        encoder = new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new com.nimbusds.jose.jwk.JWKSet(rsaKey)));
        NimbusReactiveJwtDecoder nimbusDecoder = NimbusReactiveJwtDecoder.withPublicKey((RSAPublicKey) keyPair.getPublic()).build();
        nimbusDecoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
        decoder = nimbusDecoder;
    }

    @Test
    void acceptsLocallySignedTokenWithExpectedIssuerAndFutureExpiration() {
        String token = token(ISSUER, ISSUED_AT, Instant.parse("2099-01-01T00:00:00Z"));

        Jwt jwt = decoder.decode(token).block();

        assertEquals(ISSUER, jwt.getIssuer().toString());
        assertEquals(TENANT_ID.toString(), jwt.getClaimAsString("tenant_id"));
    }

    @Test
    void rejectsTokenWithInvalidSignature() {
        String signedToken = token(ISSUER, ISSUED_AT, Instant.parse("2099-01-01T00:00:00Z"));
        int signatureStart = signedToken.lastIndexOf('.') + 1;
        char originalSignatureCharacter = signedToken.charAt(signatureStart);
        char tamperedSignatureCharacter = originalSignatureCharacter == 'A' ? 'B' : 'A';
        String tamperedToken = signedToken.substring(0, signatureStart)
                + tamperedSignatureCharacter
                + signedToken.substring(signatureStart + 1);

        assertThrows(JwtException.class, () -> decoder.decode(tamperedToken).block());
    }

    @Test
    void rejectsTokenWithUnexpectedIssuer() {
        String token = token("https://unexpected-issuer.example.test/realms/integration", ISSUED_AT,
                Instant.parse("2099-01-01T00:00:00Z"));

        assertThrows(JwtException.class, () -> decoder.decode(token).block());
    }

    @Test
    void rejectsExpiredToken() {
        Instant expiredIssuedAt = Instant.parse("2020-01-01T00:00:00Z");
        String token = token(ISSUER, expiredIssuedAt, expiredIssuedAt.plusSeconds(1));

        assertThrows(JwtException.class, () -> decoder.decode(token).block());
    }

    private String token(String issuer, Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject("test-user")
                .claim("tenant_id", TENANT_ID.toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();
        return encoder.encode(JwtEncoderParameters.from(
                org.springframework.security.oauth2.jwt.JwsHeader.with(SignatureAlgorithm.RS256)
                        .keyId("local-test-key")
                        .build(), claims)).getTokenValue();
    }
}
