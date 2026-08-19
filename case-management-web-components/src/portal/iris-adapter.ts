import type { PortalAdapter, PortalUser } from "./portal-adapter";

interface IrisGlobal {
  auth?: { getAccessToken?: () => Promise<string> };
  user?: { getProfile?: () => Promise<PortalUser> };
  router?: { navigate?: (path: string) => void };
}

declare global {
  interface Window {
    ING_IRIS?: IrisGlobal;
  }
}

export class IrisPortalAdapter implements PortalAdapter {
  readonly kind = "iris" as const;

  async getAccessToken(): Promise<string | undefined> {
    return window.ING_IRIS?.auth?.getAccessToken?.();
  }

  async getUser(): Promise<PortalUser> {
    return await window.ING_IRIS?.user?.getProfile?.() ?? { id: "iris-user" };
  }

  async getTenantId(): Promise<string | undefined> {
    return (await this.getUser()).tenantId;
  }

  navigate(path: string): void {
    window.ING_IRIS?.router?.navigate?.(path);
  }
}
