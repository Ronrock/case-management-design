export interface PortalUser {
  id: string;
  displayName?: string;
  groups?: string[];
  tenantId?: string;
}

export interface PortalAdapter {
  readonly kind: "standalone" | "iris" | "ib-portal";
  getAccessToken(): Promise<string | undefined>;
  getUser(): Promise<PortalUser>;
  getTenantId(): Promise<string | undefined>;
  navigate(path: string): void;
}

export function hasPortalGlobal(name: string): boolean {
  return typeof window !== "undefined" && name in window;
}
