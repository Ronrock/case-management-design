// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { CaseManagementShell } from "./case-management-shell";

describe("case-management-shell", () => {
  afterEach(() => {
    document.body.replaceChildren();
    vi.unstubAllGlobals();
    sessionStorage.clear();
  });

  it("renders cases from the API page envelope", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      const body = url.endsWith("/cases")
        ? { items: [{ id: "case-1", title: "Payment review", state: "ACTIVE" }],
            page: 0, pageSize: 50, totalItems: 1, totalPages: 1 }
        : [{ id: "task-1", caseId: "case-1", name: "Review payment", state: "OPEN" }];
      return new Response(JSON.stringify(body), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      });
    }));

    if (!customElements.get("case-management-shell")) {
      customElements.define("case-management-shell", CaseManagementShell);
    }
    const shell = document.createElement("case-management-shell") as CaseManagementShell;
    document.body.append(shell);
    await shell.load();
    await shell.updateComplete;

    expect(shell.shadowRoot?.textContent).toContain("Payment review");
    expect(shell.shadowRoot?.textContent).toContain("Review payment");
  });
});
