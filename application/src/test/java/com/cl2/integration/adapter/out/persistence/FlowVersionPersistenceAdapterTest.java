package com.cl2.integration.adapter.out.persistence;

import com.cl2.integration.domain.model.Flow;
import com.cl2.integration.domain.model.FlowVersion;
import com.cl2.integration.domain.model.FlowVersionState;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
class FlowVersionPersistenceAdapterTest {

    private static final UUID TENANT_ID = UUID.fromString("71923e5e-a4cb-4956-91fd-a492fcab5715");

    @Autowired
    private FlowVersionPersistenceAdapter versionAdapter;

    @Autowired
    private FlowPersistenceAdapter flowAdapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID flowId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM flow_version");
        jdbcTemplate.update("DELETE FROM flow");
        Flow flow = flowAdapter.save(TENANT_ID, Flow.create(UUID.randomUUID(), TENANT_ID, "flow/x", "X"));
        flowId = flow.id();
    }

    @Test
    void savesAndListsVersionsMostRecentFirst() {
        versionAdapter.save(TENANT_ID, FlowVersion.publish(UUID.randomUUID(), flowId, TENANT_ID, 1, "{\"v\":1}", "user"));
        versionAdapter.save(TENANT_ID, FlowVersion.publish(UUID.randomUUID(), flowId, TENANT_ID, 2, "{\"v\":2}", "user"));

        List<FlowVersion> versions = versionAdapter.findAllByFlowId(TENANT_ID, flowId);

        assertThat(versions).extracting(FlowVersion::versionNumber).containsExactly(2, 1);
    }

    @Test
    void findsAVersionByFlowIdAndVersionNumber() {
        versionAdapter.save(TENANT_ID, FlowVersion.publish(UUID.randomUUID(), flowId, TENANT_ID, 1, "{}", "user"));

        Optional<FlowVersion> found = versionAdapter.findByFlowIdAndVersionNumber(TENANT_ID, flowId, 1);

        assertThat(found).isPresent();
        assertThat(found.get().state()).isEqualTo(FlowVersionState.ACTIVE);
    }

    @Test
    void findsTheActiveVersionAndUpdatingItsStatePersists() {
        FlowVersion saved = versionAdapter.save(TENANT_ID,
                FlowVersion.publish(UUID.randomUUID(), flowId, TENANT_ID, 1, "{}", "user"));

        versionAdapter.save(TENANT_ID, saved.withState(FlowVersionState.PUBLISHED));

        assertThat(versionAdapter.findActiveByFlowId(TENANT_ID, flowId)).isEmpty();
        Optional<FlowVersion> reloaded = versionAdapter.findByFlowIdAndVersionNumber(TENANT_ID, flowId, 1);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().state()).isEqualTo(FlowVersionState.PUBLISHED);
    }

    @Test
    void nextVersionNumberStartsAtOneAndIncrements() {
        assertThat(versionAdapter.nextVersionNumber(TENANT_ID, flowId)).isEqualTo(1);

        versionAdapter.save(TENANT_ID, FlowVersion.publish(UUID.randomUUID(), flowId, TENANT_ID, 1, "{}", "user"));

        assertThat(versionAdapter.nextVersionNumber(TENANT_ID, flowId)).isEqualTo(2);
    }
}
