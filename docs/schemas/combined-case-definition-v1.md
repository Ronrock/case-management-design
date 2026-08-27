# Combined case-definition ZIP v1

The archive contains these normalized, relative UTF-8 paths:

- `contract.json` — required; conforms to `case-contract-v1.schema.json`, and must declare
  `orchestrationMode` explicitly (`BPMN` or `PLAN_MODEL`). The platform never infers the mode from
  which properties happen to be present: an inferred mode is how a bundle ends up with two
  process authorities. A stored definition published before the mode existed keeps its persisted
  value; a bundle being published now has to say.
- `presentation.json` — required; conforms to `presentation-manifest-v1.schema.json`.
- `**/*.bpmn` — at least one required. Exactly one process id equals the contract `key`; that is the root process.
- `**/*.dmn` — optional decisions referenced by bundled business-rule tasks.

Backslashes, absolute paths, `..`, normalized-path aliases, duplicate paths, unsupported file types,
DOCTYPE/entity declarations, more than 100 files, and more than 25 MiB of decompressed content are rejected.
Static call-activity and DMN decision references must resolve inside the orchestration release.
