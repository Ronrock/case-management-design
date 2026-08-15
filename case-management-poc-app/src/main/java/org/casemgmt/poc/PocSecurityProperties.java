package org.casemgmt.poc;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "casemgmt.security")
public class PocSecurityProperties {

    public enum Mode { basic, oidc }

    private Mode mode = Mode.basic;
    private final Oidc oidc = new Oidc();

    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }
    public Oidc getOidc() { return oidc; }

    public static class Oidc {
        private String principalClaim = "sub";
        private String tenantClaim = "tenant";
        private String groupsClaim = "groups";
        private String workerPermissionsClaim = "worker_permissions";
        private String workerPermissionAuthorityPrefix = "";

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
    }
}
