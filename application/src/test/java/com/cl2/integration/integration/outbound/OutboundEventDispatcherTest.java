package com.cl2.integration.integration.outbound;

import com.cl2.integration.adapter.out.http.HttpOutboundClient;
import com.cl2.integration.adapter.out.http.HttpOutboundException;
import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.domain.model.IntegrationProtocol;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import com.cl2.integration.domain.port.IntegrationProfileRepository;
import com.cl2.integration.integration.resilience.CircuitBreakerOpenException;
import com.cl2.integration.integration.resilience.ResilienceExecutor;
import com.cl2.integration.integration.security.AuthType;
import com.cl2.integration.integration.security.ResolvedSecret;
import com.cl2.integration.integration.security.SecretResolver;
import com.cl2.integration.integration.transformation.TransformationException;
import com.cl2.integration.integration.transformation.TransformationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboundEventDispatcherTest {

    @Mock
    private IntegrationProfileRepository profileRepository;

    @Mock
    private SecretResolver secretResolver;

    @Mock
    private TransformationService transformationService;

    @Mock
    private ResilienceExecutor resilienceExecutor;

    @Mock
    private HttpOutboundClient httpOutboundClient;

    private OutboundEventDispatcher dispatcher;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        dispatcher = new OutboundEventDispatcher(
                profileRepository,
                secretResolver,
                transformationService,
                resilienceExecutor,
                httpOutboundClient
        );
    }

    private void stubResilienceExecutorToExecuteDirectly() {
        lenient().when(resilienceExecutor.execute(any(UUID.class), any(), any()))
                .thenAnswer(invocation -> {
                    Supplier<?> supplier = invocation.getArgument(2);
                    return supplier.get();
                });
    }

    @Test
    @DisplayName("Should successfully orchestrate secret resolution, transformation, resilience, and HTTP dispatch for matching OUTBOUND profile")
    void shouldSuccessfullyDispatchEventToMatchingOutboundProfile() {
        stubResilienceExecutorToExecuteDirectly();

        String rawPayload = "{\"id\":\"cust-123\",\"name\":\"Acme Corp\"}";
        String transformedPayload = "{\"externalId\":\"cust-123\",\"companyName\":\"Acme Corp\"}";
        String endpoint = "https://api.external-crm.com/v1/customers";
        String credentialRef = "vault:secret/data/crm";

        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                IntegrationProtocol.REST,
                "crm-connector",
                "generic-http",
                endpoint,
                credentialRef,
                null,
                "{\"engine\":\"jslt\",\"script\":\"...\"}",
                null,
                null,
                null,
                null
        );

        IntegrationProfile profile = IntegrationProfile.create(
                UUID.randomUUID(),
                tenantId,
                "customers",
                "salesforce",
                SyncDirection.OUTBOUND,
                SourceOfTruth.PLATFORM,
                config
        );

        ResolvedSecret secret = ResolvedSecret.bearer(credentialRef, "token-xyz-123");

        when(profileRepository.findAll(tenantId, true)).thenReturn(List.of(profile));
        when(secretResolver.resolve(credentialRef, tenantId)).thenReturn(secret);
        when(transformationService.transform(rawPayload, profile)).thenReturn(transformedPayload);

        dispatcher.dispatch(eventId, tenantId, "customer.created", rawPayload);

        verify(profileRepository).findAll(tenantId, true);
        verify(secretResolver).resolve(credentialRef, tenantId);
        verify(transformationService).transform(rawPayload, profile);
        verify(resilienceExecutor).execute(eq(tenantId), eq("crm-connector"), any());
        verify(httpOutboundClient).send(endpoint, secret, transformedPayload, tenantId);
    }

    @Test
    @DisplayName("Should successfully dispatch event to matching BIDIRECTIONAL profile")
    void shouldSuccessfullyDispatchEventToBidirectionalProfile() {
        stubResilienceExecutorToExecuteDirectly();

        String rawPayload = "{\"id\":\"cust-456\"}";
        String transformedPayload = "{\"customerId\":\"cust-456\"}";
        String endpoint = "https://partner.api.com/customers";

        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                IntegrationProtocol.REST,
                "partner-connector",
                "generic-http",
                endpoint,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        IntegrationProfile profile = IntegrationProfile.create(
                UUID.randomUUID(),
                tenantId,
                "customers",
                "partner",
                SyncDirection.BIDIRECTIONAL,
                SourceOfTruth.PLATFORM,
                config
        );

        when(profileRepository.findAll(tenantId, true)).thenReturn(List.of(profile));
        when(transformationService.transform(rawPayload, profile)).thenReturn(transformedPayload);

        dispatcher.dispatch(eventId, tenantId, "CustomerCreatedEvent", rawPayload);

        verify(secretResolver, never()).resolve(anyString(), any(UUID.class));
        verify(httpOutboundClient).send(endpoint, null, transformedPayload, tenantId);
    }

    @Test
    @DisplayName("Should ignore INBOUND profiles and not trigger HTTP dispatch")
    void shouldIgnoreInboundProfiles() {
        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                IntegrationProtocol.REST,
                "connector-1",
                "generic-http",
                "https://api.test.com/inbound",
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        IntegrationProfile profile = IntegrationProfile.create(
                UUID.randomUUID(),
                tenantId,
                "customers",
                "inbound-source",
                SyncDirection.INBOUND,
                SourceOfTruth.EXTERNAL,
                config
        );

        when(profileRepository.findAll(tenantId, true)).thenReturn(List.of(profile));

        dispatcher.dispatch(eventId, tenantId, "customer.created", "{}");

        verifyNoInteractions(secretResolver, transformationService, resilienceExecutor, httpOutboundClient);
    }

    @Test
    @DisplayName("Should ignore non-REST protocols (e.g. KAFKA, JDBC) for HTTP outbound dispatch")
    void shouldIgnoreNonRestProtocols() {
        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                IntegrationProtocol.KAFKA,
                "kafka-connector",
                "kafka-adapter",
                "kafka://cluster/topic",
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        IntegrationProfile profile = IntegrationProfile.create(
                UUID.randomUUID(),
                tenantId,
                "customers",
                "kafka-target",
                SyncDirection.OUTBOUND,
                SourceOfTruth.PLATFORM,
                config
        );

        when(profileRepository.findAll(tenantId, true)).thenReturn(List.of(profile));

        dispatcher.dispatch(eventId, tenantId, "customer.created", "{}");

        verifyNoInteractions(secretResolver, transformationService, resilienceExecutor, httpOutboundClient);
    }

    @Test
    @DisplayName("Should ignore profiles where businessDomain does not match eventType domain")
    void shouldIgnoreProfilesWithNonMatchingBusinessDomain() {
        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                IntegrationProtocol.REST,
                "vehicle-connector",
                "generic-http",
                "https://api.fleet.com/vehicles",
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        IntegrationProfile profile = IntegrationProfile.create(
                UUID.randomUUID(),
                tenantId,
                "vehicles",
                "fleet-manager",
                SyncDirection.OUTBOUND,
                SourceOfTruth.PLATFORM,
                config
        );

        when(profileRepository.findAll(tenantId, true)).thenReturn(List.of(profile));

        dispatcher.dispatch(eventId, tenantId, "customer.created", "{}");

        verifyNoInteractions(secretResolver, transformationService, resilienceExecutor, httpOutboundClient);
    }

    @Test
    @DisplayName("Should dispatch to multiple matching OUTBOUND profiles for the same domain")
    void shouldDispatchToMultipleMatchingOutboundProfiles() {
        stubResilienceExecutorToExecuteDirectly();

        String rawPayload = "{\"id\":\"order-1\"}";

        IntegrationProfileConfiguration config1 = new IntegrationProfileConfiguration(
                IntegrationProtocol.REST, "connector-1", "adapter-1", "https://api1.com", "secret-1", null, null, null, null, null, null
        );
        IntegrationProfile profile1 = IntegrationProfile.create(
                UUID.randomUUID(), tenantId, "orders", "source-1", SyncDirection.OUTBOUND, SourceOfTruth.PLATFORM, config1
        );

        IntegrationProfileConfiguration config2 = new IntegrationProfileConfiguration(
                IntegrationProtocol.REST, "connector-2", "adapter-2", "https://api2.com", "secret-2", null, null, null, null, null, null
        );
        IntegrationProfile profile2 = IntegrationProfile.create(
                UUID.randomUUID(), tenantId, "orders", "source-2", SyncDirection.OUTBOUND, SourceOfTruth.PLATFORM, config2
        );

        when(profileRepository.findAll(tenantId, true)).thenReturn(List.of(profile1, profile2));
        when(secretResolver.resolve("secret-1", tenantId)).thenReturn(ResolvedSecret.apiKey("secret-1", "key1"));
        when(secretResolver.resolve("secret-2", tenantId)).thenReturn(ResolvedSecret.apiKey("secret-2", "key2"));
        when(transformationService.transform(rawPayload, profile1)).thenReturn("{\"order\":1}");
        when(transformationService.transform(rawPayload, profile2)).thenReturn("{\"ord\":1}");

        dispatcher.dispatch(eventId, tenantId, "orders.created", rawPayload);

        verify(httpOutboundClient).send(eq("https://api1.com"), any(), eq("{\"order\":1}"), eq(tenantId));
        verify(httpOutboundClient).send(eq("https://api2.com"), any(), eq("{\"ord\":1}"), eq(tenantId));
    }

    @Test
    @DisplayName("Should complete gracefully without error when no active profiles exist for tenant")
    void shouldCompleteGracefullyWhenNoProfilesFound() {
        when(profileRepository.findAll(tenantId, true)).thenReturn(List.of());

        dispatcher.dispatch(eventId, tenantId, "customer.created", "{}");

        verify(profileRepository).findAll(tenantId, true);
        verifyNoInteractions(secretResolver, transformationService, resilienceExecutor, httpOutboundClient);
    }

    @Test
    @DisplayName("Should propagate exception when TransformationService throws TransformationException")
    void shouldPropagateExceptionWhenTransformationFails() {
        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                IntegrationProtocol.REST, "connector-1", "adapter-1", "https://api.com", null, null, "invalid-json", null, null, null, null
        );
        IntegrationProfile profile = IntegrationProfile.create(
                UUID.randomUUID(), tenantId, "customers", "crm", SyncDirection.OUTBOUND, SourceOfTruth.PLATFORM, config
        );

        when(profileRepository.findAll(tenantId, true)).thenReturn(List.of(profile));
        when(transformationService.transform(anyString(), eq(profile)))
                .thenThrow(new TransformationException("Invalid mapping script"));

        assertThatThrownBy(() -> dispatcher.dispatch(eventId, tenantId, "customer.created", "{}"))
                .isInstanceOf(TransformationException.class)
                .hasMessageContaining("Invalid mapping script");

        verifyNoInteractions(httpOutboundClient);
    }

    @Test
    @DisplayName("Should propagate CircuitBreakerOpenException when ResilienceExecutor blocks execution")
    void shouldPropagateExceptionWhenCircuitBreakerIsOpen() {
        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                IntegrationProtocol.REST, "failing-connector", "adapter-1", "https://api.com", null, null, null, null, null, null, null
        );
        IntegrationProfile profile = IntegrationProfile.create(
                UUID.randomUUID(), tenantId, "customers", "crm", SyncDirection.OUTBOUND, SourceOfTruth.PLATFORM, config
        );

        when(profileRepository.findAll(tenantId, true)).thenReturn(List.of(profile));
        when(transformationService.transform(anyString(), eq(profile))).thenReturn("{}");
        when(resilienceExecutor.execute(eq(tenantId), eq("failing-connector"), any()))
                .thenThrow(new CircuitBreakerOpenException(tenantId, "failing-connector"));

        assertThatThrownBy(() -> dispatcher.dispatch(eventId, tenantId, "customer.created", "{}"))
                .isInstanceOf(CircuitBreakerOpenException.class);

        verifyNoInteractions(httpOutboundClient);
    }

    @Test
    @DisplayName("Should propagate HttpOutboundException when HttpOutboundClient fails")
    void shouldPropagateExceptionWhenHttpOutboundClientFails() {
        stubResilienceExecutorToExecuteDirectly();

        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                IntegrationProtocol.REST, "failing-http-connector", "adapter-1", "https://api.com/fail", null, null, null, null, null, null, null
        );
        IntegrationProfile profile = IntegrationProfile.create(
                UUID.randomUUID(), tenantId, "customers", "crm", SyncDirection.OUTBOUND, SourceOfTruth.PLATFORM, config
        );

        when(profileRepository.findAll(tenantId, true)).thenReturn(List.of(profile));
        when(transformationService.transform(anyString(), eq(profile))).thenReturn("{}");
        doThrow(new HttpOutboundException("500 Internal Server Error", 500, "Server Error", null))
                .when(httpOutboundClient).send(eq("https://api.com/fail"), isNull(), eq("{}"), eq(tenantId));

        assertThatThrownBy(() -> dispatcher.dispatch(eventId, tenantId, "customer.created", "{}"))
                .isInstanceOf(HttpOutboundException.class)
                .hasMessageContaining("500 Internal Server Error");
    }

    @Test
    @DisplayName("Should derive business domain correctly for different eventType formats")
    void shouldDeriveBusinessDomainFromVariousEventTypes() {
        stubResilienceExecutorToExecuteDirectly();

        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                IntegrationProtocol.REST, "connector-1", "adapter-1", "https://api.com", null, null, null, null, null, null, null
        );
        IntegrationProfile customerProfile = IntegrationProfile.create(
                UUID.randomUUID(), tenantId, "customers", "crm", SyncDirection.OUTBOUND, SourceOfTruth.PLATFORM, config
        );
        IntegrationProfile vehicleProfile = IntegrationProfile.create(
                UUID.randomUUID(), tenantId, "vehicles", "telematics", SyncDirection.OUTBOUND, SourceOfTruth.PLATFORM, config
        );

        when(profileRepository.findAll(tenantId, true)).thenReturn(List.of(customerProfile, vehicleProfile));
        when(transformationService.transform(anyString(), any())).thenReturn("{}");

        // customer.created -> should match customerProfile only
        dispatcher.dispatch(eventId, tenantId, "customer.created", "{}");
        verify(transformationService, times(1)).transform("{}", customerProfile);
        verify(transformationService, never()).transform("{}", vehicleProfile);

        // vehicles.location.updated -> should match vehicleProfile only
        reset(transformationService);
        when(transformationService.transform(anyString(), any())).thenReturn("{}");
        dispatcher.dispatch(eventId, tenantId, "vehicles.location.updated", "{}");
        verify(transformationService, times(1)).transform("{}", vehicleProfile);
        verify(transformationService, never()).transform("{}", customerProfile);
    }

    @Test
    @DisplayName("Should pass tenantId to HttpOutboundClient when dispatching event with OAuth2 secret")
    void shouldPassTenantIdToHttpOutboundClient() {
        stubResilienceExecutorToExecuteDirectly();

        String rawPayload = "{\"name\":\"Toyota\"}";
        String transformedPayload = "{\"name\":\"Toyota\"}";
        String endpoint = "https://api.cl2.com/api/v1/brands";
        String credentialRef = "vault:secret/data/keycloak";

        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                IntegrationProtocol.REST,
                "units-connector",
                "generic-http",
                endpoint,
                credentialRef,
                null,
                null,
                null,
                null,
                null,
                null
        );

        IntegrationProfile profile = IntegrationProfile.create(
                UUID.randomUUID(),
                tenantId,
                "brands",
                "keycloak-cl2",
                SyncDirection.OUTBOUND,
                SourceOfTruth.PLATFORM,
                config
        );

        ResolvedSecret secret = ResolvedSecret.oauth2(credentialRef, "http://auth/token", "client", "secret");

        when(profileRepository.findAll(tenantId, true)).thenReturn(List.of(profile));
        when(secretResolver.resolve(credentialRef, tenantId)).thenReturn(secret);
        when(transformationService.transform(rawPayload, profile)).thenReturn(transformedPayload);

        dispatcher.dispatch(eventId, tenantId, "brands.created", rawPayload);

        verify(httpOutboundClient).send(endpoint, secret, transformedPayload, tenantId);
    }
}
