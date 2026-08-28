package org.casemgmt.engine;

import org.casemgmt.domain.CaseTask;
import org.casemgmt.repo.EngineCommandRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EngineCommandDispatcherRootConfirmationTest {

    @Test
    void inconsistentReturnedDefinitionIdentityRetriesWithoutConfirmation() {
        EngineCommandRepository commands = mock(EngineCommandRepository.class);
        EngineGateway engine = mock(EngineGateway.class);
        EngineCommandDispatcher.SyncReporter reporter =
                mock(EngineCommandDispatcher.SyncReporter.class);
        EngineCommand command = exactStartCommand();
        when(commands.claimDue(50)).thenReturn(List.of(command));
        when(engine.startProcess(any())).thenReturn(new EngineProcessRef(
                "engine-process-42", "orders:2:other", "orders", "case-1"));

        new EngineCommandDispatcher(commands, engine, reporter).drainOnce();

        verify(reporter, never()).confirmProcessStarted(
                any(), any(), any(), any(), any(), any());
        verify(commands, never()).markDone(command.id());
        verify(commands).markRetry(eq(command.id()), contains("process-definition id"), any());
    }

    @Test
    void successfulStartReportsCaseCorrelationAndRealEngineIdentityTogether() {
        EngineCommandRepository commands = mock(EngineCommandRepository.class);
        EngineGateway engine = mock(EngineGateway.class);
        EngineCommand command = exactStartCommand();
        when(commands.claimDue(50)).thenReturn(List.of(command));
        when(engine.startProcess(any()))
                .thenReturn(new EngineProcessRef("engine-process-42", "orders:1:exact",
                        "orders", "case-1"));
        RecordingReporter reporter = new RecordingReporter();

        new EngineCommandDispatcher(commands, engine, reporter).drainOnce();

        assertThat(reporter.caseId).isEqualTo("case-1");
        assertThat(reporter.correlationId).isEqualTo("root-correlation");
        assertThat(reporter.engineProcessInstanceId).isEqualTo("engine-process-42");
        assertThat(reporter.processDefinitionId).isEqualTo("orders:1:exact");
        assertThat(reporter.processDefinitionKey).isEqualTo("orders");
        assertThat(reporter.confirmedAt).isNotNull();
    }

    private static EngineCommand exactStartCommand() {
        return new EngineCommand("command-1", "case-1",
                EngineCommand.Type.START_PROCESS, Map.of(
                        "selectionType", "ID",
                        "processDefinitionId", "orders:1:exact",
                        "processDefinitionKey", "orders",
                        "tenantId", "",
                        "planItemId", "",
                        "variables", Map.of(),
                        "correlationId", "root-correlation"),
                "CLAIMED", 0, OffsetDateTime.parse("2026-08-28T07:00:00Z"), null);
    }

    private static final class RecordingReporter implements EngineCommandDispatcher.SyncReporter {
        private String caseId;
        private String correlationId;
        private String engineProcessInstanceId;
        private String processDefinitionId;
        private String processDefinitionKey;
        private OffsetDateTime confirmedAt;

        @Override
        public void report(String correlationKey, CaseTask.EngineSync sync, String engineId) {
            throw new AssertionError("START_PROCESS success must use atomic confirmation reporting");
        }

        @Override
        public void confirmProcessStarted(String caseId, String correlationId,
                                          String engineProcessInstanceId,
                                          String processDefinitionId,
                                          String processDefinitionKey,
                                          OffsetDateTime confirmedAt) {
            this.caseId = caseId;
            this.correlationId = correlationId;
            this.engineProcessInstanceId = engineProcessInstanceId;
            this.processDefinitionId = processDefinitionId;
            this.processDefinitionKey = processDefinitionKey;
            this.confirmedAt = confirmedAt;
        }
    }
}
