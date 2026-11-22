package com.clydeexchange.server.auth;

/** The request did not prove who it is from. ApiExceptionHandler turns this into a 401. */
public class NotLoggedInException extends RuntimeException {

    public NotLoggedInException(String message) {
        super(message);
    }
}
