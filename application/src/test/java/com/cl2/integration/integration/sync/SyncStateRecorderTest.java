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
class SyncStateRecorderTest {

    @Autowired
    private SyncStateRecorder recorder;

    @Autowired
    private SyncStateRepository syncStateRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final UUID PROFILE_ID = UUID.randomUUID();

    @BeforeEach
    void seedProfile() {
        jdbcTemplate.update("DELETE FROM integration_sync_state");
        jdbcTemplate.update("DELETE FROM integration_outbox");
        jdbcTemplate.update("DELETE FROM integration_profile");
        jdbcTemplate.update(
                "INSERT INTO integration_profile (id, tenant_id, business_domain, external_source, sync_direction, source_of_truth, active, version, created_at, updated_at) "
                + "VALUES (UNHEX(REPLACE(?, '-', '')), UNHEX(REPLACE(?, '-', '')), 'customers', 'sap-hana', 'INBOUND', 'EXTERNAL', 1, 0, NOW(6), NOW(6))",
                PROFILE_ID.toString(), UUID.randomUUID().toString());
    }

    @Test
    void recordsAFailureWithoutTouchingTheExistingWatermark() {
        Instant existingWatermark = Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.MICROS);
        syncStateRepository.upsert(new SyncState(PROFILE_ID, existingWatermark, existingWatermark, SyncRunStatus.SUCCESS, null));

        Instant startedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        recorder.recordFailure(PROFILE_ID, startedAt, "connection refused");

        SyncState found = syncStateRepository.find(PROFILE_ID).orElseThrow();
        assertThat(found.lastWatermark()).isEqualTo(existingWatermark);
        assertThat(found.lastRunStartedAt()).isEqualTo(startedAt);
        assertThat(found.lastRunStatus()).isEqualTo(SyncRunStatus.FAILED);
        assertThat(found.lastError()).isEqualTo("connection refused");
    }

    @Test
    void truncatesAnOverlyLongErrorMessage() {
        String longMessage = "x".repeat(2000);
        recorder.recordFailure(PROFILE_ID, Instant.now(), longMessage);

        SyncState found = syncStateRepository.find(PROFILE_ID).orElseThrow();
        assertThat(found.lastError()).hasSize(1000);
    }
}
