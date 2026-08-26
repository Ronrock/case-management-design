// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { CaseDetail } from "./case-detail";
import type { CasePresentation } from "../presentation/case-presentation";

describe("case-detail Scenario A loading", () => {
  afterEach(() => {
    document.body.replaceChildren();
    vi.unstubAllGlobals();
  });

  it("loads the manifest plus ordinary filtered resources and canonical variables", async () => {
    const requested: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      requested.push(url);
      const body = url.endsWith("/cases/case-1")
        ? { id: "case-1", caseDefinitionKey: "complaint", caseDefinitionVersion: 2,
            state: "ACTIVE", variables: { customerName: "Ada" }, availableActions: [] }
        : url.endsWith("/case-definitions/complaint/versions/2")
          ? { key: "complaint", version: 2, presentationReleaseId: "present-1",
              contractReleaseId: "contract-1" }
          : url.includes("/presentation-releases/")
            ? { version: "1.0", sections: [] }
            : url.includes("/contract-releases/")
              ? { forms: {} }
              : url.endsWith("/tasks") ? [{ id: "task-1" }]
                : url.endsWith("/plan-items") ? [{ id: "plan-1" }]
                  : url.endsWith("/documents") ? [{ id: "document-1" }]
                    : url.endsWith("/milestones") ? [{ id: "milestone-1" }]
                      : url.endsWith("/slas") ? [{ id: "sla-1" }]
                        : [];
      return new Response(JSON.stringify(body), {
        status: 200, headers: { "Content-Type": "application/json" }
      });
    }));

    const detail = new CaseDetail();
    detail.caseId = "case-1";
    await detail.load();
    document.body.append(detail);
    await detail.updateComplete;

    const presentation = detail.shadowRoot?.querySelector("case-presentation") as CasePresentation;
    expect(presentation.resource?.fields).toEqual({ customerName: "Ada" });
    expect(presentation.resource?.documents).toEqual([{ id: "document-1" }]);
    expect(presentation.resource?.milestones).toEqual([{ id: "milestone-1" }]);
    expect(presentation.resource?.slas).toEqual([{ id: "sla-1" }]);
    expect(requested.some(url => url.endsWith("/cases/case-1/documents"))).toBe(true);
    expect(requested.some(url => url.endsWith("/cases/case-1/milestones"))).toBe(true);
    expect(requested.some(url => url.endsWith("/cases/case-1/slas"))).toBe(true);
  });
});
