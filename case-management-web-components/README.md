# Case Management Web Components

Lit-based shell and integration adapters for running the case management UI either standalone or embedded in an enterprise portal.

The package intentionally keeps host dependencies behind `PortalAdapter`. Embedded and standalone hosts provide authentication, user context and navigation differently; the Lit components consume one normalized contract.

## Build

```bash
npm install
npm run build
```

## Host Contract

Embedded hosts expose the normalized integration contract through:

- `window.CASE_MANAGEMENT_HOST`

Without that global, the standalone adapter reads a bearer token from `sessionStorage.caseManagementToken`.
