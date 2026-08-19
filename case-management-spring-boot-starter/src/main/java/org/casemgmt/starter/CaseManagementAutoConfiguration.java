package org.casemgmt.starter;

import org.casemgmt.config.TransactionManagerConfig;
import org.casemgmt.rest.controller.CaseController;
import org.casemgmt.rest.controller.CaseDefinitionController;
import org.casemgmt.rest.controller.CollaborationController;
import org.casemgmt.rest.controller.EventController;
import org.casemgmt.rest.controller.PlanItemController;
import org.casemgmt.rest.controller.SearchController;
import org.casemgmt.rest.controller.SlaController;
import org.casemgmt.rest.controller.TaskController;
import org.casemgmt.rest.error.ProblemDetailHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@ConditionalOnProperty(prefix = "casemgmt", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(CaseManagementProperties.class)
@Import({
        TransactionManagerConfig.class,
        CaseManagementRepositoryConfiguration.class,
        CaseManagementPolicyConfiguration.class,
        CaseManagementServiceConfiguration.class,
        CaseManagementWorkerPermissionsConfiguration.class,
        CaseManagementSearchConfiguration.class,
        CaseManagementWebhookConfiguration.class,
        CaseManagementSlaConfiguration.class,
        CaseController.class,
        PlanItemController.class,
        TaskController.class,
        CaseDefinitionController.class,
        CollaborationController.class,
        EventController.class,
        SlaController.class,
        SearchController.class,
        ProblemDetailHandler.class
})
public class CaseManagementAutoConfiguration {
}
