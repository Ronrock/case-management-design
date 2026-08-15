package org.casemgmt.starter;

import org.casemgmt.event.EventPublisher;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.SlaRepository;
import org.casemgmt.sla.SlaService;
import org.casemgmt.sla.SlaSweeper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CaseManagementSlaConfiguration {

    @Bean
    public SlaService slaService(SlaRepository sla, CaseRepository cases, EventPublisher publisher) {
        return new SlaService(sla, cases, publisher);
    }

    @Bean
    public SlaSweeper slaSweeper(SlaRepository sla, CaseRepository cases, EventPublisher publisher) {
        return new SlaSweeper(sla, cases, publisher);
    }
}
