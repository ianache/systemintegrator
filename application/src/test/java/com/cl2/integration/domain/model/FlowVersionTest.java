package com.cl2.integration.domain.model;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlowVersionTest {

    private static final UUID VERSION_ID = UUID.fromString("3c32c264-9163-4985-a3df-cb67a1031039");
    private static final UUID FLOW_ID = UUID.fromString("22965df9-e1f2-4375-943d-2df67a4c2e26");
    private static final UUID TENANT_ID = UUID.fromString("71923e5e-a4cb-4956-91fd-a492fcab5715");

    @Test
    void publishCreatesAnActiveVersion() {
        FlowVersion version = FlowVersion.publish(VERSION_ID, FLOW_ID, TENANT_ID, 1, "{\"nodes\":[]}", "user@tenant");

        assertThat(version.id()).isEqualTo(VERSION_ID);
        assertThat(version.flowId()).isEqualTo(FLOW_ID);
        assertThat(version.tenantId()).isEqualTo(TENANT_ID);
        assertThat(version.versionNumber()).isEqualTo(1);
        assertThat(version.graph()).isEqualTo("{\"nodes\":[]}");
        assertThat(version.state()).isEqualTo(FlowVersionState.ACTIVE);
        assertThat(version.publishedBy()).isEqualTo("user@tenant");
        assertThat(version.publishedAt()).isNotNull();
    }

    @Test
    void rejectsAVersionNumberBelowOne() {
        assertThatThrownBy(() -> FlowVersion.publish(VERSION_ID, FLOW_ID, TENANT_ID, 0, "{}", "user"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsABlankGraph() {
        assertThatThrownBy(() -> FlowVersion.publish(VERSION_ID, FLOW_ID, TENANT_ID, 1, "  ", "user"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withStateReturnsANewInstanceWithTheGivenState() {
        FlowVersion active = FlowVersion.publish(VERSION_ID, FLOW_ID, TENANT_ID, 1, "{}", "user");

        FlowVersion superseded = active.withState(FlowVersionState.PUBLISHED);

        assertThat(superseded.state()).isEqualTo(FlowVersionState.PUBLISHED);
        assertThat(superseded.versionNumber()).isEqualTo(active.versionNumber());
        assertThat(active.state()).isEqualTo(FlowVersionState.ACTIVE);
    }
}
