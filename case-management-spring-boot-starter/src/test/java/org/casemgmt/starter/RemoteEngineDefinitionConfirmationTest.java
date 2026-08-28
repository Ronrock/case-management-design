package org.casemgmt.starter;

import org.casemgmt.engine.EngineCommand;
import org.casemgmt.engine.EngineProcessRef;
import org.casemgmt.engine.remote.RemoteEngineGateway;
import org.casemgmt.repo.CaseTaskRepository;
import org.casemgmt.repo.EngineCommandRepository;
import org.casemgmt.repo.LinkedProcessRepository;
import org.casemgmt.service.LinkedProcessService;
import org.casemgmt.service.OrchestrationDeploymentReportService;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RemoteEngineDefinitionConfirmationTest {

    @Test
    void remoteDispatcherWiringConfirmsExactDefinitionIdentity() {
        EngineCommandRepository commands = mock(EngineCommandRepository.class);
        RemoteEngineGateway gateway = mock(RemoteEngineGateway.class);
        LinkedProcessService linkedProcesses = mock(LinkedProcessService.class);
        EngineCommand command = new EngineCommand("command-1", "case-1",
                EngineCommand.Type.START_PROCESS, Map.of(
                "selectionType", "ID", "processDefinitionId", "orders:9:exact",
                "processDefinitionKey", "orders", "tenantId", "",
                "planItemId", "", "variables", Map.of(),
                "correlationId", "correlation-1"), "CLAIMED", 0,
                OffsetDateTime.parse("2026-08-28T07:00:00Z"), null);
        when(commands.claimDue(50)).thenReturn(List.of(command));
        when(gateway.startProcess(any())).thenReturn(new EngineProcessRef(
                "process-42", "orders:9:exact", "orders", "case-1"));
        var dispatcher = new RemoteEngineAutoConfiguration().engineCommandDispatcher(
                commands, gateway, mock(CaseTaskRepository.class),
                mock(LinkedProcessRepository.class), linkedProcesses,
                mock(OrchestrationDeploymentReportService.class));

        dispatcher.drainOnce();

        verify(linkedProcesses).confirmStarted(eq("case-1"), eq("correlation-1"),
                eq("process-42"), eq("orders:9:exact"), eq("orders"),
                any(OffsetDateTime.class));
    }
}
