import type { PortalAdapter, PortalUser } from "./portal-adapter";

interface IbPortalGlobal {
  security?: { token?: () => Promise<string> };
  context?: { user?: () => Promise<PortalUser>; tenantId?: () => Promise<string> };
  navigation?: { go?: (path: string) => void };
}

declare global {
  interface Window {
    IB_PORTAL?: IbPortalGlobal;
  }
}

export class IbPortalAdapter implements PortalAdapter {
  readonly kind = "ib-portal" as const;

  async getAccessToken(): Promise<string | undefined> {
    return window.IB_PORTAL?.security?.token?.();
  }

  async getUser(): Promise<PortalUser> {
    return await window.IB_PORTAL?.context?.user?.() ?? { id: "ib-portal-user" };
  }

  async getTenantId(): Promise<string | undefined> {
    return window.IB_PORTAL?.context?.tenantId?.() ?? (await this.getUser()).tenantId;
  }

  navigate(path: string): void {
    window.IB_PORTAL?.navigation?.go?.(path);
  }
}
