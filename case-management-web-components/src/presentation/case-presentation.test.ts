// @vitest-environment jsdom
import { describe, expect, it } from "vitest";
import "./case-presentation";
import type { CasePresentation } from "./case-presentation";

describe("case-presentation", () => {
  it("interprets bounded fields and intersects actions with server availability", async () => {
    const view = document.createElement("case-presentation") as CasePresentation;
    view.manifest = {
      version: "1.0",
      sections: [
        { id: "summary", primitive: "field-grid", fields: ["system:state", "field:amount"] },
        { id: "work", primitive: "actions", actions: ["claim", "admin-delete"] }
      ]
    };
    view.resource = {
      id: "case-1",
      state: "ACTIVE",
      fields: { "field:amount": 1250 },
      availableActions: [
        { action: "claim", name: "Claim", href: "/tasks/t1/claim", method: "POST" }
      ]
    };

    document.body.append(view);
    await view.updateComplete;

    expect(view.shadowRoot?.textContent).toContain("ACTIVE");
    expect(view.shadowRoot?.textContent).toContain("1250");
    expect(view.shadowRoot?.textContent).toContain("Claim");
    expect(view.shadowRoot?.textContent).not.toContain("admin-delete");
  });

  it("shows a predictable error for unsupported manifest versions", async () => {
    const view = document.createElement("case-presentation") as CasePresentation;
    view.manifest = { version: "3.0", sections: [] };
    view.resource = { id: "case-1" };

    document.body.append(view);
    await view.updateComplete;

    expect(view.shadowRoot?.querySelector('[role="alert"]')?.textContent)
      .toContain("Presentation manifest major version 3 is not supported");
  });
});
