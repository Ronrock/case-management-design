package org.casemgmt.permissions;

@FunctionalInterface
public interface WorkerPermissionsTokenProvider {

    String bearerToken();

    static WorkerPermissionsTokenProvider none() {
        return () -> null;
    }
}
