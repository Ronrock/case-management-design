package org.casemgmt.starter;

import org.casemgmt.permissions.WorkerPermissionsClient;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.DocumentRepository;
import org.casemgmt.search.CaseProjectionSearchProvider;
import org.casemgmt.search.DocumentMetadataSearchProvider;
import org.casemgmt.search.SearchOrchestrator;
import org.casemgmt.search.SearchProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration(proxyBeanMethods = false)
public class CaseManagementSearchConfiguration {

    @Bean
    @ConditionalOnMissingBean(CaseProjectionSearchProvider.class)
    public CaseProjectionSearchProvider caseProjectionSearchProvider(
            CaseRepository cases, WorkerPermissionsClient permissions) {
        return new CaseProjectionSearchProvider(cases, permissions);
    }

    @Bean
    @ConditionalOnMissingBean(DocumentMetadataSearchProvider.class)
    public DocumentMetadataSearchProvider documentMetadataSearchProvider(
            DocumentRepository documents, WorkerPermissionsClient permissions) {
        return new DocumentMetadataSearchProvider(documents, permissions);
    }

    @Bean
    @ConditionalOnMissingBean(SearchOrchestrator.class)
    public SearchOrchestrator searchOrchestrator(List<SearchProvider> providers) {
        return new SearchOrchestrator(providers);
    }
}
