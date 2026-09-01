// @vitest-environment jsdom
import { describe, expect, it } from "vitest";
import {
  PresentationManifestError,
  effectiveActions,
  parsePresentationManifest,
  resolveField
} from "./presentation-manifest";

describe("presentation manifest v1", () => {
  it("rejects unsupported major versions predictably", () => {
    expect(() => parsePresentationManifest({ version: "2.0", sections: [] }))
      .toThrowError(new PresentationManifestError(
        "unsupported-presentation-version",
        "Presentation manifest major version 2 is not supported"
      ));
  });

  it("resolves canonical and system field ids without JSON paths in the manifest", () => {
    const resource = {
      id: "case-1",
      state: "ACTIVE",
      fields: { "field:amount": 1250 }
    };

    expect(resolveField(resource, "system:state")).toBe("ACTIVE");
    expect(resolveField(resource, "field:amount")).toBe(1250);
  });

  it("renders only actions returned by the server", () => {
    const manifestActions = ["claim", "complete", "forbidden-admin-action"];
    const serverActions = [
      { action: "claim", name: "Claim", href: "/tasks/t1/claim", method: "POST" },
      { action: "complete", name: "Complete", href: "/tasks/t1/complete", method: "POST" }
    ];

    expect(effectiveActions(manifestActions, serverActions).map(action => action.action))
      .toEqual(["claim", "complete"]);
  });
});
