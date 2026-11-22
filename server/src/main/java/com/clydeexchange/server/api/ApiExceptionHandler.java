package com.clydeexchange.server.api;

import com.clydeexchange.server.auth.NotLoggedInException;
import com.clydeexchange.server.auth.TooManyRequestsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns the errors a caller can cause into the right HTTP status with a readable message. */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** The engine's validation errors, and rejected registrations. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(NotLoggedInException.class)
    public ProblemDetail notLoggedIn(NotLoggedInException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ProblemDetail tooManyRequests(TooManyRequestsException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }
}
