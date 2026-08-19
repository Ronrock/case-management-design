package org.casemgmt.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "casemgmt")
public class CaseManagementProperties {

    public enum EngineMode { embedded, remote }

    /** Master switch: false leaves a plain Operaton app completely untouched. */
    private boolean enabled = true;

    /** Prefix of every globally unique case id, and the CloudEvents `source`. */
    private String engineId;

    private final Engine engine = new Engine();
    private final Events events = new Events();
    private final Webhooks webhooks = new Webhooks();
    private final Schedulers schedulers = new Schedulers();
    private final WorkerPermissions workerPermissions = new WorkerPermissions();

    public static class Engine {
        private EngineMode mode = EngineMode.embedded;
        private final Remote remote = new Remote();

        public EngineMode getMode() { return mode; }
        public void setMode(EngineMode mode) { this.mode = mode; }
        public Remote getRemote() { return remote; }

        public static class Remote {
            public enum AuthMode { auto, none, basic, bearer }

            private String baseUrl;
            private AuthMode authMode = AuthMode.auto;
            private String username;
            private String password;
            private String bearerToken;

            /**
             * Connect and read timeouts for the production {@code RestClient} the remote
             * gateway uses (K1, carried from Task 12: a remote engine that is up but hung
             * otherwise blocks the calling thread forever with no exception to retry or
             * dead-letter on). Defaults match the precedent {@code WebhookDispatcher} already
             * set in this codebase — 5s connect, 10s per-request response — rather than the
             * task brief's own sketch, which hardcoded an unconfigurable 30s read timeout with
             * no property backing it at all; deviation declared in the Task 25 report.
             */
            private long connectTimeoutMs = 5_000;
            private long readTimeoutMs = 10_000;

            public String getBaseUrl() { return baseUrl; }
            public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
            public AuthMode getAuthMode() { return authMode; }
            public void setAuthMode(AuthMode authMode) { this.authMode = authMode; }
            public String getUsername() { return username; }
            public void setUsername(String username) { this.username = username; }
            public String getPassword() { return password; }
            public void setPassword(String password) { this.password = password; }
            public String getBearerToken() { return bearerToken; }
            public void setBearerToken(String bearerToken) { this.bearerToken = bearerToken; }
            public long getConnectTimeoutMs() { return connectTimeoutMs; }
            public void setConnectTimeoutMs(long v) { this.connectTimeoutMs = v; }
            public long getReadTimeoutMs() { return readTimeoutMs; }
            public void setReadTimeoutMs(long v) { this.readTimeoutMs = v; }
        }
    }

    public static class Events {
        /** No default on purpose: shipping a placeholder namespace into a broker is unfixable later. */
        private String typePrefix;

        public String getTypePrefix() { return typePrefix; }
        public void setTypePrefix(String typePrefix) { this.typePrefix = typePrefix; }
    }

    public static class Webhooks {
        /**
         * Base64-encoded AES key used to encrypt per-subscription signing secrets in the
         * database. Required when the starter is active because webhook subscriptions must
         * survive process restarts.
         */
        private String secretEncryptionKey;
        private String secretKeyId = "default";

        public String getSecretEncryptionKey() { return secretEncryptionKey; }
        public void setSecretEncryptionKey(String secretEncryptionKey) {
            this.secretEncryptionKey = secretEncryptionKey;
        }
        public String getSecretKeyId() { return secretKeyId; }
        public void setSecretKeyId(String secretKeyId) { this.secretKeyId = secretKeyId; }
    }

    public static class Schedulers {
        private boolean enabled = true;
        private long webhookIntervalMs = 5_000;
        private long engineCommandIntervalMs = 5_000;
        private long slaSweepIntervalMs = 60_000;

        /**
         * How often {@code CM_IDEMPOTENCY_KEY} is purged (final whole-branch review, Important
         * 5). Hourly, not seconds like the outbox drains: this is a retention sweep, not a work
         * queue, and its only job is to keep the table from growing one row per create forever.
         */
        private long idempotencyPurgeIntervalMs = 3_600_000;

        /**
         * Retention window for {@code CM_IDEMPOTENCY_KEY}, in hours. 48 is spec §6.4's own
         * figure. Until this sweep existed, {@code IdempotencyRepository.purgeOlderThanHours}
         * had no caller anywhere — grep found only its own Javadoc and a test comment — so §6.4
         * was documented, implemented, and never actually run.
         */
        private int idempotencyRetentionHours = 48;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getWebhookIntervalMs() { return webhookIntervalMs; }
        public void setWebhookIntervalMs(long v) { this.webhookIntervalMs = v; }
        public long getEngineCommandIntervalMs() { return engineCommandIntervalMs; }
        public void setEngineCommandIntervalMs(long v) { this.engineCommandIntervalMs = v; }
        public long getSlaSweepIntervalMs() { return slaSweepIntervalMs; }
        public void setSlaSweepIntervalMs(long v) { this.slaSweepIntervalMs = v; }
        public long getIdempotencyPurgeIntervalMs() { return idempotencyPurgeIntervalMs; }
        public void setIdempotencyPurgeIntervalMs(long v) { this.idempotencyPurgeIntervalMs = v; }
        public int getIdempotencyRetentionHours() { return idempotencyRetentionHours; }
        public void setIdempotencyRetentionHours(int v) { this.idempotencyRetentionHours = v; }
    }

    public static class WorkerPermissions {
        private boolean enabled = false;
        private String baseUrl;
        private String evaluatePath = "/permissions/evaluate";
        private String bearerToken;
        private long connectTimeoutMs = 5_000;
        private long readTimeoutMs = 5_000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getEvaluatePath() { return evaluatePath; }
        public void setEvaluatePath(String evaluatePath) { this.evaluatePath = evaluatePath; }
        public String getBearerToken() { return bearerToken; }
        public void setBearerToken(String bearerToken) { this.bearerToken = bearerToken; }
        public long getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(long connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
        public long getReadTimeoutMs() { return readTimeoutMs; }
        public void setReadTimeoutMs(long readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getEngineId() { return engineId; }
    public void setEngineId(String engineId) { this.engineId = engineId; }
    public Engine getEngine() { return engine; }
    public Events getEvents() { return events; }
    public Webhooks getWebhooks() { return webhooks; }
    public Schedulers getSchedulers() { return schedulers; }
    public WorkerPermissions getWorkerPermissions() { return workerPermissions; }
}
