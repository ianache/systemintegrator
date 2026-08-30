package com.cl2.integration.domain.model;

import com.cl2.integration.application.exception.FlowConflictException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlowTest {

    private static final UUID FLOW_ID = UUID.fromString("3c32c264-9163-4985-a3df-cb67a1031039");
    private static final UUID TENANT_ID = UUID.fromString("71923e5e-a4cb-4956-91fd-a492fcab5715");

    @Test
    void createBuildsADraftFlowAtVersionZero() {
        Flow flow = Flow.create(FLOW_ID, TENANT_ID, "flow/vehiculo-alta", "Alta de vehiculos");

        assertThat(flow.id()).isEqualTo(FLOW_ID);
        assertThat(flow.tenantId()).isEqualTo(TENANT_ID);
        assertThat(flow.code()).isEqualTo("flow/vehiculo-alta");
        assertThat(flow.name()).isEqualTo("Alta de vehiculos");
        assertThat(flow.draftGraph()).isNull();
        assertThat(flow.activeVersionNumber()).isNull();
        assertThat(flow.archived()).isFalse();
        assertThat(flow.version()).isZero();
        assertThat(flow.status()).isEqualTo(FlowStatus.DRAFT);
    }

    @Test
    void rejectsBlankCode() {
        assertThatThrownBy(() -> Flow.create(FLOW_ID, TENANT_ID, "  ", "name"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateDraftIncrementsVersionAndKeepsStatusDraft() {
        Flow flow = Flow.create(FLOW_ID, TENANT_ID, "flow/x", "X");

        Flow updated = flow.updateDraft("X renamed", "CRON */5", "{\"nodes\":[]}", 0);

        assertThat(updated.name()).isEqualTo("X renamed");
        assertThat(updated.triggerSummary()).isEqualTo("CRON */5");
        assertThat(updated.draftGraph()).isEqualTo("{\"nodes\":[]}");
        assertThat(updated.version()).isEqualTo(1);
        assertThat(updated.status()).isEqualTo(FlowStatus.DRAFT);
    }

    @Test
    void updateDraftRejectsAStaleExpectedVersion() {
        Flow flow = Flow.create(FLOW_ID, TENANT_ID, "flow/x", "X");

        assertThatThrownBy(() -> flow.updateDraft("X", null, null, 5))
                .isInstanceOf(FlowConflictException.class);
    }

    @Test
    void withActiveVersionMovesStatusToPublished() {
        Flow flow = Flow.create(FLOW_ID, TENANT_ID, "flow/x", "X");

        Flow published = flow.withActiveVersion(1);

        assertThat(published.activeVersionNumber()).isEqualTo(1);
        assertThat(published.status()).isEqualTo(FlowStatus.PUBLISHED);
        assertThat(published.version()).isEqualTo(1);
    }

    @Test
    void archiveMovesStatusToObsoleteRegardlessOfActiveVersion() {
        Flow flow = Flow.create(FLOW_ID, TENANT_ID, "flow/x", "X").withActiveVersion(1);

        Flow archived = flow.archive();

        assertThat(archived.archived()).isTrue();
        assertThat(archived.status()).isEqualTo(FlowStatus.OBSOLETE);
    }

    @Test
    void archiveIsIdempotent() {
        Flow flow = Flow.create(FLOW_ID, TENANT_ID, "flow/x", "X").archive();

        Flow archivedAgain = flow.archive();

        assertThat(archivedAgain.version()).isEqualTo(flow.version());
    }
}
