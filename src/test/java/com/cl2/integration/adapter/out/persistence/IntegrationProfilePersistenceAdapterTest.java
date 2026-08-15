package com.cl2.integration.adapter.out.persistence;

import com.cl2.integration.application.exception.IntegrationProfileConflictException;
import com.cl2.integration.application.exception.IntegrationProfileNotFoundException;
import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.domain.model.IntegrationProtocol;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class IntegrationProfilePersistenceAdapterTest {

    private static final UUID TENANT_ID = UUID.fromString("71923e5e-a4cb-4956-91fd-a492fcab5715");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("22965df9-e1f2-4375-943d-2df67a4c2e26");

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("integration")
            .withUsername("integration")
            .withPassword("integration");

    @Autowired
    private IntegrationProfilePersistenceAdapter adapter;

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @BeforeEach
    void clearProfiles() {
        jdbcTemplate.update("DELETE FROM integration_profile");
    }

    @Test
    void migratesTheIntegrationProfileSchema() throws SQLException {
        Set<String> columns;
        try (var connection = dataSource.getConnection();
             var resultSet = connection.getMetaData().getColumns(connection.getCatalog(), null,
                     "integration_profile", null)) {
            columns = new java.util.HashSet<>();
            while (resultSet.next()) {
                columns.add(resultSet.getString("COLUMN_NAME"));
            }
        }

        assertThat(flyway.info().applied()).hasSize(3);
        assertThat(columns).contains(
                "tenant_id", "active", "version", "created_at", "updated_at",
                "protocol", "connector", "adapter", "endpoint", "credential_ref",
                "mapping_json", "transformation_json", "sync_policy_json",
                "retry_policy_json", "rate_limit_policy_json"
        );
    }

    @Test
    void savesAndReadsAProfileWithinItsTenant() {
        IntegrationProfile profile = profile(TENANT_ID, "orders", "erp");

        IntegrationProfile saved = adapter.save(TENANT_ID, profile);

        IntegrationProfile found = adapter.findById(TENANT_ID, saved.id());

        assertThat(found.tenantId()).isEqualTo(TENANT_ID);
        assertThat(found.businessDomain()).isEqualTo("orders");
        assertThat(found.externalSource()).isEqualTo("erp");
        assertThat(found.direction()).isEqualTo(SyncDirection.BIDIRECTIONAL);
        assertThat(found.sourceOfTruth()).isEqualTo(SourceOfTruth.PLATFORM);
        assertThat(found.active()).isTrue();
        assertThat(found.version()).isZero();
        assertThat(found.configuration()).isNull();
    }

    @Test
    void savesAndReadsAProfileWithConfiguration() {
        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                IntegrationProtocol.REST, "sigo", "sigo-vehicle-http", "https://sigo.test/api", "secret/sigo/orders",
                "{\"vin\":\"vehicle.vin\"}", "{\"status\":\"MAP_STATUS\"}", "{\"mode\":\"INCREMENTAL\"}",
                "{\"maxAttempts\":3,\"initialBackoffMs\":100}", "{\"requestsPerSecond\":10}"
        );
        IntegrationProfile profile = IntegrationProfile.create(
                UUID.randomUUID(), TENANT_ID, "orders", "erp",
                SyncDirection.BIDIRECTIONAL, SourceOfTruth.PLATFORM, config);

        IntegrationProfile saved = adapter.save(TENANT_ID, profile);

        IntegrationProfile found = adapter.findById(TENANT_ID, saved.id());

        assertThat(found.configuration()).isEqualTo(config);
    }

    @Test
    void doesNotExposeProfilesAcrossTenants() {
        IntegrationProfile profile = adapter.save(TENANT_ID, profile(TENANT_ID, "orders", "erp"));

        assertThatThrownBy(() -> adapter.findById(OTHER_TENANT_ID, profile.id()))
                .isInstanceOf(IntegrationProfileNotFoundException.class);
        assertThat(adapter.findAll(OTHER_TENANT_ID, false)).isEmpty();
        assertThat(adapter.existsActive(OTHER_TENANT_ID, "orders", "erp")).isFalse();
    }

    @Test
    void preservesPersistedTimestampsWhenReadingAProfile() {
        IntegrationProfile saved = adapter.save(TENANT_ID, profile(TENANT_ID, "orders", "erp"));
        IntegrationProfile updated = adapter.save(TENANT_ID, saved.update(
                "invoices", "erp", SyncDirection.BIDIRECTIONAL, SourceOfTruth.PLATFORM, saved.version()));

        IntegrationProfile found = adapter.findById(TENANT_ID, updated.id());

        assertThat(found.createdAt()).isEqualTo(saved.createdAt());
        assertThat(found.updatedAt()).isEqualTo(updated.updatedAt());
    }

    @Test
    void persistsLogicalDeactivationAndExcludesItFromActiveLists() {
        IntegrationProfile saved = adapter.save(TENANT_ID, profile(TENANT_ID, "orders", "erp"));

        adapter.save(TENANT_ID, saved.deactivate());

        assertThat(adapter.findAll(TENANT_ID, true)).isEmpty();
        assertThat(adapter.findAll(TENANT_ID, false))
                .singleElement()
                .satisfies(found -> {
                    assertThat(found.active()).isFalse();
                    assertThat(found.version()).isEqualTo(1);
                });
        assertThat(adapter.existsActive(TENANT_ID, "orders", "erp")).isFalse();
    }

    @Test
    void rejectsDuplicateActiveProfilesButAllowsInactiveHistory() {
        IntegrationProfile original = adapter.save(TENANT_ID, profile(TENANT_ID, "orders", "erp"));

        assertThatThrownBy(() -> adapter.save(TENANT_ID, profile(TENANT_ID, "orders", "erp")))
                .isInstanceOf(IntegrationProfileConflictException.class);

        adapter.save(TENANT_ID, original.deactivate());
        IntegrationProfile replacement = adapter.save(TENANT_ID, profile(TENANT_ID, "orders", "erp"));

        assertThat(replacement.active()).isTrue();
        assertThat(adapter.findAll(TENANT_ID, false))
                .extracting(IntegrationProfile::active)
                .containsExactlyInAnyOrder(false, true);
    }

    @Test
    void rejectsAStaleMutationAfterAnotherCallerUpdatesTheSameVersion() {
        IntegrationProfile saved = adapter.save(TENANT_ID, profile(TENANT_ID, "orders", "erp"));
        IntegrationProfile firstMutation = saved.update(
                "invoices", "erp", SyncDirection.BIDIRECTIONAL, SourceOfTruth.PLATFORM, saved.version());
        IntegrationProfile staleMutation = saved.update(
                "payments", "erp", SyncDirection.BIDIRECTIONAL, SourceOfTruth.PLATFORM, saved.version());

        adapter.save(TENANT_ID, firstMutation);

        assertThatThrownBy(() -> adapter.save(TENANT_ID, staleMutation))
                .isInstanceOf(IntegrationProfileConflictException.class);
        assertThat(adapter.findById(TENANT_ID, saved.id()).businessDomain()).isEqualTo("invoices");
    }

    @Test
    void rejectsSavingAProfileForADifferentTenant() {
        IntegrationProfile profile = profile(TENANT_ID, "orders", "erp");

        assertThatThrownBy(() -> adapter.save(OTHER_TENANT_ID, profile))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);

        assertThat(adapter.findAll(TENANT_ID, false)).isEmpty();
    }

    private IntegrationProfile profile(UUID tenantId, String businessDomain, String externalSource) {
        return IntegrationProfile.create(
                UUID.randomUUID(), tenantId, businessDomain, externalSource,
                SyncDirection.BIDIRECTIONAL, SourceOfTruth.PLATFORM);
    }
}
