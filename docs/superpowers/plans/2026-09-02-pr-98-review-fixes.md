# PR 98 Review Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve the five actionable review findings on PR #98 while preserving the standalone React demo boundary.

**Architecture:** Keep the demo capability-driven and request-driven. Add one public, tenant-scoped and version-pinned form-schema route to the already imported definition controller; make case pagination explicit; fix presentation ordering locally; and prove the documented backend/frontend startup paths in CI.

**Tech Stack:** Java 21, Spring Boot, Oracle/Liquibase, OpenAPI, React 19, TypeScript 6, Vite 8, Vitest, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-01-react-case-demo-worker-features-design.md` and the five actionable inline threads on PR #98.

## Global Constraints

- Keep `case-management-react-demo` outside the Maven reactor and published library package.
- Do not address or reply to the React-versus-Lit architectural finding.
- Continue to render only server-advertised mutation capabilities.
- Keep refresh user- or mutation-driven; do not add polling.
- Use Java 21 and Node 22 in documentation and CI.

---

### Task 1: Publish and consume a version-pinned task form route

**Files:**
- Modify: `case-management-rest/src/main/java/org/casemgmt/rest/controller/CaseDefinitionController.java`
- Modify: `case-management-poc-app/src/test/java/org/casemgmt/poc/OpenApiConformanceIT.java`
- Modify: `openapi-specs.md`
- Modify: `case-management-react-demo/src/lib/case-api-client.ts`
- Modify: `case-management-react-demo/src/lib/case-api-client.test.ts`

**Interfaces:**
- Produces: `GET /case-definitions/{key}/versions/{version}/forms/{formKey}`.
- Consumes: `CaseDefinitionRepository.findVersion(...)` and `formSchemaOfDefinition(...)`.

- [ ] Add a React client test expecting one version-pinned form request and no contract-release request.
- [ ] Run the focused Vitest file and observe the old two-request implementation fail.
- [ ] Add a PoC integration assertion that creates a case, claims its task, loads its advertised form through the exact case-definition version, and completes the task.
- [ ] Run the focused PoC test and observe the route return 404.
- [ ] Implement the controller route, OpenAPI operation, and direct React client call.
- [ ] Re-run both focused suites and confirm they pass.

### Task 2: Sort activity timestamps by instant

**Files:**
- Modify: `case-management-react-demo/src/features/activity/activity-panel.test.tsx`
- Modify: `case-management-react-demo/src/features/activity/activity-panel.tsx`

**Interfaces:**
- Produces: deterministic descending ordering by parsed epoch; missing or invalid timestamps sort after valid timestamps and then by stable entry identity.

- [ ] Add a mixed-offset regression test where equal wall-clock text represents different instants.
- [ ] Run the focused test and observe lexical ordering fail.
- [ ] Implement a bounded timestamp sort-key helper using `Date.parse`.
- [ ] Re-run the focused test and activity suite.

### Task 3: Make case paging explicit and filtering honest

**Files:**
- Modify: `case-management-react-demo/src/lib/case-api-client.ts`
- Modify: `case-management-react-demo/src/features/cases/case-demo.tsx`
- Modify: `case-management-react-demo/src/features/cases/case-demo.test.tsx`
- Modify: `case-management-react-demo/src/features/cases/case-rail.tsx`

**Interfaces:**
- Produces: `CaseApiClient.listCases(page?: number)` and a `Load more cases` control while `page + 1 < totalPages`.
- The filter is labelled `Filter loaded cases` and only searches the loaded page set.

- [ ] Add an integration test with two API pages and a result present only on page two.
- [ ] Run the case-demo suite and observe the missing load-more control fail.
- [ ] Add page state, de-duplicating append behavior, explicit load-more, and honest filter copy.
- [ ] Re-run the case-demo suite and all frontend tests.

### Task 4: Repair and verify the documented fresh startup

**Files:**
- Modify: `docker/oracle-init/01-create-schemas.sql`
- Modify: `case-management-react-demo/README.md`
- Modify: `.github/workflows/ci.yml`

**Interfaces:**
- Produces: a `cm` schema that may create Liquibase-owned triggers and a hosted clean-start smoke job.

- [ ] Add `CREATE TRIGGER` to the idempotent `cm` grant.
- [ ] State Java 21 and Node 22 prerequisites in the demo README.
- [ ] Add a CI job that starts a fresh compose Oracle database, packages and launches the PoC, and calls an authenticated REST endpoint.
- [ ] Validate the workflow syntax and exercise the startup locally when the existing Oracle environment permits it.

### Task 5: Gate the standalone frontend in hosted CI

**Files:**
- Modify: `.github/workflows/ci.yml`

**Interfaces:**
- Produces: a Node 22 job using the demo lockfile and running install, test, lint, and build.

- [ ] Add the isolated frontend job with npm dependency caching.
- [ ] Run `npm ci`, `npm test`, `npm run lint`, and `npm run build` locally.
- [ ] Run `git diff --check` and inspect the full branch diff.
- [ ] Commit and push the fixes to `codex/react-case-demo`.
- [ ] Reply to and resolve only the five actionable review threads with exact verification evidence.
