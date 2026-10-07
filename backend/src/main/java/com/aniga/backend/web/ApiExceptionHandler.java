package com.aniga.backend.web;

import java.util.NoSuchElementException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail notFound(NoSuchElementException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail conflict(DataIntegrityViolationException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "constraint violated (duplicate username or unknown user/media id)");
    }

    @ExceptionHandler(RestClientException.class)
    public ProblemDetail upstream(RestClientException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "Upstream source request failed: " + e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail unavailable(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
    }
}
