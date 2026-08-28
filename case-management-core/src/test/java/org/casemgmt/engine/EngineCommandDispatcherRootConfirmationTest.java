package org.casemgmt.engine;

import org.casemgmt.domain.CaseTask;
import org.casemgmt.repo.EngineCommandRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EngineCommandDispatcherRootConfirmationTest {

    @Test
    void successfulStartReportsCaseCorrelationAndRealEngineIdentityTogether() {
        EngineCommandRepository commands = mock(EngineCommandRepository.class);
        EngineGateway engine = mock(EngineGateway.class);
        EngineCommand command = new EngineCommand("command-1", "case-1",
                EngineCommand.Type.START_PROCESS, Map.of(
                        "selectionType", "ID",
                        "processDefinitionId", "orders:1:exact",
                        "processDefinitionKey", "orders",
                        "tenantId", "",
                        "planItemId", "",
                        "variables", Map.of(),
                        "correlationId", "root-correlation"),
                "CLAIMED", 0, OffsetDateTime.parse("2026-08-28T07:00:00Z"), null);
        when(commands.claimDue(50)).thenReturn(List.of(command));
        when(engine.startProcess(any()))
                .thenReturn(new EngineProcessRef("engine-process-42", "orders", "case-1"));
        RecordingReporter reporter = new RecordingReporter();

        new EngineCommandDispatcher(commands, engine, reporter).drainOnce();

        assertThat(reporter.caseId).isEqualTo("case-1");
        assertThat(reporter.correlationId).isEqualTo("root-correlation");
        assertThat(reporter.engineProcessInstanceId).isEqualTo("engine-process-42");
        assertThat(reporter.confirmedAt).isNotNull();
    }

    private static final class RecordingReporter implements EngineCommandDispatcher.SyncReporter {
        private String caseId;
        private String correlationId;
        private String engineProcessInstanceId;
        private OffsetDateTime confirmedAt;

        @Override
        public void report(String correlationKey, CaseTask.EngineSync sync, String engineId) {
            throw new AssertionError("START_PROCESS success must use atomic confirmation reporting");
        }

        @Override
        public void confirmProcessStarted(String caseId, String correlationId,
                                          String engineProcessInstanceId,
                                          OffsetDateTime confirmedAt) {
            this.caseId = caseId;
            this.correlationId = correlationId;
            this.engineProcessInstanceId = engineProcessInstanceId;
            this.confirmedAt = confirmedAt;
        }
    }
}
