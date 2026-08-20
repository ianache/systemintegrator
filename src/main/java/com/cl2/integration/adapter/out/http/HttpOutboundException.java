package com.cl2.integration.adapter.out.http;

public class HttpOutboundException extends RuntimeException {

    private final Integer statusCode;
    private final String responseBody;

    public HttpOutboundException(String message) {
        super(message);
        this.statusCode = null;
        this.responseBody = null;
    }

    public HttpOutboundException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = null;
        this.responseBody = null;
    }

    public HttpOutboundException(String message, int statusCode, String responseBody, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
