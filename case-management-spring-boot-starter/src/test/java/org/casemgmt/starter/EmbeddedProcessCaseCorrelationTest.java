package org.casemgmt.starter;

import org.casemgmt.repo.CaseDefinitionVersionBindingRepository;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.LinkedProcessRepository;
import org.casemgmt.observation.EngineProcessAuthorityLookup;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbeddedProcessCaseCorrelationTest {

    @Test
    void starterCorrelationIgnoresAnUnmarkedForeignProcess() {
        LinkedProcessRepository processes = mock(LinkedProcessRepository.class);
        when(processes.findByProcessInstanceId("foreign-process"))
                .thenReturn(Optional.empty());

        var configuration =
                new EmbeddedEngineAutoConfiguration.EmbeddedEngineGatewayConfiguration();
        var correlation = configuration.processCaseCorrelation(
                mock(EngineProcessAuthorityLookup.class), processes, mock(CaseRepository.class),
                mock(CaseDefinitionVersionBindingRepository.class));

        assertThat(correlation.caseId("foreign-process", "foreign:1")).isNull();
    }
}
