package com.cl2.integration.integration.transformation;

public class TransformationException extends RuntimeException {
    public TransformationException(String message) {
        super(message);
    }
    public TransformationException(String message, Throwable cause) {
        super(message, cause);
    }
}
