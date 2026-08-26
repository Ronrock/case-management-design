import { LitElement, css, html } from "lit";
import { customElement, property, state } from "lit/decorators.js";
import { CaseApiClient, type CaseSummary } from "../api/case-api-client";
import { detectPortalAdapter } from "../portal/detect-portal-adapter";
import type { CaseCapabilityFacade } from "../presentation/extension-registry";
import type { AvailableAction, PresentableResource } from "../presentation/presentation-manifest";
import type { PresentationForm } from "../presentation/case-presentation";
import "../presentation/case-presentation";

interface BoundDefinition {
  key: string;
  version: number;
  presentationReleaseId: string;
  contractReleaseId: string;
}

@customElement("case-detail")
export class CaseDetail extends LitElement {
  @property({ type: String, attribute: "api-base-url" }) apiBaseUrl = "/case-api/v2";
  @property({ type: String, attribute: "case-id" }) caseId = "";
  @state() private manifest?: Record<string, unknown>;
  @state() private resource?: PresentableResource;
  @state() private forms: Record<string, PresentationForm> = {};
  @state() private error?: string;

  private readonly adapter = detectPortalAdapter();
  private client?: CaseApiClient;
  private facade?: CaseCapabilityFacade;

  connectedCallback(): void {
    super.connectedCallback();
    void this.load();
  }

  async load(): Promise<void> {
    if (!this.caseId) return;
    this.error = undefined;
    const client = new CaseApiClient({
      baseUrl: this.apiBaseUrl,
      getAccessToken: () => this.adapter.getAccessToken()
    });
    this.client = client;
    try {
      const caseResource = await client.case(this.caseId);
      const key = required(caseResource.caseDefinitionKey, "caseDefinitionKey");
      const version = required(caseResource.caseDefinitionVersion, "caseDefinitionVersion");
      const definition = await client.definition(key, version) as unknown as BoundDefinition;
      const [manifest, contract, tasks, planItems, documents, milestones, slas] = await Promise.all([
        client.presentation(key, required(definition.presentationReleaseId, "presentationReleaseId")),
        client.contract(key, required(definition.contractReleaseId, "contractReleaseId")),
        client.caseTasks(this.caseId),
        client.casePlanItems(this.caseId),
        client.caseDocuments(this.caseId),
        client.caseMilestones(this.caseId),
        client.caseSlas(this.caseId)
      ]);
      this.manifest = manifest;
      this.forms = contractForms(contract);
      this.resource = {
        ...caseResource,
        fields: isRecord(caseResource.variables) ? caseResource.variables : {},
        tasks,
        planItems,
        documents,
        milestones,
        slas
      } as PresentableResource;
      this.facade = this.caseFacade(caseResource, client);
    } catch (error) {
      this.error = error instanceof Error ? error.message : String(error);
    }
  }

  render() {
    if (this.error) return html`<p class="error" role="alert">${this.error}</p>`;
    if (!this.manifest || !this.resource) return html`<p aria-live="polite">Loading case…</p>`;
    return html`<case-presentation .manifest=${this.manifest} .resource=${this.resource}
      .forms=${this.forms} .capabilityFacade=${this.facade}
      @case-action-request=${this.actionRequested}></case-presentation>`;
  }

  private async actionRequested(event: CustomEvent<{ action: AvailableAction }>): Promise<void> {
    await this.executeAvailable(event.detail.action);
  }

  private async executeAvailable(action: AvailableAction, input?: Record<string, unknown>) {
    const current = this.resource?.availableActions ?? [];
    if (!current.some(candidate => candidate.action === action.action
      && candidate.href === action.href && candidate.method === action.method)) {
      throw new Error(`Action '${action.action}' is not available`);
    }
    await this.client!.execute(action.href, action.method, input);
    await this.load();
  }

  private caseFacade(caseResource: CaseSummary, client: CaseApiClient): CaseCapabilityFacade {
    return Object.freeze({
      executeAction: async (name: string, input?: Record<string, unknown>) => {
        const action = caseResource.availableActions?.find(candidate => candidate.action === name);
        if (!action) throw new Error(`Action '${name}' is not available`);
        return this.executeAvailable(action, input);
      },
      readResource: async (resource: "tasks" | "documents" | "milestones" | "slas") => {
        if (resource === "tasks") return client.caseTasks(this.caseId);
        if (resource === "documents") return client.caseDocuments(this.caseId);
        if (resource === "milestones") return client.caseMilestones(this.caseId);
        if (resource === "slas") return client.caseSlas(this.caseId);
        throw new Error(`Capability '${String(resource)}' is not enabled for this shell`);
      }
    });
  }

  static styles = css`:host { display: block; } .error { color: #b42318; }`;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function required<T>(value: T | null | undefined, name: string): T {
  if (value === undefined || value === null || value === "") {
    throw new Error(`Case response is missing ${name}`);
  }
  return value;
}

function contractForms(contract: Record<string, unknown>): Record<string, PresentationForm> {
  const forms = contract.forms;
  if (!forms || typeof forms !== "object" || Array.isArray(forms)) return {};
  return forms as Record<string, PresentationForm>;
}
