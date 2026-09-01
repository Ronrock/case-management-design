package org.casemgmt.engine;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineCommandLegacyMigrationTest {

    private static final OffsetDateTime MIGRATED_AT = OffsetDateTime.parse(
            "2026-08-28T14:00:00.123456789+02:00");
    private static final OffsetDateTime CANONICAL_MIGRATED_AT = OffsetDateTime.parse(
            "2026-08-28T12:00:00.123456Z");
    private static final EngineCommandPolicy POLICY = new EngineCommandPolicy(Clock.fixed(
            Instant.parse("2026-08-28T13:00:00Z"), ZoneOffset.UTC));

    @ParameterizedTest
    @EnumSource(EngineCommand.Type.class)
    void legacyDoneRetainsTruthfulBoundProvenanceForEveryCommandType(EngineCommand.Type type) {
        var command = command(type);
        var migrated = migrate(command, type, 2);

        assertThat(migrated.status()).isEqualTo(EngineCommandStatus.CONFIRMED);
        assertThat(migrated.terminalConfirmation()).isNull();
        assertThat(migrated.legacyConfirmation().source())
                .isEqualTo(CommandDispatchOutcome.ConfirmationSource.LEGACY_MIGRATION);
        assertThat(migrated.legacyConfirmation().oldStatus())
                .isEqualTo(EngineCommandPolicy.LegacyCommandStatus.DONE);
        assertThat(migrated.legacyConfirmation().tenantId()).isEqualTo(command.tenantId());
        assertThat(migrated.legacyConfirmation().operationId()).isEqualTo(command.operationId());
        assertThat(migrated.legacyConfirmation().commandId()).isEqualTo(command.commandId());
        assertThat(migrated.legacyConfirmation().commandType()).isEqualTo(type);
        assertThat(migrated.legacyConfirmation().expectedTargetIdentity())
                .isEqualTo(command.expectedTargetIdentity());
        assertThat(migrated.legacyConfirmation().legacyRowId())
                .isEqualTo("legacy-row:" + type.name().toLowerCase());
        assertThat(migrated.legacyConfirmation().migrationReference())
                .isEqualTo("migration:ws4-task2");
        assertThat(migrated.legacyConfirmation().legacyFailureCount()).isEqualTo(2);
        assertThat(migrated.legacyConfirmation().migratedAt())
                .isEqualTo(CANONICAL_MIGRATED_AT);
        assertThat(migrated.totalDispatchAttempts()).isEqualTo(3);
        assertThat(migrated.automaticAttemptsInBudget()).isEqualTo(3);
        assertThat(migrated.decidedAt()).isEqualTo(CANONICAL_MIGRATED_AT);
        assertThatCodeCanRehydrate(command, migrated);
        assertThat(migrate(command, type, 2)).isEqualTo(migrated);

        var live = liveEvidence(type);
        if (type == EngineCommand.Type.CLAIM_TASK
                || type == EngineCommand.Type.COMPLETE_TASK
                || type == EngineCommand.Type.CANCEL_PROCESS) {
            assertThat(POLICY.transition(new EngineCommandPolicy.CommandState(command, migrated),
                    CommandDispatchOutcome.observation(live))).isEqualTo(migrated);
        } else {
            assertThatThrownBy(() -> POLICY.transition(
                    new EngineCommandPolicy.CommandState(command, migrated),
                    CommandDispatchOutcome.observation(live)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("legacy");
        }
    }

    @ParameterizedTest
    @EnumSource(EngineCommand.Type.class)
    void ordinaryDispatchEvidenceCannotForgeTheLegacyMigrationSource(EngineCommand.Type type) {
        var command = command(type);
        assertThatThrownBy(() -> new CommandDispatchOutcome.ConfirmationEvidence(
                command.tenantId(), command.operationId(), command.commandId(), type,
                command.expectedTargetIdentity(), remoteIdentity(type), terminalState(type),
                CommandDispatchOutcome.ConfirmationSource.LEGACY_MIGRATION,
                "evidence:forged-legacy"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("legacy");
    }

    @ParameterizedTest
    @EnumSource(EngineCommand.Type.class)
    void legacyDoneFailureCountTranslatesToTheStartedDispatchLifetime(
            EngineCommand.Type type) {
        assertThat(migrate(command(type), type, 0).totalDispatchAttempts()).isEqualTo(1);
        assertThat(migrate(command(type), type, 0).automaticAttemptsInBudget()).isEqualTo(1);
        assertThat(migrate(command(type), type, 5).totalDispatchAttempts()).isEqualTo(6);
        assertThat(migrate(command(type), type, 5).automaticAttemptsInBudget()).isEqualTo(6);

        assertThatThrownBy(() -> migrate(command(type), type, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failure count");
        assertThatThrownBy(() -> migrate(command(type), type, 6))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failure count");
    }

    @ParameterizedTest
    @EnumSource(EngineCommand.Type.class)
    void legacyMigrationConstructionIsNotPartOfThePublicRuntimeApi(EngineCommand.Type type) {
        assertThat(Arrays.stream(EngineCommandPolicy.class.getMethods())
                .map(method -> method.getName()))
                .doesNotContain("migrateLegacyDone");
        assertThat(Modifier.isPublic(LegacyDoneCommandMigration.class.getModifiers())).isFalse();
        assertThat(Arrays.stream(LegacyDoneCommandMigration.class.getDeclaredConstructors())
                .noneMatch(constructor -> Modifier.isPublic(constructor.getModifiers()))).isTrue();
        assertThat(Modifier.isPublic(
                LegacyDoneCommandMigration.LegacyDoneRow.class.getModifiers())).isFalse();
        assertThat(Arrays.stream(
                LegacyDoneCommandMigration.LegacyDoneRow.class.getDeclaredConstructors())
                .noneMatch(constructor -> Modifier.isPublic(constructor.getModifiers()))).isTrue();
        assertThat(Modifier.isPublic(
                EngineCommandPolicy.LegacyConfirmationEvidence.class.getModifiers())).isFalse();
        assertThat(Modifier.isPublic(
                EngineCommandPolicy.LegacyCommandStatus.class.getModifiers())).isFalse();

        var migrated = migrate(command(type), type, 0);
        assertThat(migrated.legacyConfirmation().source())
                .isEqualTo(CommandDispatchOutcome.ConfirmationSource.LEGACY_MIGRATION);
    }

    @ParameterizedTest
    @EnumSource(EngineCommand.Type.class)
    void legacyEvidenceBindsMigrationTimeAndRawFailureCount(EngineCommand.Type type) {
        var migrated = migrate(command(type), type, 2);

        assertThatThrownBy(() -> new EngineCommandPolicy.Decision(
                EngineCommandStatus.CONFIRMED, MIGRATED_AT.plusSeconds(1), null, null, null,
                3, 3, 0, false, null, migrated.legacyConfirmation(),
                null, null, EngineCommandPolicy.ActionLedgerSummary.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("migration time");
        assertThatThrownBy(() -> new EngineCommandPolicy.Decision(
                EngineCommandStatus.CONFIRMED, MIGRATED_AT, null, null, null,
                4, 4, 0, false, null, migrated.legacyConfirmation(),
                null, null, EngineCommandPolicy.ActionLedgerSummary.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failure count");
    }

    @ParameterizedTest
    @EnumSource(EngineCommand.Type.class)
    void legacyDoneCannotBeRehydratedAgainstAnotherCommandBinding(EngineCommand.Type type) {
        var command = command(type);
        var migrated = migrate(command, type, 2);
        var wrongTenant = new EngineCommandPolicy.CommandContext(
                "other-tenant", command.operationId(), command.commandId(), type, target(type));

        assertThatThrownBy(() -> new EngineCommandPolicy.CommandState(wrongTenant, migrated))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant");
    }

    private static void assertThatCodeCanRehydrate(
            EngineCommandPolicy.CommandContext command, EngineCommandPolicy.Decision decision) {
        assertThat(new EngineCommandPolicy.CommandState(command, decision).committedDecision())
                .isEqualTo(decision);
    }

    private static EngineCommandPolicy.Decision migrate(
            EngineCommandPolicy.CommandContext command,
            EngineCommand.Type type,
            int legacyFailureCount) {
        return LegacyDoneCommandMigration.migrate(new LegacyDoneCommandMigration.LegacyDoneRow(
                command, "legacy-row:" + type.name().toLowerCase(),
                "migration:ws4-task2", MIGRATED_AT, legacyFailureCount));
    }

    private static EngineCommandPolicy.CommandContext command(EngineCommand.Type type) {
        return new EngineCommandPolicy.CommandContext(
                "tenant-a", "operation-a", "command-a", type, target(type));
    }

    private static CommandDispatchOutcome.ConfirmationEvidence liveEvidence(
            EngineCommand.Type type) {
        var command = command(type);
        return new CommandDispatchOutcome.ConfirmationEvidence(
                command.tenantId(), command.operationId(), command.commandId(), type,
                command.expectedTargetIdentity(), remoteIdentity(type), terminalState(type),
                CommandDispatchOutcome.ConfirmationSource.OBSERVATION, "evidence:live");
    }

    private static String target(EngineCommand.Type type) {
        return switch (type) {
            case CREATE_TASK -> "plan-item-a";
            case CLAIM_TASK, COMPLETE_TASK -> "task-a";
            case START_PROCESS -> "process-definition-a";
            case CANCEL_PROCESS -> "process-instance-a";
            case DEPLOY_ORCHESTRATION -> "release-a";
            case CORRELATE_MESSAGE -> "message-correlation-a";
        };
    }

    private static String remoteIdentity(EngineCommand.Type type) {
        return switch (type) {
            case CLAIM_TASK, COMPLETE_TASK -> "task-a";
            case CANCEL_PROCESS -> "process-instance-a";
            default -> "remote-result-a";
        };
    }

    private static CommandDispatchOutcome.RemoteState terminalState(EngineCommand.Type type) {
        return switch (type) {
            case CREATE_TASK -> CommandDispatchOutcome.RemoteState.TASK_CREATED;
            case CLAIM_TASK -> CommandDispatchOutcome.RemoteState.TASK_CLAIMED;
            case COMPLETE_TASK -> CommandDispatchOutcome.RemoteState.TASK_COMPLETED;
            case START_PROCESS -> CommandDispatchOutcome.RemoteState.PROCESS_STARTED;
            case CANCEL_PROCESS -> CommandDispatchOutcome.RemoteState.PROCESS_TERMINATED;
            case DEPLOY_ORCHESTRATION ->
                    CommandDispatchOutcome.RemoteState.ORCHESTRATION_DEPLOYED;
            case CORRELATE_MESSAGE -> CommandDispatchOutcome.RemoteState.MESSAGE_CORRELATED;
        };
    }
}
