package org.casemgmt.starter;

import org.casemgmt.permissions.PermissionActions;
import org.casemgmt.permissions.ResourceTypes;
import org.casemgmt.permissions.WorkerPermissionRequest;
import org.casemgmt.permissions.WorkerPermissionResource;
import org.casemgmt.permissions.WorkerPermissionsTokenProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CaseManagementWorkerPermissionsConfigurationTest {

    @Test
    void disabledEnterpriseIntegrationLeavesStandaloneLocalPolicyOperational() {
        CaseManagementProperties properties = new CaseManagementProperties();
        var client = new CaseManagementWorkerPermissionsConfiguration().workerPermissionsClient(
                properties, WorkerPermissionsTokenProvider.none());

        var decisions = client.evaluate(new WorkerPermissionRequest("t1", "alice",
                List.of("users"), PermissionActions.CASE_READ, ResourceTypes.CASE,
                List.of(new WorkerPermissionResource("case-1", Map.of()))));

        assertThat(decisions.get("case-1").allowed()).isTrue();
    }
}
