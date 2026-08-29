package com.cl2.integration.integration.outbound;

import com.cl2.integration.adapter.out.http.HttpOutboundClient;
import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.domain.model.IntegrationProtocol;
import com.cl2.integration.domain.model.SyncDirection;
import com.cl2.integration.domain.port.IntegrationProfileRepository;
import com.cl2.integration.integration.resilience.ResilienceExecutor;
import com.cl2.integration.integration.batch.BatchContext;
import com.cl2.integration.integration.security.ResolvedSecret;
import com.cl2.integration.integration.security.SecretResolver;
import com.cl2.integration.integration.transformation.TransformationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class OutboundEventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboundEventDispatcher.class);

    private final IntegrationProfileRepository profileRepository;
    private final SecretResolver secretResolver;
    private final TransformationService transformationService;
    private final ResilienceExecutor resilienceExecutor;
    private final HttpOutboundClient httpOutboundClient;

    public OutboundEventDispatcher(
            IntegrationProfileRepository profileRepository,
            SecretResolver secretResolver,
            TransformationService transformationService,
            ResilienceExecutor resilienceExecutor,
            HttpOutboundClient httpOutboundClient) {
        this.profileRepository = profileRepository;
        this.secretResolver = secretResolver;
        this.transformationService = transformationService;
        this.resilienceExecutor = resilienceExecutor;
        this.httpOutboundClient = httpOutboundClient;
    }

    public void dispatch(UUID eventId, UUID tenantId, String eventType, String payload) {
        dispatch(eventId, tenantId, eventType, payload, null, BatchContext.unitary());
    }

    public void dispatch(UUID eventId, UUID tenantId, String eventType, String payload, String originExternalSource) {
        dispatch(eventId, tenantId, eventType, payload, originExternalSource, BatchContext.unitary());
    }

    public void dispatch(
            UUID eventId,
            UUID tenantId,
            String eventType,
            String payload,
            String originExternalSource,
            BatchContext batchContext) {
        if (tenantId == null) {
            log.warn("Cannot dispatch outbound event: tenantId is null (eventId={}, eventType={})", eventId, eventType);
            return;
        }

        BatchContext effectiveBatchContext = batchContext != null ? batchContext : BatchContext.unitary();

        String derivedDomain = deriveBusinessDomain(eventType);
        log.debug("Dispatching outbound event: eventId={}, tenantId={}, eventType={}, derivedDomain={}, originSource={}",
                eventId, tenantId, eventType, derivedDomain, originExternalSource);

        List<IntegrationProfile> activeProfiles = profileRepository.findAll(tenantId, true);
        if (activeProfiles == null || activeProfiles.isEmpty()) {
            log.info("No active integration profiles configured for tenantId={}", tenantId);
            return;
        }

        List<IntegrationProfile> matchingProfiles = activeProfiles.stream()
                .filter(profile -> isOutboundRestProfile(profile))
                .filter(profile -> matchesBusinessDomain(profile.businessDomain(), derivedDomain, eventType))
                .filter(profile -> originExternalSource == null || !originExternalSource.equalsIgnoreCase(profile.externalSource()))
                .toList();

        if (matchingProfiles.isEmpty()) {
            log.info("No matching active outbound REST profiles found for tenantId={}, eventType={}, derivedDomain={}, originSource={}",
                    tenantId, eventType, derivedDomain, originExternalSource);
            return;
        }

        log.debug("Found {} matching outbound REST profile(s) for eventId={}", matchingProfiles.size(), eventId);

        for (IntegrationProfile profile : matchingProfiles) {
            dispatchToProfile(eventId, tenantId, payload, profile, effectiveBatchContext.batchMode());
        }
    }

    private void dispatchToProfile(
            UUID eventId,
            UUID tenantId,
            String payload,
            IntegrationProfile profile,
            boolean batchMode) {
        IntegrationProfileConfiguration config = profile.configuration();
        String credentialRef = config != null ? config.credentialRef() : null;

        ResolvedSecret secret = null;
        if (credentialRef != null && !credentialRef.isBlank()) {
            secret = secretResolver.resolve(credentialRef, tenantId);
        }

        String outboundPayload = batchMode ? payload : transformationService.transform(payload, profile);
        String endpoint = config != null ? config.endpoint() : null;
        String connector = config != null ? config.connector() : "default";

        log.debug("Sending outbound event to endpoint={} via connector={} (profileId={}, eventId={})",
                endpoint, connector, profile.id(), eventId);

        final ResolvedSecret finalSecret = secret;
        resilienceExecutor.execute(tenantId, connector, () -> {
            httpOutboundClient.send(endpoint, finalSecret, outboundPayload, tenantId);
            return null;
        });
    }

    private boolean isOutboundRestProfile(IntegrationProfile profile) {
        if (profile == null) {
            return false;
        }
        SyncDirection direction = profile.direction();
        if (direction != SyncDirection.OUTBOUND && direction != SyncDirection.BIDIRECTIONAL) {
            return false;
        }
        IntegrationProfileConfiguration config = profile.configuration();
        if (config == null || config.protocol() != IntegrationProtocol.REST) {
            return false;
        }
        return true;
    }

    public String deriveBusinessDomain(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return "";
        }
        String lower = eventType.toLowerCase();
        if (lower.contains("customer")) {
            return "customers";
        }
        if (lower.contains("vehicle")) {
            return "vehicles";
        }
        if (lower.contains("order")) {
            return "orders";
        }
        if (eventType.contains(".")) {
            return eventType.substring(0, eventType.indexOf('.')).toLowerCase();
        }
        if (eventType.contains("/")) {
            return eventType.substring(0, eventType.indexOf('/')).toLowerCase();
        }
        if (eventType.contains(":")) {
            return eventType.substring(0, eventType.indexOf(':')).toLowerCase();
        }
        if (eventType.contains("-")) {
            return eventType.substring(0, eventType.indexOf('-')).toLowerCase();
        }
        if (eventType.contains("_")) {
            return eventType.substring(0, eventType.indexOf('_')).toLowerCase();
        }
        String cleaned = eventType.endsWith("Event") ? eventType.substring(0, eventType.length() - 5) : eventType;
        String[] parts = cleaned.split("(?=\\p{Upper})");
        if (parts.length > 0 && !parts[0].isBlank()) {
            return parts[0].toLowerCase();
        } else if (parts.length > 1 && !parts[1].isBlank()) {
            return parts[1].toLowerCase();
        }
        return lower;
    }

    private boolean matchesBusinessDomain(String profileDomain, String derivedDomain, String eventType) {
        if (profileDomain == null || profileDomain.isBlank()) {
            return false;
        }
        String pd = profileDomain.trim().toLowerCase();
        if (derivedDomain != null && !derivedDomain.isBlank()) {
            String dd = derivedDomain.trim().toLowerCase();
            if (pd.equals(dd) || (pd + "s").equals(dd) || pd.equals(dd + "s")) {
                return true;
            }
        }
        if (eventType != null && !eventType.isBlank()) {
            String et = eventType.toLowerCase();
            if (et.contains(pd)) {
                return true;
            }
            if (pd.endsWith("s") && et.contains(pd.substring(0, pd.length() - 1))) {
                return true;
            }
        }
        return false;
    }
}
