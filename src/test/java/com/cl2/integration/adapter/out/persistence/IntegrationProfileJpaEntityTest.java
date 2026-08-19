package com.cl2.integration.adapter.out.persistence;

import com.cl2.integration.domain.model.IntegrationProfile;
import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.domain.model.IntegrationProtocol;
import com.cl2.integration.domain.model.SourceOfTruth;
import com.cl2.integration.domain.model.SyncDirection;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IntegrationProfileJpaEntityTest {

    @Test
    void rehydratesThePersistedTimestampsWithoutReplayingDomainTransitions() {
        Instant createdAt = Instant.parse("2026-08-14T19:20:30.123456Z");
        Instant updatedAt = Instant.parse("2026-08-15T01:02:03.654321Z");
        IntegrationProfile persisted = IntegrationProfile.rehydrate(
                UUID.fromString("3c32c264-9163-4985-a3df-cb67a1031039"),
                UUID.fromString("71923e5e-a4cb-4956-91fd-a492fcab5715"),
                "orders", "erp", SyncDirection.BIDIRECTIONAL, SourceOfTruth.PLATFORM,
                false, createdAt, updatedAt, 7);

        IntegrationProfile rehydrated = IntegrationProfileJpaEntity.from(persisted).toDomain();

        assertThat(rehydrated.createdAt()).isEqualTo(persisted.createdAt());
        assertThat(rehydrated.updatedAt()).isEqualTo(persisted.updatedAt());
        assertThat(rehydrated.version()).isEqualTo(persisted.version());
        assertThat(rehydrated.active()).isEqualTo(persisted.active());
        assertThat(rehydrated.configuration()).isNull();
    }

    @Test
    void rehydratesFullConfiguration() {
        Instant createdAt = Instant.parse("2026-08-14T19:20:30.123456Z");
        Instant updatedAt = Instant.parse("2026-08-15T01:02:03.654321Z");
        IntegrationProfileConfiguration config = new IntegrationProfileConfiguration(
                IntegrationProtocol.REST, "sigo", "sigo-vehicle-http", "https://sigo.test/api", "secret/sigo/orders",
                "{\"vin\":\"vehicle.vin\"}", "{\"status\":\"MAP_STATUS\"}", "{\"mode\":\"INCREMENTAL\"}",
                "{\"maxAttempts\":3,\"initialBackoffMs\":100}", "{\"requestsPerSecond\":10}", null
        );
        IntegrationProfile persisted = IntegrationProfile.rehydrate(
                UUID.fromString("3c32c264-9163-4985-a3df-cb67a1031039"),
                UUID.fromString("71923e5e-a4cb-4956-91fd-a492fcab5715"),
                "orders", "erp", SyncDirection.BIDIRECTIONAL, SourceOfTruth.PLATFORM,
                config, false, createdAt, updatedAt, 7);

        IntegrationProfile rehydrated = IntegrationProfileJpaEntity.from(persisted).toDomain();

        assertThat(rehydrated.configuration()).isEqualTo(config);
    }
}
