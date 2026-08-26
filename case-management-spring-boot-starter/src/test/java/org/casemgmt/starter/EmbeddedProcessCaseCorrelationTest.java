package org.casemgmt.starter;

import org.casemgmt.engine.embedded.EmbeddedEngineGateway;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.engine.RuntimeService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddedProcessCaseCorrelationTest {

    @Test
    void resolvesTheCaseVariableBeforeQueryingAnUnflushedProcessInstance() {
        RuntimeService runtime = mock(RuntimeService.class);
        when(runtime.getVariable("process-1", EmbeddedEngineGateway.CASE_ID_VARIABLE))
                .thenReturn("case-1");

        var configuration =
                new EmbeddedEngineAutoConfiguration.EmbeddedEngineGatewayConfiguration();
        var correlation = configuration.processCaseCorrelation(runtime);

        assertThat(correlation.caseId("process-1")).isEqualTo("case-1");
        verify(runtime, never()).createProcessInstanceQuery();
    }
}
