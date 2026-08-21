// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { detectPortalAdapter } from "./detect-portal-adapter";

describe("embedded portal adapter", () => {
  afterEach(() => {
    delete window.CASE_MANAGEMENT_HOST;
  });

  it("normalizes the enterprise host contract", async () => {
    const navigate = vi.fn();
    window.CASE_MANAGEMENT_HOST = {
      auth: { getAccessToken: async () => "access-token" },
      user: { getProfile: async () => ({ id: "worker-1", tenantId: "tenant-1" }) },
      router: { navigate }
    };

    const adapter = detectPortalAdapter();

    expect(adapter.kind).toBe("embedded");
    expect(await adapter.getAccessToken()).toBe("access-token");
    expect(await adapter.getUser()).toEqual({ id: "worker-1", tenantId: "tenant-1" });
    expect(await adapter.getTenantId()).toBe("tenant-1");

    adapter.navigate("/cases/case-1");
    expect(navigate).toHaveBeenCalledWith("/cases/case-1");
  });
});
