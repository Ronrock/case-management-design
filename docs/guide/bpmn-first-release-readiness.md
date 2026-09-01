# BPMN-first release readiness

Use this checklist for a release candidate. It records proof, not optimism: every checked item
needs the command output, CI run, or incident record that supports it.

## Release checklist

- [ ] The candidate was built from a clean checkout with Java 21 and `./mvnw -B clean install`.
- [ ] Surefire reports show discovered tests in every relevant module; no required suite is
  disabled or filtered out.
- [ ] Docker/Testcontainers started Oracle Free 23 and the fresh schema migration, historical
  migration rehearsal, and Oracle-backed lifecycle tests passed.
- [ ] BPMN-only migration preflight was run against a production-shaped restored database. There
  are no retained `PLAN_MODEL` definitions/bindings, or the upgrade has been deliberately stopped
  for an approved archive/removal decision.
- [ ] Exact-release acceptance proof covers a case pinned to BPMN v1 after v2 is available.
- [ ] Authorization proof covers tenant separation and denies unapproved task and ad-hoc actions.
- [ ] Remote proof covers a lost response/replay, concurrent requests, poisoned/retried inbox
  evidence, and 499/500/501/1,000/1,201 history rows including equal timestamps.
- [ ] Root completion proof shows canonical projection, audit/event output, and applicable SLA
  clocks terminalise in the same lifecycle transaction.
- [ ] The modeler templates validate against the release validator and embedded engine; web
  components build and test when those directories are included in the release.
- [ ] CI reports contain no credentials, bearer tokens, webhook secrets, or customer payloads.
- [ ] The on-call team has read [Operations](operations.md), owns remote-engine credentials, and
  knows the supported dead-letter retry and case-cancellation endpoints.

## Evidence map

| Concern | Focused evidence |
|---|---|
| Exact pinned BPMN start | `BpmnFirstAcceptanceIT`, `BpmnOrchestrationTest`, engine embedded/remote exact-start tests |
| BPMN-only publication authority | `BpmnFirstSecurityIT`, `JsonSchemaCaseContractValidatorTest`, `LegacyMigrationBoundaryTest` |
| Restart/replay-safe remote fact | `BpmnFirstFailureRecoveryIT`, `RemoteObservationInboxWorkerReconciliationTest` |
| Historical migration safety | `BpmnFirstMigrationIT`, `Ws2HistoricalMigrationRehearsalTest`, `SchemaMigrationTest` |
| High-volume remote paging | `BpmnFirstRemoteHighVolumeIT`, `RemoteObservationPollerHttpTest` |
| Root completion and SLA outcome | `SlaLifecycleServiceTest`, `EngineObservationTransactionalIntegrationTest` |
| Task/ad-hoc operation authority | `CaseTaskOperationConcurrencyTest`, `RemoteTaskOperationHttpTest`, `RemoteAdHocActionHttpTest` |

## Required clean-room commands

```bash
./mvnw -B clean install
(cd modeler-templates && npm ci && npm test)
(cd case-management-web-components && npm ci && npm run build && npm test)
```

The final two commands apply because this repository contains both packages. If a downstream
distribution omits either directory, record that fact in the release evidence rather than silently
skipping it.

For recovery actions, use the procedures in [Operations](operations.md#bpmn-first-operational-recovery).
For caller-facing request semantics, use the [API cookbook](api-cookbook.md).
