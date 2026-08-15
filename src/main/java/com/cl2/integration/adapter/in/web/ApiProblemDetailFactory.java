package com.cl2.integration.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

public final class ApiProblemDetailFactory {

    private ApiProblemDetailFactory() {
    }

    public static ProblemDetail create(
            HttpStatus status,
            String errorCode,
            String detail,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("errorCode", errorCode);
        problem.setProperty("correlationId", correlationId(request));
        return problem;
    }

    private static String correlationId(HttpServletRequest request) {
        String requestCorrelationId = request.getHeader("X-Correlation-ID");
        return requestCorrelationId == null || requestCorrelationId.isBlank()
                ? UUID.randomUUID().toString()
                : requestCorrelationId;
    }
}
