import type { PortalAdapter, PortalUser } from "./portal-adapter";

export class StandalonePortalAdapter implements PortalAdapter {
  readonly kind = "standalone" as const;

  async getAccessToken(): Promise<string | undefined> {
    return sessionStorage.getItem("caseManagementToken") ?? undefined;
  }

  async getUser(): Promise<PortalUser> {
    return {
      id: sessionStorage.getItem("caseManagementUser") ?? "local-user",
      displayName: sessionStorage.getItem("caseManagementDisplayName") ?? "Local user",
      tenantId: sessionStorage.getItem("caseManagementTenant") ?? undefined
    };
  }

  async getTenantId(): Promise<string | undefined> {
    return (await this.getUser()).tenantId;
  }

  navigate(path: string): void {
    history.pushState(null, "", path);
    window.dispatchEvent(new PopStateEvent("popstate"));
  }
}
