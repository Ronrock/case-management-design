package org.casemgmt.starter;

import org.casemgmt.permissions.WorkerPermissionEvaluator;
import org.casemgmt.permissions.WorkerPermissionsClient;
import org.casemgmt.permissions.WorkerPermissionsTokenProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CaseManagementWorkerPermissionsConfiguration {

    @Bean
    @ConditionalOnMissingBean(WorkerPermissionsTokenProvider.class)
    public WorkerPermissionsTokenProvider workerPermissionsTokenProvider(
            CaseManagementProperties props) {
        return () -> props.getWorkerPermissions().getBearerToken();
    }

    @Bean
    @ConditionalOnMissingBean(WorkerPermissionsClient.class)
    public WorkerPermissionsClient workerPermissionsClient(CaseManagementProperties props,
                                                            WorkerPermissionsTokenProvider tokenProvider) {
        CaseManagementProperties.WorkerPermissions config = props.getWorkerPermissions();
        if (!config.isEnabled()) {
            return WorkerPermissionsClient.denyAll();
        }
        return new WorkerPermissionsHttpClient(config.getBaseUrl(), config.getEvaluatePath(),
                tokenProvider, config.getConnectTimeoutMs(), config.getReadTimeoutMs());
    }

    @Bean
    @ConditionalOnMissingBean(WorkerPermissionEvaluator.class)
    public WorkerPermissionEvaluator workerPermissionEvaluator(WorkerPermissionsClient client) {
        return new WorkerPermissionEvaluator(client);
    }
}
