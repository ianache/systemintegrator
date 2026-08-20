package com.cl2.integration.adapter.out.generic.security;

public class InvalidSqlExtractionException extends RuntimeException {
    public InvalidSqlExtractionException(String message) {
        super(message);
    }

    public InvalidSqlExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
