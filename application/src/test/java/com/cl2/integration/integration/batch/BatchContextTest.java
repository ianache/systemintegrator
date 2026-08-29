package com.cl2.integration.integration.batch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BatchContextTest {

    @Test
    void shouldCreateUnitaryContext() {
        assertEquals(new BatchContext(false, 0), BatchContext.unitary());
    }

    @Test
    void shouldCreateBatchContextForPositiveSize() {
        assertEquals(new BatchContext(true, 2), BatchContext.batch(2));
    }

    @Test
    void shouldRejectNonPositiveBatchSize() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> BatchContext.batch(0));

        assertEquals("batchSize must be positive", exception.getMessage());
    }
}
