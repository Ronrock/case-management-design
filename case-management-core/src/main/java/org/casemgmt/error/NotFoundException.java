package org.casemgmt.error;

/**
 * Thrown when a caller requests a resource by id and no such row exists.
 * Maps to HTTP 404 at the REST layer.
 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String resourceType, String id) {
        super(resourceType + " not found: " + id);
    }
}
