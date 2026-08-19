package org.casemgmt.error;

/** Raised when an enterprise authorization decision denies the requested operation. */
public class AuthorizationDeniedException extends RuntimeException {
    public AuthorizationDeniedException(String message) {
        super(message);
    }
}
