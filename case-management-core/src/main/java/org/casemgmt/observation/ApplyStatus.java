package org.casemgmt.observation;

/** Outcome of applying one engine fact. */
public enum ApplyStatus {
    APPLIED,
    DUPLICATE,
    IGNORED_STALE
}
