package com.cl2.integration.integration.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SyncPolicyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesCronExpressionAndOverlapBuffer() throws Exception {
        SyncPolicy policy = objectMapper.readValue(
                "{\"cronExpression\":\"0 */10 * * * *\",\"overlapBufferSeconds\":300}", SyncPolicy.class);

        assertThat(policy.cronExpression()).isEqualTo("0 */10 * * * *");
        assertThat(policy.overlapBufferSecondsOrZero()).isEqualTo(300);
    }

    @Test
    void defaultsOverlapBufferToZeroWhenAbsent() throws Exception {
        SyncPolicy policy = objectMapper.readValue("{\"cronExpression\":\"0 */10 * * * *\"}", SyncPolicy.class);

        assertThat(policy.overlapBufferSecondsOrZero()).isZero();
    }

    @Test
    void ignoresUnknownFields() throws Exception {
        SyncPolicy policy = objectMapper.readValue(
                "{\"cronExpression\":\"0 */10 * * * *\",\"mode\":\"INCREMENTAL\"}", SyncPolicy.class);

        assertThat(policy.cronExpression()).isEqualTo("0 */10 * * * *");
    }
}
