package org.casemgmt.observation;

/** Bounded, non-sensitive rejection categories suitable for telemetry. */
public enum ObservationRejectionReason {
    TENANT_MISMATCH,
    ENGINE_MISMATCH,
    BINDING_MISSING,
    BINDING_IDENTITY_MISMATCH,
    NON_BPMN_BINDING,
    BINDING_STATUS,
    PROCESS_NOT_LINKED,
    PROCESS_DEFINITION_MISMATCH,
    ENTITY_OWNERSHIP,
    PROJECTION_COLLISION,
    ORDERING_MODE_MISMATCH,
    RECONCILIATION_REQUIRED
}
