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
    private final Schedulers schedulers = new Schedulers();

    public static class Engine {
        private EngineMode mode = EngineMode.embedded;
        private final Remote remote = new Remote();

        public EngineMode getMode() { return mode; }
        public void setMode(EngineMode mode) { this.mode = mode; }
        public Remote getRemote() { return remote; }

        public static class Remote {
            private String baseUrl;
            private String username;
            private String password;

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
            public String getUsername() { return username; }
            public void setUsername(String username) { this.username = username; }
            public String getPassword() { return password; }
            public void setPassword(String password) { this.password = password; }
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

    public static class Schedulers {
        private boolean enabled = true;
        private long webhookIntervalMs = 5_000;
        private long engineCommandIntervalMs = 5_000;
        private long slaSweepIntervalMs = 60_000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getWebhookIntervalMs() { return webhookIntervalMs; }
        public void setWebhookIntervalMs(long v) { this.webhookIntervalMs = v; }
        public long getEngineCommandIntervalMs() { return engineCommandIntervalMs; }
        public void setEngineCommandIntervalMs(long v) { this.engineCommandIntervalMs = v; }
        public long getSlaSweepIntervalMs() { return slaSweepIntervalMs; }
        public void setSlaSweepIntervalMs(long v) { this.slaSweepIntervalMs = v; }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getEngineId() { return engineId; }
    public void setEngineId(String engineId) { this.engineId = engineId; }
    public Engine getEngine() { return engine; }
    public Events getEvents() { return events; }
    public Schedulers getSchedulers() { return schedulers; }
}
