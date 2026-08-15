# Case Management Web Components

Lit-based shell and integration adapters for running the case management UI either standalone or embedded in ING portal frameworks.

The package intentionally keeps portal dependencies behind `PortalAdapter`. IRIS, IB Portal and standalone hosts provide authentication, user context and navigation differently; the Lit components consume one normalized contract.

## Build

```bash
npm install
npm run build
```

## Host Contract

Hosts should either expose an adapter explicitly or provide one of the detected globals:

- `window.ING_IRIS`
- `window.IB_PORTAL`

Without either, the standalone adapter reads a bearer token from `sessionStorage.caseManagementToken`.
