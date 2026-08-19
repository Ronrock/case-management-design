package org.casemgmt.error;

/** Raised when authorization must be evaluated but the policy decision point is unavailable. */
public class AuthorizationUnavailableException extends RuntimeException {
    public AuthorizationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
