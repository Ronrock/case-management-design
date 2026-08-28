package org.casemgmt.starter;

import org.casemgmt.repo.CaseDefinitionVersionBindingRepository;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.LinkedProcessRepository;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.RuntimeService;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbeddedProcessCaseCorrelationTest {

    @Test
    void starterCorrelationIgnoresAnUnmarkedForeignProcess() {
        RuntimeService runtime = mock(RuntimeService.class);
        LinkedProcessRepository processes = mock(LinkedProcessRepository.class);
        when(processes.findByProcessInstanceId("foreign-process"))
                .thenReturn(Optional.empty());

        var configuration =
                new EmbeddedEngineAutoConfiguration.EmbeddedEngineGatewayConfiguration();
        var correlation = configuration.processCaseCorrelation(runtime,
                mock(RepositoryService.class), processes, mock(CaseRepository.class),
                mock(CaseDefinitionVersionBindingRepository.class));

        assertThat(correlation.caseId("foreign-process", "foreign:1")).isNull();
    }
}
