package org.casemgmt.starter;

import org.casemgmt.domain.CaseInstance;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.projection.CaseCompletionPublisher;
import org.casemgmt.repo.CaseRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CaseCompletionPublisherModeTest {

    @Test
    void remoteModeKeepsTheLegacyCompletionPublisherUntilRemoteIngestionMigrates() {
        CaseRepository cases = mock(CaseRepository.class);
        EventPublisher events = mock(EventPublisher.class);
        CaseInstance instance = mock(CaseInstance.class);
        when(instance.tenantId()).thenReturn("tenant-a");
        when(cases.require("case-1")).thenReturn(instance);
        when(events.engineId()).thenReturn("engine-a");
        CaseManagementProperties properties = new CaseManagementProperties();
        properties.getEngine().setMode(CaseManagementProperties.EngineMode.remote);

        CaseCompletionPublisher publisher = new CaseManagementRepositoryConfiguration()
                .caseCompletionPublisher(cases, events, properties);
        publisher.publish("case-1", "COMPLETED",
                OffsetDateTime.parse("2026-08-28T12:00:00Z"));

        verify(events).publish(any());
        verify(events).audit(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void embeddedModeDisablesTheLegacyCompletionPublisher() {
        CaseRepository cases = mock(CaseRepository.class);
        EventPublisher events = mock(EventPublisher.class);
        CaseManagementProperties properties = new CaseManagementProperties();
        properties.getEngine().setMode(CaseManagementProperties.EngineMode.embedded);

        CaseCompletionPublisher publisher = new CaseManagementRepositoryConfiguration()
                .caseCompletionPublisher(cases, events, properties);
        publisher.publish("case-1", "COMPLETED",
                OffsetDateTime.parse("2026-08-28T12:00:00Z"));

        verify(cases, never()).require(any());
        verify(events, never()).publish(any());
    }
}
