import { describe, expect, it } from "vitest";
import { ExtensionRegistry } from "./extension-registry";

describe("ExtensionRegistry", () => {
  it("resolves only explicitly allowlisted, versioned components", () => {
    const registry = new ExtensionRegistry().allow("complaint-map@1", "cm-complaint-map");
    expect(registry.resolve("complaint-map@1")).toBe("cm-complaint-map");
    expect(registry.resolve("complaint-map@2")).toBeUndefined();
    expect(registry.resolve("script")).toBeUndefined();
  });
});
