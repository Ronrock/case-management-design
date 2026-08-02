package org.casemgmt.service;

import java.util.List;

/** Who is performing a case mutation — carried through to CM_AUDIT_LOG and engine task assignment. */
public record Actor(String userId, List<String> groups) {
    public static final Actor SYSTEM = new Actor("system", List.of());
}
