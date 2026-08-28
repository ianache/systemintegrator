package com.cl2.integration.integration.credential;

import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.port.IntegrationProfileRepository;
import com.cl2.integration.integration.security.ResolvedSecret;
import com.cl2.integration.integration.security.SecretResolver;
import com.cl2.integration.integration.security.VaultProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class CredentialCatalogService {

    private static final Logger log = LoggerFactory.getLogger(CredentialCatalogService.class);

    static final String STATE_VIGENTE = "VIGENTE";
    static final String STATE_SIN_VERIFICAR = "SIN_VERIFICAR";

    private final IntegrationProfileRepository profileRepository;
    private final SecretResolver secretResolver;
    private final VaultProperties vaultProperties;
    private final RestClient restClient;

    public CredentialCatalogService(
            IntegrationProfileRepository profileRepository,
            SecretResolver secretResolver,
            VaultProperties vaultProperties) {
        this.profileRepository = profileRepository;
        this.secretResolver = secretResolver;
        this.vaultProperties = vaultProperties;
        this.restClient = RestClient.builder().baseUrl(vaultProperties.getUri()).build();
    }

    public List<CredentialSummary> list(UUID tenantId) {
        List<IntegrationProfile> profiles = profileRepository.findAll(tenantId, false);

        Map<String, List<String>> usedByRef = new LinkedHashMap<>();
        for (IntegrationProfile profile : profiles) {
            String ref = profile.configuration() != null ? profile.configuration().credentialRef() : null;
            if (ref == null || ref.isBlank()) {
                continue;
            }
            usedByRef
                    .computeIfAbsent(ref, key -> new java.util.ArrayList<>())
                    .add(profile.businessDomain() + " · " + profile.externalSource());
        }

        return usedByRef.entrySet().stream()
                .map(entry -> summarize(tenantId, entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());
    }

    private CredentialSummary summarize(UUID tenantId, String ref, List<String> usedBy) {
        String type = null;
        String state = STATE_SIN_VERIFICAR;
        try {
            ResolvedSecret resolved = secretResolver.resolve(ref, tenantId);
            type = resolved.authType() != null ? resolved.authType().name() : null;
            state = STATE_VIGENTE;
        } catch (Exception ex) {
            log.debug("Could not resolve credential {} for catalog listing: {}", ref, ex.getMessage());
        }

        Instant rotatedAt = vaultProperties.isEnabled() ? readRotatedAt(ref) : null;
        return new CredentialSummary(ref, type, usedBy, rotatedAt, state);
    }

    private Instant readRotatedAt(String ref) {
        try {
            String path = normalizePath(ref);
            Map<?, ?> response = restClient.get()
                    .uri("/v1/secret/metadata/" + path)
                    .header("X-Vault-Token", vaultProperties.getToken())
                    .retrieve()
                    .body(Map.class);
            Object dataNode = response == null ? null : response.get("data");
            if (!(dataNode instanceof Map<?, ?> data)) {
                return null;
            }
            Object updatedTime = data.get("updated_time");
            return updatedTime != null ? Instant.parse(updatedTime.toString()) : null;
        } catch (Exception ex) {
            log.debug("Could not read Vault metadata for {}: {}", ref, ex.getMessage());
            return null;
        }
    }

    private String normalizePath(String credentialRef) {
        String path = credentialRef.startsWith("vault:") ? credentialRef.substring("vault:".length()) : credentialRef;
        if (path.startsWith("secret/data/")) {
            return path.substring("secret/data/".length());
        }
        if (path.startsWith("secret/")) {
            return path.substring("secret/".length());
        }
        return path;
    }
}
