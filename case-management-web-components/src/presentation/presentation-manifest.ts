export type PresentationPrimitive =
  | "summary-fields"
  | "field-grid"
  | "plan-tree"
  | "task-list"
  | "document-list"
  | "milestone-list"
  | "sla-list"
  | "search"
  | "form"
  | "actions"
  | "extension-slot";

export interface PresentationSection {
  id: string;
  primitive: PresentationPrimitive;
  title?: string;
  fields?: string[];
  actions?: string[];
  formId?: string;
  searchProfileId?: string;
  component?: string;
  props?: Record<string, unknown>;
}

export interface PresentationManifestV1 {
  version: string;
  localeNamespace?: string;
  sections: PresentationSection[];
}

export interface AvailableAction {
  action: string;
  name: string;
  href: string;
  method: string;
  formKey?: string;
}

export interface PresentableResource {
  id: string;
  title?: string;
  state?: string;
  businessKey?: string;
  fields?: Record<string, unknown>;
  availableActions?: AvailableAction[];
  [key: string]: unknown;
}

const PRIMITIVES = new Set<PresentationPrimitive>([
  "summary-fields", "field-grid", "plan-tree", "task-list", "document-list",
  "milestone-list", "sla-list", "search", "form", "actions", "extension-slot"
]);

export class PresentationManifestError extends Error {
  constructor(readonly code: string, message: string) {
    super(message);
    this.name = "PresentationManifestError";
  }
}

export function parsePresentationManifest(value: unknown): PresentationManifestV1 {
  if (!isObject(value)) {
    throw new PresentationManifestError(
      "invalid-presentation-manifest", "Presentation manifest must be an object"
    );
  }
  const rawVersion = value.version ?? value.schemaVersion;
  const version = typeof rawVersion === "number" ? String(rawVersion) : rawVersion;
  if (typeof version !== "string" || !/^\d+(?:\.\d+)?$/.test(version)) {
    throw new PresentationManifestError(
      "invalid-presentation-version", "Presentation manifest requires a numeric version"
    );
  }
  const major = Number.parseInt(version.split(".")[0], 10);
  if (major !== 1) {
    throw new PresentationManifestError(
      "unsupported-presentation-version",
      `Presentation manifest major version ${major} is not supported`
    );
  }
  if (!Array.isArray(value.sections)) {
    throw new PresentationManifestError(
      "invalid-presentation-manifest", "Presentation manifest sections must be an array"
    );
  }
  const ids = new Set<string>();
  const sections = value.sections.map((section, index) => parseSection(section, index, ids));
  return {
    version,
    localeNamespace: typeof value.localeNamespace === "string"
      ? value.localeNamespace : undefined,
    sections
  };
}

export function resolveField(resource: PresentableResource, fieldId: string): unknown {
  const systemFields: Record<string, keyof PresentableResource> = {
    "system:id": "id",
    "system:title": "title",
    "system:state": "state",
    "system:business-key": "businessKey"
  };
  const systemKey = systemFields[fieldId];
  if (systemKey) {
    return resource[systemKey];
  }
  return resource.fields?.[fieldId];
}

export function effectiveActions(
  requested: readonly string[] | undefined,
  available: readonly AvailableAction[] | undefined
): AvailableAction[] {
  const serverActions = available ?? [];
  if (!requested?.length) {
    return [...serverActions];
  }
  const allowed = new Set(requested);
  return serverActions.filter(action => allowed.has(action.action));
}

function parseSection(value: unknown, index: number, ids: Set<string>): PresentationSection {
  if (!isObject(value) || typeof value.id !== "string" || !value.id.trim()) {
    throw new PresentationManifestError(
      "invalid-presentation-section", `Presentation section ${index} requires an id`
    );
  }
  if (ids.has(value.id)) {
    throw new PresentationManifestError(
      "duplicate-presentation-section", `Duplicate presentation section id '${value.id}'`
    );
  }
  ids.add(value.id);
  if (typeof value.primitive !== "string" || !PRIMITIVES.has(value.primitive as PresentationPrimitive)) {
    throw new PresentationManifestError(
      "unsupported-presentation-primitive",
      `Presentation section '${value.id}' uses unsupported primitive '${String(value.primitive)}'`
    );
  }
  return {
    id: value.id,
    primitive: value.primitive as PresentationPrimitive,
    title: optionalString(value.title),
    fields: stringArray(value.fields),
    actions: stringArray(value.actions),
    formId: optionalString(value.formId),
    searchProfileId: optionalString(value.searchProfileId),
    component: optionalString(value.component),
    props: isObject(value.props) ? { ...value.props } : undefined
  };
}

function optionalString(value: unknown): string | undefined {
  return typeof value === "string" ? value : undefined;
}

function stringArray(value: unknown): string[] | undefined {
  return Array.isArray(value) && value.every(item => typeof item === "string")
    ? [...value] : undefined;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
