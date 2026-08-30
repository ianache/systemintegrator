package com.cl2.integration.adapter.out.persistence;

import com.cl2.integration.application.exception.FlowConflictException;
import com.cl2.integration.application.exception.FlowNotFoundException;
import com.cl2.integration.domain.model.Flow;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles("test")
@SpringBootTest
class FlowPersistenceAdapterTest {

    private static final UUID TENANT_ID = UUID.fromString("71923e5e-a4cb-4956-91fd-a492fcab5715");
    private static final UUID OTHER_TENANT_ID = UUID.fromString("22965df9-e1f2-4375-943d-2df67a4c2e26");

    @Autowired
    private FlowPersistenceAdapter adapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearFlows() {
        jdbcTemplate.update("DELETE FROM flow_version");
        jdbcTemplate.update("DELETE FROM flow");
    }

    /**
     * Compare two JSON strings semantically, tolerating MySQL's JSON normalization
     * (which adds spaces after colons and commas). Parses both strings as JSON objects
     * and compares the parsed structures.
     */
    private void assertJsonEqual(String expected, String actual) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Object expectedObj = mapper.readValue(expected, Object.class);
        Object actualObj = mapper.readValue(actual, Object.class);
        assertThat(actualObj).isEqualTo(expectedObj);
    }

    @Test
    void savesAndReadsAFlowWithinItsTenant() {
        Flow flow = Flow.create(UUID.randomUUID(), TENANT_ID, "flow/x", "X");

        Flow saved = adapter.save(TENANT_ID, flow);
        Flow found = adapter.findById(TENANT_ID, saved.id());

        assertThat(found.code()).isEqualTo("flow/x");
        assertThat(found.name()).isEqualTo("X");
        assertThat(found.version()).isZero();
        assertThat(found.draftGraph()).isNull();
    }

    @Test
    void throwsNotFoundForAnotherTenantsFlow() {
        Flow flow = adapter.save(TENANT_ID, Flow.create(UUID.randomUUID(), TENANT_ID, "flow/x", "X"));

        assertThatThrownBy(() -> adapter.findById(OTHER_TENANT_ID, flow.id()))
                .isInstanceOf(FlowNotFoundException.class);
    }

    @Test
    void updatesTheDraftWhenTheExpectedVersionMatches() throws Exception {
        Flow flow = adapter.save(TENANT_ID, Flow.create(UUID.randomUUID(), TENANT_ID, "flow/x", "X"));

        Flow updated = adapter.save(TENANT_ID, flow.updateDraft("X renamed", "CRON */5", "{\"nodes\":[]}", 0));

        assertThat(updated.version()).isEqualTo(1);
        Flow reloaded = adapter.findById(TENANT_ID, flow.id());
        assertThat(reloaded.name()).isEqualTo("X renamed");
        // Compare JSON semantically, not byte-for-byte, to tolerate MySQL's JSON normalization
        assertJsonEqual("{\"nodes\":[]}", reloaded.draftGraph());
        assertThat(reloaded.version()).isEqualTo(1);
    }

    @Test
    void rejectsAStaleVersionOnUpdate() {
        Flow flow = adapter.save(TENANT_ID, Flow.create(UUID.randomUUID(), TENANT_ID, "flow/x", "X"));
        adapter.save(TENANT_ID, flow.updateDraft("X v1", null, null, 0));

        assertThatThrownBy(() -> adapter.save(TENANT_ID, flow.updateDraft("X v2 (stale)", null, null, 0)))
                .isInstanceOf(FlowConflictException.class);
    }

    @Test
    void enforcesUniqueCodePerTenantAmongActiveFlows() {
        adapter.save(TENANT_ID, Flow.create(UUID.randomUUID(), TENANT_ID, "flow/dup", "First"));

        assertThatThrownBy(() -> adapter.save(TENANT_ID, Flow.create(UUID.randomUUID(), TENANT_ID, "flow/dup", "Second")))
                .isInstanceOf(FlowConflictException.class);
    }

    @Test
    void allowsReusingACodeOnceTheOriginalFlowIsArchived() {
        Flow original = adapter.save(TENANT_ID, Flow.create(UUID.randomUUID(), TENANT_ID, "flow/dup", "First"));
        adapter.save(TENANT_ID, original.archive());

        Flow reused = adapter.save(TENANT_ID, Flow.create(UUID.randomUUID(), TENANT_ID, "flow/dup", "Second"));

        assertThat(reused.code()).isEqualTo("flow/dup");
    }

    @Test
    void listsOnlyNonArchivedFlowsWhenActiveOnlyIsTrue() {
        adapter.save(TENANT_ID, Flow.create(UUID.randomUUID(), TENANT_ID, "flow/a", "A"));
        Flow toArchive = adapter.save(TENANT_ID, Flow.create(UUID.randomUUID(), TENANT_ID, "flow/b", "B"));
        adapter.save(TENANT_ID, toArchive.archive());

        List<Flow> active = adapter.findAll(TENANT_ID, true);
        List<Flow> all = adapter.findAll(TENANT_ID, false);

        assertThat(active).extracting(Flow::code).containsExactly("flow/a");
        assertThat(all).extracting(Flow::code).containsExactlyInAnyOrder("flow/a", "flow/b");
    }

    @Test
    void existsActiveIsScopedToTenantAndNonArchivedFlows() {
        adapter.save(TENANT_ID, Flow.create(UUID.randomUUID(), TENANT_ID, "flow/x", "X"));

        assertThat(adapter.existsActive(TENANT_ID, "flow/x")).isTrue();
        assertThat(adapter.existsActive(OTHER_TENANT_ID, "flow/x")).isFalse();
    }
}
