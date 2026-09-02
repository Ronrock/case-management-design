# Standalone React case management demo

This small React application showcases the case management library through its public REST API. It is intentionally independent: the directory is not part of the Maven reactor, an npm workspace, or the published library package. It has no mock-data mode and requires the live PoC backend.

## Start the demo

Prerequisites:

- Docker with Docker Compose;
- JDK 21; and
- Node.js 22 with npm.

From the repository root, start the backend in one terminal:

```bash
docker compose up -d oracle
export CASEMGMT_WEBHOOK_SECRET_ENCRYPTION_KEY="$(openssl rand -base64 32)"
./mvnw -B -DskipTests package
java -jar case-management-poc-app/target/case-management-poc-app-0.1.0-SNAPSHOT.jar
```

Then start the standalone frontend in a second terminal:

```bash
cd case-management-react-demo
npm ci
VITE_CASE_API_PROXY_TARGET=http://localhost:8080 npm run dev
```

Open the URL printed by Vite and connect with the tutorial caseworker credentials `alice` / `alice`. The username and password stay in browser memory only; they are not persisted.

The worker showcase is entirely driven by capabilities returned by the live REST API. It provides:

- a request-driven **My Work** inbox, grouped into assigned and claimable work;
- a shared case workspace for facts, current tasks, and the observation spine;
- case-level update, close, and cancel controls only when the case advertises them;
- every SLA clock, including advertised pause and resume controls;
- a chronological activity view combining system events and internal comments; and
- advertised task claim and completion actions, with completion fields loaded from the pinned case contract.

Refresh controls issue requests only when selected; the demo does not poll in the background. Because actions and comments depend on the current case state and caller permissions, controls that the backend does not advertise are intentionally absent.

## Configuration

- `VITE_CASE_API_BASE_URL` sets the browser-visible REST base and defaults to `/case-api/v2`.
- `VITE_CASE_API_PROXY_TARGET` sets the Vite development proxy target and defaults to `http://localhost:8080`.
- Production hosting must reverse-proxy `/case-api` to the REST backend. HTTP Basic is intended for this local demonstration; use the deployment's normal authentication controls outside the PoC.

## Checks and production preview

```bash
npm test
npm run lint
npm run build
npm run preview
```
