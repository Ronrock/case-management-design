package org.casemgmt.poc;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "casemgmt.security")
public class PocSecurityProperties {

    public enum Mode { basic, oidc }

    private Mode mode = Mode.basic;
    private String engineIntegrationPrincipal = "admin";
    private final Oidc oidc = new Oidc();

    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }
    public String getEngineIntegrationPrincipal() { return engineIntegrationPrincipal; }
    public void setEngineIntegrationPrincipal(String engineIntegrationPrincipal) {
        this.engineIntegrationPrincipal = engineIntegrationPrincipal;
    }
    public Oidc getOidc() { return oidc; }

    public static class Oidc {
        private String principalClaim = "sub";
        private String tenantClaim = "tenant";
        private String groupsClaim = "groups";
        private String workerPermissionsClaim = "worker_permissions";
        private String workerPermissionAuthorityPrefix = "";
        private String enginePermissionsClaim = "engine_permissions";
        private String enginePermissionAuthorityPrefix = "engine:";

        public String getPrincipalClaim() { return principalClaim; }
        public void setPrincipalClaim(String principalClaim) { this.principalClaim = principalClaim; }
        public String getTenantClaim() { return tenantClaim; }
        public void setTenantClaim(String tenantClaim) { this.tenantClaim = tenantClaim; }
        public String getGroupsClaim() { return groupsClaim; }
        public void setGroupsClaim(String groupsClaim) { this.groupsClaim = groupsClaim; }
        public String getWorkerPermissionsClaim() { return workerPermissionsClaim; }
        public void setWorkerPermissionsClaim(String workerPermissionsClaim) {
            this.workerPermissionsClaim = workerPermissionsClaim;
        }
        public String getWorkerPermissionAuthorityPrefix() { return workerPermissionAuthorityPrefix; }
        public void setWorkerPermissionAuthorityPrefix(String workerPermissionAuthorityPrefix) {
            this.workerPermissionAuthorityPrefix = workerPermissionAuthorityPrefix;
        }
        public String getEnginePermissionsClaim() { return enginePermissionsClaim; }
        public void setEnginePermissionsClaim(String enginePermissionsClaim) {
            this.enginePermissionsClaim = enginePermissionsClaim;
        }
        public String getEnginePermissionAuthorityPrefix() { return enginePermissionAuthorityPrefix; }
        public void setEnginePermissionAuthorityPrefix(String enginePermissionAuthorityPrefix) {
            this.enginePermissionAuthorityPrefix = enginePermissionAuthorityPrefix;
        }
    }
}
