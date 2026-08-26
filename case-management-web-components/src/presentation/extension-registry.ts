export interface CaseCapabilityFacade {
  executeAction(action: string, input?: Record<string, unknown>): Promise<unknown>;
  readResource(resource: "tasks" | "documents" | "milestones" | "slas"): Promise<unknown>;
}

/** Exact, versioned allowlist; manifests cannot select arbitrary custom-element names. */
export class ExtensionRegistry {
  private readonly entries = new Map<string, string>();

  allow(identifier: string, tagName: string): this {
    if (!/^[a-z][a-z0-9-]*@[1-9]\d*$/.test(identifier)) {
      throw new Error(`Invalid versioned extension identifier '${identifier}'`);
    }
    if (!/^[a-z][a-z0-9-]*-[a-z0-9-]+$/.test(tagName)) {
      throw new Error(`Invalid custom-element tag '${tagName}'`);
    }
    this.entries.set(identifier, tagName);
    return this;
  }

  resolve(identifier: string | undefined): string | undefined {
    return identifier ? this.entries.get(identifier) : undefined;
  }
}
