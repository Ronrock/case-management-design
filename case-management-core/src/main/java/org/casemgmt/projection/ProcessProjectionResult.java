package org.casemgmt.projection;

/** Authoritative outcome of the handler-safe process projection operation. */
public record ProcessProjectionResult(boolean rootTransitioned, long caseVersion) { }
