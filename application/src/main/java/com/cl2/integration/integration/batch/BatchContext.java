package com.cl2.integration.integration.batch;

public record BatchContext(boolean batchMode, int batchSize) {

    public static BatchContext unitary() {
        return new BatchContext(false, 0);
    }

    public static BatchContext batch(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        return new BatchContext(true, batchSize);
    }
}
