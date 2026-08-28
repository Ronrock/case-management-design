package org.casemgmt.projection;

/** Bounded ownership/collision rejection; never carries engine payload data. */
public final class ProjectionOwnershipException extends SecurityException {

    public enum Classification {
        CROSS_OWNER,
        RELATIONSHIP_MISMATCH,
        ENTITY_KIND_MISMATCH,
        INSERT_COLLISION
    }

    private final Classification classification;

    public ProjectionOwnershipException(Classification classification) {
        super("Projection ownership rejected: " + classification.name());
        this.classification = classification;
    }

    public Classification classification() {
        return classification;
    }
}
