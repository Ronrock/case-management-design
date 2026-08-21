import type { PortalAdapter, PortalUser } from "./portal-adapter";

interface EmbeddedHostGlobal {
  auth?: { getAccessToken?: () => Promise<string> };
  user?: { getProfile?: () => Promise<PortalUser> };
  router?: { navigate?: (path: string) => void };
}

declare global {
  interface Window {
    CASE_MANAGEMENT_HOST?: EmbeddedHostGlobal;
  }
}

export class EmbeddedPortalAdapter implements PortalAdapter {
  readonly kind = "embedded" as const;

  async getAccessToken(): Promise<string | undefined> {
    return window.CASE_MANAGEMENT_HOST?.auth?.getAccessToken?.();
  }

  async getUser(): Promise<PortalUser> {
    return await window.CASE_MANAGEMENT_HOST?.user?.getProfile?.() ?? { id: "embedded-user" };
  }

  async getTenantId(): Promise<string | undefined> {
    return (await this.getUser()).tenantId;
  }

  navigate(path: string): void {
    window.CASE_MANAGEMENT_HOST?.router?.navigate?.(path);
  }
}
