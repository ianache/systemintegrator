package com.cl2.integration.domain.model;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlowExecutionTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID FLOW_ID = UUID.randomUUID();

    @Test
    void computesDurationFromStartAndFinish() {
        Instant started = Instant.parse("2026-08-30T00:00:00Z");
        Instant finished = Instant.parse("2026-08-30T00:00:00.750Z");

        FlowExecution execution = FlowExecution.report(UUID.randomUUID(), TENANT_ID, FLOW_ID, 1,
                FlowExecutionStatus.SUCCESS, started, finished, null);

        assertThat(execution.durationMs()).isEqualTo(750);
        assertThat(execution.status()).isEqualTo(FlowExecutionStatus.SUCCESS);
        assertThat(execution.errorMessage()).isNull();
    }

    @Test
    void rejectsAFinishTimeBeforeTheStartTime() {
        Instant started = Instant.parse("2026-08-30T00:00:01Z");
        Instant finished = Instant.parse("2026-08-30T00:00:00Z");

        assertThatThrownBy(() -> FlowExecution.report(UUID.randomUUID(), TENANT_ID, FLOW_ID, 1,
                FlowExecutionStatus.FAILURE, started, finished, "boom"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keepsTheErrorMessageForAFailure() {
        Instant started = Instant.parse("2026-08-30T00:00:00Z");
        Instant finished = Instant.parse("2026-08-30T00:00:01Z");

        FlowExecution execution = FlowExecution.report(UUID.randomUUID(), TENANT_ID, FLOW_ID, 2,
                FlowExecutionStatus.FAILURE, started, finished, "connector timeout");

        assertThat(execution.errorMessage()).isEqualTo("connector timeout");
        assertThat(execution.flowVersionNumber()).isEqualTo(2);
    }
}
