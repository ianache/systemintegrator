package com.cl2.integration.application.exception;

public class FlowExecutionNotFoundException extends RuntimeException {
    public FlowExecutionNotFoundException(String message) {
        super(message);
    }
}
