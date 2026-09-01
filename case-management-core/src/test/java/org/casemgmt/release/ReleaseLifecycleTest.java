package org.casemgmt.release;

import org.casemgmt.orchestration.EngineDeploymentIdentity;
import org.casemgmt.orchestration.OrchestrationMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The release and binding lifecycles (Workstream 2, Task 1).
 *
 * <p>Before this, a release was constructed straight into {@code ACTIVE} and a binding inferred
 * its usability from having been written at all. Two consequences followed, and both are what
 * these tests exist to prevent:
 *
 * <ol>
 *   <li><b>A release was selectable before its orchestration was known to be deployed.</b>
 *       "Stored" and "usable" were the same fact, so a row existed in a runnable-looking state
 *       while the engine deployment behind it had not been verified — or, in remote mode, had
 *       not happened yet.</li>
 *   <li><b>Nothing could be withdrawn.</b> With no {@code RETIRED} state, taking a release out
 *       of service meant deleting it or leaving running cases pointing at a row that had to stay
 *       pretended-active.</li>
 * </ol>
 *
 * <p>The state machine is therefore explicit and guarded rather than advisory: an illegal move
 * throws where it is attempted, so a caller cannot quietly write a state the rest of the platform
 * does not expect to read.
 */
class ReleaseLifecycleTest {

    // ------------------------------------------------------- release transitions

    /**
     * Orchestration is the long path: it is not usable until an engine deployment has been
     * verified, so it passes through {@code DEPLOYING}. Contract and presentation releases have
     * nothing to deploy and go active straight from validation.
     */
    @ParameterizedTest(name = "{0} -> {1} is legal")
    @CsvSource({
            "DRAFT,      VALIDATED",
            "DRAFT,      FAILED",
            "VALIDATED,  DEPLOYING",
            "VALIDATED,  ACTIVE",
            "VALIDATED,  FAILED",
            "DEPLOYING,  ACTIVE",
            "DEPLOYING,  FAILED",
            "ACTIVE,     RETIRED"
    })
    void allowsALegalReleaseTransition(ReleaseStatus from, ReleaseStatus to) {
        assertThat(from.canTransitionTo(to)).isTrue();
        assertThat(from.transitionTo(to)).isEqualTo(to);
    }

    @ParameterizedTest(name = "{0} -> {1} is rejected")
    @CsvSource({
            // Skipping validation, or deploying something never validated.
            "DRAFT,      ACTIVE",
            "DRAFT,      DEPLOYING",
            "DRAFT,      RETIRED",
            // Going back up the path.
            "ACTIVE,     DEPLOYING",
            "ACTIVE,     VALIDATED",
            "DEPLOYING,  VALIDATED",
            "VALIDATED,  DRAFT",
            // Terminal states stay terminal: a failed or withdrawn release is re-published, not
            // revived in place, so its id keeps meaning one thing.
            "FAILED,     ACTIVE",
            "FAILED,     DRAFT",
            "RETIRED,    ACTIVE",
            "RETIRED,    DEPLOYING"
    })
    void rejectsAnIllegalReleaseTransition(ReleaseStatus from, ReleaseStatus to) {
        assertThat(from.canTransitionTo(to)).isFalse();
        assertThatThrownBy(() -> from.transitionTo(to))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(from.name())
                .hasMessageContaining(to.name());
    }

    @ParameterizedTest
    @EnumSource(ReleaseStatus.class)
    void treatsEveryStateAsIllegalToRepeat(ReleaseStatus status) {
        assertThat(status.canTransitionTo(status)).isFalse();
    }

    @Test
    void onlyActiveIsUsable() {
        assertThat(ReleaseStatus.ACTIVE.isActive()).isTrue();
        assertThat(ReleaseStatus.DRAFT.isActive()).isFalse();
        assertThat(ReleaseStatus.VALIDATED.isActive()).isFalse();
        assertThat(ReleaseStatus.DEPLOYING.isActive()).isFalse();
        assertThat(ReleaseStatus.FAILED.isActive()).isFalse();
        assertThat(ReleaseStatus.RETIRED.isActive()).isFalse();
    }

