package org.casemgmt.error;

/**
 * Thrown when an {@code UPDATE ... WHERE ID_ = :id AND VERSION_ = :expected} affects zero
 * rows: either the row does not exist, or someone else wrote it first. Never retried by the
 * repository — surfaced as HTTP 412 by the REST layer so the client can re-read the current
 * ETag and decide whether to retry (design doc Appendix B).
 */
public class OptimisticLockException extends RuntimeException {
    public OptimisticLockException(String resourceType, String id, long expectedVersion) {
        super(resourceType + " " + id + " was modified concurrently (expected version "
                + expectedVersion + ")");
    }
}
