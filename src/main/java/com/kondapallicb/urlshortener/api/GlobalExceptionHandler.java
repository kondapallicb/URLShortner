package com.kondapallicb.urlshortener.api;

import com.kondapallicb.urlshortener.domain.UrlMappingExpiredException;
import com.kondapallicb.urlshortener.domain.UrlMappingNotFoundException;
import com.kondapallicb.urlshortener.orchestration.WorkflowRunNotFoundException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleInvalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("INVALID_REQUEST", exception.getMessage(), List.of()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidState(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of("INVALID_STATE", exception.getMessage(), List.of()));
    }

    @ExceptionHandler({com.kondapallicb.urlshortener.domain.IdempotencyConflictException.class,
            com.kondapallicb.urlshortener.domain.SlugConflictException.class})
    public ResponseEntity<ErrorResponse> handleConflict(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("CONFLICT", exception.getMessage(), List.of()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        List<String> details = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .toList();
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("VALIDATION_FAILED", "Request validation failed", details));
    }

    @ExceptionHandler(UrlMappingNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(UrlMappingNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("URL_NOT_FOUND", exception.getMessage(), List.of()));
    }

    @ExceptionHandler(UrlMappingExpiredException.class)
    public ResponseEntity<ErrorResponse> handleExpired(UrlMappingExpiredException exception) {
        return ResponseEntity.status(HttpStatus.GONE)
                .body(ErrorResponse.of("URL_EXPIRED", exception.getMessage(), List.of()));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRateLimit(RateLimitExceededException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ErrorResponse.of("RATE_LIMIT_EXCEEDED", exception.getMessage(), List.of()));
    }

    @ExceptionHandler(WorkflowRunNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleWorkflowNotFound(WorkflowRunNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("WORKFLOW_NOT_FOUND", exception.getMessage(), List.of()));
    }
}
