package com.clydeexchange.server.auth;

/** Slow down: too many failed logins, or too many orders in a second. ApiExceptionHandler turns this into a 429. */
public class TooManyRequestsException extends RuntimeException {

    public TooManyRequestsException(String message) {
        super(message);
    }
}
