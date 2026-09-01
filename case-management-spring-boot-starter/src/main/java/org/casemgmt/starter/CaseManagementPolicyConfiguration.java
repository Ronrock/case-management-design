package org.casemgmt.starter;

import org.casemgmt.repo.ParticipantRepository;
import org.casemgmt.rest.CallerResolver;
import org.casemgmt.rest.policy.ActionPolicy;
import org.casemgmt.rules.CriterionEvaluator;
import org.casemgmt.rules.JuelCriterionEvaluator;
import org.casemgmt.service.FormValidator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CaseManagementPolicyConfiguration {

    @Bean
    @ConditionalOnMissingBean(CriterionEvaluator.class)
    public CriterionEvaluator criterionEvaluator() {
        return new JuelCriterionEvaluator();
    }

    @Bean
    @ConditionalOnMissingBean(FormValidator.class)
    public FormValidator formValidator() {
        return new FormValidator();
    }

    @Bean
    @ConditionalOnMissingBean(ActionPolicy.class)
    public ActionPolicy actionPolicy() {
        return new ActionPolicy();
    }

    @Bean
    @ConditionalOnMissingBean(CallerResolver.class)
    public CallerResolver callerResolver(ParticipantRepository participants) {
        return new CallerResolver(participants);
    }
}