    @Test
    void failedAndRetiredAreTerminal() {
        assertThat(ReleaseStatus.FAILED.isTerminal()).isTrue();
        assertThat(ReleaseStatus.RETIRED.isTerminal()).isTrue();
        assertThat(ReleaseStatus.ACTIVE.isTerminal()).isFalse();
        assertThat(ReleaseStatus.DEPLOYING.isTerminal()).isFalse();
    }

    // ------------------------------------------------------- binding transitions

    @ParameterizedTest(name = "binding {0} -> {1} is legal")
    @CsvSource({
            "DRAFT,   ACTIVE",
            "DRAFT,   FAILED",
            "ACTIVE,  RETIRED"
    })
    void allowsALegalBindingTransition(BindingStatus from, BindingStatus to) {
        assertThat(from.canTransitionTo(to)).isTrue();
        assertThat(from.transitionTo(to)).isEqualTo(to);
    }

    @ParameterizedTest(name = "binding {0} -> {1} is rejected")
    @CsvSource({
            "ACTIVE,   DRAFT",
            "RETIRED,  ACTIVE",
            "FAILED,   ACTIVE",
            "DRAFT,    RETIRED"
    })
    void rejectsAnIllegalBindingTransition(BindingStatus from, BindingStatus to) {
        assertThat(from.canTransitionTo(to)).isFalse();
        assertThatThrownBy(() -> from.transitionTo(to))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void onlyAnActiveBindingIsSelectable() {
        assertThat(BindingStatus.ACTIVE.isActive()).isTrue();
        assertThat(BindingStatus.DRAFT.isActive()).isFalse();
        assertThat(BindingStatus.RETIRED.isActive()).isFalse();
        assertThat(BindingStatus.FAILED.isActive()).isFalse();
    }

    // ---------------------------------------------------- construction is not activation

    /**
     * The behavioural core of this task. A newly published release starts at {@code DRAFT}:
     * writing a row is not a claim that the artifact behind it is usable, and for orchestration
     * that claim cannot honestly be made until the engine deployment is verified (Task 3).
     */
    @Test
    void aNewlyPublishedReleaseIsNotActive() {
        CaseDefinitionRelease release = CaseDefinitionRelease.draft("r-1", "sample-case", "t1",
                ReleaseKind.ORCHESTRATION, "application/bpmn+xml", new byte[]{1}, "sha", "alice");

        assertThat(release.status()).isEqualTo(ReleaseStatus.DRAFT);
        assertThat(release.status().isActive()).isFalse();
    }

    @Test
    void aReleaseCarriesItsStateForwardThroughGuardedTransitions() {
        CaseDefinitionRelease draft = CaseDefinitionRelease.draft("r-1", "sample-case", "t1",
                ReleaseKind.CONTRACT, "application/json", new byte[]{1}, "sha", "alice");

        CaseDefinitionRelease active = draft
                .withStatus(ReleaseStatus.VALIDATED)
                .withStatus(ReleaseStatus.ACTIVE);

        assertThat(active.status()).isEqualTo(ReleaseStatus.ACTIVE);
        assertThat(active.id()).isEqualTo("r-1");
        assertThat(active.sha256()).isEqualTo("sha");
    }

    @Test
    void aReleaseRefusesToSkipStraightToActive() {
        CaseDefinitionRelease draft = CaseDefinitionRelease.draft("r-1", "sample-case", "t1",
                ReleaseKind.ORCHESTRATION, "application/bpmn+xml", new byte[]{1}, "sha", "alice");

        assertThatThrownBy(() -> draft.withStatus(ReleaseStatus.ACTIVE))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void bindingActivationRejectsANonActivePinnedConstituent() {
        EngineDeploymentIdentity identity = identity();
        CaseDefinitionVersionBinding draft = draftBinding();
        CaseDefinitionRelease orchestration = release(
                "orch-1", ReleaseKind.ORCHESTRATION, ReleaseStatus.ACTIVE, "o", identity);
        CaseDefinitionRelease contract = release(
                "contract-1", ReleaseKind.CONTRACT, ReleaseStatus.RETIRED, "c", null);
        CaseDefinitionRelease presentation = release(
                "presentation-1", ReleaseKind.PRESENTATION, ReleaseStatus.ACTIVE, "p", null);

        assertThatThrownBy(() -> draft.activate(
                orchestration, contract, presentation, identity, OffsetDateTime.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("contract-1")
                .hasMessageContaining("RETIRED")
                .hasMessageContaining("ACTIVE");
    }

    @Test
    void bindingActivationRejectsAnIdentityForAnotherKeyOrTenant() {
        CaseDefinitionVersionBinding draft = draftBinding();
        EngineDeploymentIdentity approved = identity();
        EngineDeploymentIdentity reported = new EngineDeploymentIdentity(
                "deployment-2", "other:1:5", "other", 1, "t2");

        assertThatThrownBy(() -> draft.activate(
                release("orch-1", ReleaseKind.ORCHESTRATION, ReleaseStatus.ACTIVE, "o", approved),
                release("contract-1", ReleaseKind.CONTRACT, ReleaseStatus.ACTIVE, "c", null),
                release("presentation-1", ReleaseKind.PRESENTATION, ReleaseStatus.ACTIVE, "p", null),
                reported, OffsetDateTime.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exact engine identity");
    }

    @Test
    void activationPreservesTheImmutableBindingAndPinsTheApprovedIdentity() {
        CaseDefinitionVersionBinding draft = draftBinding();
        EngineDeploymentIdentity identity = identity();
        OffsetDateTime activatedAt = OffsetDateTime.parse("2026-08-28T09:00:00Z");

        CaseDefinitionVersionBinding active = draft.activate(
                release("orch-1", ReleaseKind.ORCHESTRATION, ReleaseStatus.ACTIVE, "o", identity),
                release("contract-1", ReleaseKind.CONTRACT, ReleaseStatus.ACTIVE, "c", null),
                release("presentation-1", ReleaseKind.PRESENTATION, ReleaseStatus.ACTIVE, "p", null),
                identity, activatedAt);

        assertThat(active.status()).isEqualTo(BindingStatus.ACTIVE);
        assertThat(active.engineIdentity()).isEqualTo(identity);
        assertThat(active.activatedAt()).isEqualTo(activatedAt);
        assertThat(active.caseDefinitionId()).isEqualTo(draft.caseDefinitionId());
        assertThat(active.orchestrationReleaseId()).isEqualTo(draft.orchestrationReleaseId());
        assertThat(active.orchestrationSha256()).isEqualTo(draft.orchestrationSha256());
        assertThat(active.contractReleaseId()).isEqualTo(draft.contractReleaseId());
        assertThat(active.contractSha256()).isEqualTo(draft.contractSha256());
        assertThat(active.presentationReleaseId()).isEqualTo(draft.presentationReleaseId());
        assertThat(active.presentationSha256()).isEqualTo(draft.presentationSha256());
    }

    private static CaseDefinitionVersionBinding draftBinding() {
        return new CaseDefinitionVersionBinding(
                "t1:sample-case:1", "sample-case", "t1", "orch-1", "o".repeat(64),
                "contract-1", "c".repeat(64), "presentation-1", "p".repeat(64),
                ReleaseStatus.DEPLOYING, OrchestrationMode.BPMN, BindingStatus.DRAFT,
                null, null, OffsetDateTime.now(), null, null, "alice");
    }

    private static CaseDefinitionRelease release(
            String id, ReleaseKind kind, ReleaseStatus status, String digestSeed,
            EngineDeploymentIdentity identity) {
        return CaseDefinitionRelease.storedWithEngineIdentity(
                id, "sample-case", "t1", kind, "application/json",
                "{}".getBytes(StandardCharsets.UTF_8), digestSeed.repeat(64), status,
                identity, null, "alice");
    }

    private static EngineDeploymentIdentity identity() {
        return new EngineDeploymentIdentity(
                "deployment-1", "sample-case:1:100", "sample-case", 1, "t1");
    }
}
