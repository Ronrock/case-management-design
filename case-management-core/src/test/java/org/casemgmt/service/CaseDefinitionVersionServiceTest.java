package org.casemgmt.service;

import org.casemgmt.domain.CaseDefinition;
import org.casemgmt.orchestration.OrchestrationMode;
import org.casemgmt.release.CaseDefinitionRelease;
import org.casemgmt.release.CaseDefinitionVersionBinding;
import org.casemgmt.release.ReleaseKind;
import org.casemgmt.repo.CaseDefinitionReleaseRepository;
import org.casemgmt.repo.CaseDefinitionVersionBindingRepository;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CaseDefinitionVersionServiceTest {

    @Test
    void bindsExactReleaseReferencesIntoABpmnDefinitionVersion() {
        CaseDefinitionReleaseRepository releases = mock(CaseDefinitionReleaseRepository.class);
        CaseDefinitionVersionBindingRepository bindings =
                mock(CaseDefinitionVersionBindingRepository.class);
        CaseDefinitionService definitions = mock(CaseDefinitionService.class);
        when(releases.require("orch-1", "t1")).thenReturn(release("orch-1", ReleaseKind.ORCHESTRATION,
                """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <process id="sample-case" isExecutable="true"/>
                </definitions>""", "1", "application/bpmn+xml"));
        when(releases.require("contract-1", "t1")).thenReturn(release("contract-1", ReleaseKind.CONTRACT,
                "{\"key\":\"sample-case\",\"roles\":[],\"forms\":{},\"fields\":{}}", "2"));
        when(releases.require("presentation-1", "t1")).thenReturn(release("presentation-1",
                ReleaseKind.PRESENTATION, "{\"version\":\"1.0\",\"sections\":[]}", "3"));
        CaseDefinition definition = new CaseDefinition("t1:sample-case:1", "sample-case", 1,
                "Sample case", "t1", null, null, List.of(), List.of(), Map.of(), List.of(),
                OrchestrationMode.BPMN, OffsetDateTime.now(), "alice");
        when(definitions.deployBpmn(eq("sample-case"), any(), eq("alice"), eq("t1")))
                .thenReturn(definition);

        CaseDefinitionVersionBinding bound = new CaseDefinitionVersionService(
                releases, bindings, definitions).bind("sample-case", "t1", "orch-1",
                "contract-1", "presentation-1", "alice");

        assertThat(bound.caseDefinitionId()).isEqualTo("t1:sample-case:1");
        assertThat(bound.orchestrationReleaseId()).isEqualTo("orch-1");
        verify(bindings).insert(bound);
    }

    @Test
    void rejectsCandidateGroupNotDeclaredByContract() {
        CaseDefinitionReleaseRepository releases = mock(CaseDefinitionReleaseRepository.class);
        when(releases.require("orch-1", "t1")).thenReturn(release("orch-1",
                ReleaseKind.ORCHESTRATION, """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:operaton="http://operaton.org/schema/1.0/bpmn">
                  <process id="sample-case" isExecutable="true">
                    <userTask id="review" operaton:candidateGroups="secret-reviewers"/>
                  </process>
                </definitions>""", "1", "application/bpmn+xml"));
        when(releases.require("contract-1", "t1")).thenReturn(release("contract-1",
                ReleaseKind.CONTRACT,
                "{\"key\":\"sample-case\",\"roles\":[],\"candidateGroups\":[\"handlers\"],"
                        + "\"forms\":{},\"fields\":{}}", "2"));
        when(releases.require("presentation-1", "t1")).thenReturn(release("presentation-1",
                ReleaseKind.PRESENTATION, "{\"version\":\"1.0\",\"sections\":[]}", "3"));

        assertThatThrownBy(() -> new CaseDefinitionVersionService(releases,
                mock(CaseDefinitionVersionBindingRepository.class),
                mock(CaseDefinitionService.class)).bind("sample-case", "t1", "orch-1",
                "contract-1", "presentation-1", "alice"))
                .hasMessageContaining("undeclared candidate group 'secret-reviewers'");
    }

    private static CaseDefinitionRelease release(String id, ReleaseKind kind, String content,
                                                  String digestSeed) {
        return release(id, kind, content, digestSeed, "application/json");
    }

    private static CaseDefinitionRelease release(String id, ReleaseKind kind, String content,
                                                  String digestSeed, String mediaType) {
        return CaseDefinitionRelease.stored(id, "sample-case", "t1", kind, mediaType,
                content.getBytes(StandardCharsets.UTF_8), digestSeed.repeat(64), "alice");
    }
}
