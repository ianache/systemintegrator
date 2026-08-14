package com.cl2.integration.adapter.in.web;

import com.cl2.integration.application.exception.IntegrationProfileConflictException;
import com.cl2.integration.application.exception.IntegrationProfileNotFoundException;
import com.cl2.integration.application.exception.TenantRequiredException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The request body is invalid", request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class, TenantRequiredException.class})
    ProblemDetail handleBadRequest(Exception exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "The request is invalid", request);
    }

    @ExceptionHandler(IntegrationProfileNotFoundException.class)
    ProblemDetail handleNotFound(IntegrationProfileNotFoundException exception, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "INTEGRATION_PROFILE_NOT_FOUND", "Integration profile was not found", request);
    }

    @ExceptionHandler(IntegrationProfileConflictException.class)
    ProblemDetail handleConflict(IntegrationProfileConflictException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "INTEGRATION_PROFILE_CONFLICT", "Integration profile conflicts with an existing profile", request);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception exception, HttpServletRequest request) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred", request);
    }

    private ProblemDetail problem(HttpStatus status, String errorCode, String detail, HttpServletRequest request) {
        return ApiProblemDetailFactory.create(status, errorCode, detail, request);
    }
}
