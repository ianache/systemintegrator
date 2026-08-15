package com.cl2.integration.adapter.out.persistence;

import com.cl2.integration.application.exception.IntegrationProfileConflictException;
import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(IntegrationProfilePersistenceAdapter.class)
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.jdbc.time_zone=UTC"
})
class IntegrationProfilePersistenceAdapterTest {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("integration_test")
        .withUsername("integration")
        .withPassword("integration");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.driver-class-name", mysql::getDriverClassName);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IntegrationProfilePersistenceAdapter adapter;

    @Test
    void flywayCreatesTheIntegrationProfileSchemaFromAnEmptyDatabase() {
        List<String> columns = jdbcTemplate.queryForList("""
            select column_name
            from information_schema.columns
            where table_schema = database()
              and table_name = 'integration_profile'
            """, String.class);

        assertThat(columns).contains(
            "id",
            "tenant_id",
            "business_domain",
            "external_source",
            "sync_direction",
            "source_of_truth",
            "active",
            "created_at",
            "updated_at",
            "version");
    }

    @Test
    void saveAndReadMapsAllDomainFieldsForTheCallingTenant() {
        UUID tenantId = UUID.randomUUID();
        IntegrationProfile profile = profile(tenantId, "customer", "sap");

        IntegrationProfile saved = adapter.save(tenantId, profile);
        IntegrationProfile found = adapter.findById(tenantId, profile.id()).orElseThrow();

        assertThat(found.id()).isEqualTo(saved.id());
        assertThat(found.tenantId()).isEqualTo(tenantId);
        assertThat(found.businessDomain()).isEqualTo("customer");
        assertThat(found.externalSource()).isEqualTo("sap");
        assertThat(found.direction()).isEqualTo(SyncDirection.OUTBOUND);
        assertThat(found.sourceOfTruth()).isEqualTo(SourceOfTruth.EXTERNAL);
        assertThat(found.active()).isTrue();
        assertThat(found.version()).isEqualTo(saved.version());
        assertThat(found.createdAt()).isEqualTo(saved.createdAt());
        assertThat(found.updatedAt()).isEqualTo(saved.updatedAt());
    }

    @Test
    void tenantScopedQueriesHideRecordsOwnedByAnotherTenant() {
        UUID ownerTenantId = UUID.randomUUID();
        UUID otherTenantId = UUID.randomUUID();
        IntegrationProfile profile = adapter.save(ownerTenantId, profile(ownerTenantId, "customer", "sap"));

        assertThat(adapter.findById(otherTenantId, profile.id())).isEmpty();
        assertThat(adapter.findAll(otherTenantId, false)).isEmpty();
        assertThat(adapter.existsActive(otherTenantId, "customer", "sap")).isFalse();
    }

    @Test
    void logicalDeactivationRemovesTheProfileFromActiveQueries() {
        UUID tenantId = UUID.randomUUID();
        IntegrationProfile profile = adapter.save(tenantId, profile(tenantId, "customer", "sap"));
        profile.deactivate();

        IntegrationProfile saved = adapter.save(tenantId, profile);

        assertThat(saved.active()).isFalse();
        assertThat(adapter.existsActive(tenantId, "customer", "sap")).isFalse();
        assertThat(adapter.findAll(tenantId, true)).isEmpty();
        assertThat(adapter.findAll(tenantId, false)).extracting(IntegrationProfile::id).containsExactly(profile.id());
    }

    @Test
    void saveRejectsAConcurrentUpdateMadeAfterTheCallerLoadedTheProfile() {
        UUID tenantId = UUID.randomUUID();
        IntegrationProfile saved = adapter.save(tenantId, profile(tenantId, "customer", "sap"));
        IntegrationProfile firstCaller = copyOf(saved);
        IntegrationProfile staleCaller = copyOf(saved);
        firstCaller.update("vehicle", "sap", SyncDirection.INBOUND, SourceOfTruth.PLATFORM, saved.version());
        staleCaller.update("contract", "sap", SyncDirection.BIDIRECTIONAL, SourceOfTruth.SHARED, saved.version());

        IntegrationProfile winningUpdate = adapter.save(tenantId, firstCaller);

        assertThat(winningUpdate.version()).isEqualTo(1);
        assertThatThrownBy(() -> adapter.save(tenantId, staleCaller))
            .isInstanceOf(IntegrationProfileConflictException.class);
        assertThat(adapter.findById(tenantId, saved.id()).orElseThrow().businessDomain()).isEqualTo("vehicle");
    }

    @Test
    void activeUniquenessRejectsOnlyADuplicateActiveProfileWithinTheSameTenant() {
        UUID tenantId = UUID.randomUUID();
        IntegrationProfile active = adapter.save(tenantId, profile(tenantId, "customer", "sap"));

        assertThatThrownBy(() -> adapter.save(tenantId, profile(tenantId, "customer", "sap")))
            .isInstanceOf(IntegrationProfileConflictException.class);

        active.deactivate();
        adapter.save(tenantId, active);

        IntegrationProfile replacement = adapter.save(tenantId, profile(tenantId, "customer", "sap"));
        UUID otherTenantId = UUID.randomUUID();
        IntegrationProfile anotherTenant = adapter.save(
            otherTenantId, profile(otherTenantId, "customer", "sap"));

        assertThat(replacement.active()).isTrue();
        assertThat(anotherTenant.active()).isTrue();
    }

    private IntegrationProfile profile(UUID tenantId, String businessDomain, String externalSource) {
        return IntegrationProfile.create(
            UUID.randomUUID(),
            tenantId,
            businessDomain,
            externalSource,
            SyncDirection.OUTBOUND,
            SourceOfTruth.EXTERNAL);
    }

    private IntegrationProfile copyOf(IntegrationProfile profile) {
        return IntegrationProfile.restore(
            profile.id(),
            profile.tenantId(),
            profile.businessDomain(),
            profile.externalSource(),
            profile.direction(),
            profile.sourceOfTruth(),
            profile.active(),
            profile.version(),
            profile.createdAt(),
            profile.updatedAt());
    }
}
