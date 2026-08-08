package org.casemgmt.error;

public class PreconditionRequiredException extends RuntimeException {
    public PreconditionRequiredException() {
        super("This mutation requires an If-Match header carrying the resource's current ETag");
    }
}
