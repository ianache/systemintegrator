package com.cl2.integration.integration.sync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
class SyncStatePersistenceAdapterTest {

    @Autowired
    private SyncStateRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clear() {
        jdbcTemplate.update("DELETE FROM integration_profile");
        jdbcTemplate.update(
                "INSERT INTO integration_profile (id, tenant_id, business_domain, external_source, sync_direction, source_of_truth, active, version, created_at, updated_at) "
                + "VALUES (UNHEX(REPLACE(?, '-', '')), UNHEX(REPLACE(?, '-', '')), 'customers', 'sap-hana', 'INBOUND', 'EXTERNAL', 1, 0, NOW(6), NOW(6))",
                PROFILE_ID.toString(), UUID.randomUUID().toString());
    }

    private static final UUID PROFILE_ID = UUID.randomUUID();

    @Test
    void returnsEmptyWhenNoStateRecordedYet() {
        assertThat(repository.find(PROFILE_ID)).isEmpty();
    }

    @Test
    void insertsAndReadsBackANewState() {
        Instant watermark = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant startedAt = watermark.minusSeconds(5);
        repository.upsert(new SyncState(PROFILE_ID, watermark, startedAt, SyncRunStatus.SUCCESS, null));

        SyncState found = repository.find(PROFILE_ID).orElseThrow();

        assertThat(found.profileId()).isEqualTo(PROFILE_ID);
        assertThat(found.lastWatermark()).isEqualTo(watermark);
        assertThat(found.lastRunStartedAt()).isEqualTo(startedAt);
        assertThat(found.lastRunStatus()).isEqualTo(SyncRunStatus.SUCCESS);
        assertThat(found.lastError()).isNull();
    }

    @Test
    void upsertOverwritesThePreviousStateForTheSameProfile() {
        Instant firstWatermark = Instant.now().truncatedTo(ChronoUnit.MICROS);
        repository.upsert(new SyncState(PROFILE_ID, firstWatermark, firstWatermark, SyncRunStatus.SUCCESS, null));

        Instant secondStartedAt = firstWatermark.plusSeconds(600);
        repository.upsert(new SyncState(PROFILE_ID, firstWatermark, secondStartedAt, SyncRunStatus.FAILED, "boom"));

        SyncState found = repository.find(PROFILE_ID).orElseThrow();
        assertThat(found.lastRunStartedAt()).isEqualTo(secondStartedAt);
        assertThat(found.lastRunStatus()).isEqualTo(SyncRunStatus.FAILED);
        assertThat(found.lastError()).isEqualTo("boom");
    }
}
