import { LitElement, css, html, nothing, type TemplateResult } from "lit";
import { customElement, property } from "lit/decorators.js";
import { ref } from "lit/directives/ref.js";
import { html as staticHtml, unsafeStatic } from "lit/static-html.js";
import type { FormUiSchema, JsonObjectSchema } from "./json-schema-form";
import "./json-schema-form";
import type { CaseCapabilityFacade } from "./extension-registry";
import { ExtensionRegistry } from "./extension-registry";
import {
  effectiveActions,
  parsePresentationManifest,
  PresentationManifestError,
  resolveField,
  type AvailableAction,
  type PresentableResource,
  type PresentationManifestV1,
  type PresentationSection
} from "./presentation-manifest";

export interface CaseActionRequestDetail {
  action: AvailableAction;
}

export interface PresentationForm {
  schema: JsonObjectSchema;
  uiSchema?: FormUiSchema;
  value?: Record<string, unknown>;
}

@customElement("case-presentation")
export class CasePresentation extends LitElement {
  @property({ attribute: false })
  manifest: unknown;

  @property({ attribute: false })
  resource?: PresentableResource;

  @property({ attribute: false })
  forms: Record<string, PresentationForm> = {};

  @property({ attribute: false })
  extensionRegistry = new ExtensionRegistry();

  @property({ attribute: false })
  capabilityFacade?: CaseCapabilityFacade;

  render() {
    if (!this.manifest || !this.resource) {
      return html`<p class="empty">No case presentation is available.</p>`;
    }
    let manifest: PresentationManifestV1;
    try {
      manifest = parsePresentationManifest(this.manifest);
    } catch (error) {
      const message = error instanceof PresentationManifestError
        ? error.message : "The case presentation could not be loaded";
      return html`<p class="error" role="alert">${message}</p>`;
    }
    return html`
      <div class="presentation" data-manifest-version=${manifest.version}>
        ${manifest.sections.map(section => this.renderSection(section, this.resource!))}
      </div>
    `;
  }

  private renderSection(
    section: PresentationSection,
    resource: PresentableResource
  ): TemplateResult | typeof nothing {
    const heading = section.title
      ? html`<h2 id=${`${section.id}-title`}>${section.title}</h2>` : nothing;
    const labelledBy = section.title ? `${section.id}-title` : nothing;
    switch (section.primitive) {
      case "summary-fields":
      case "field-grid":
        return html`<section class="card" aria-labelledby=${labelledBy}>
          ${heading}${this.renderFields(section.fields ?? [], resource)}
        </section>`;
      case "actions":
        return html`<section class="card actions" aria-labelledby=${labelledBy}>
          ${heading}${effectiveActions(section.actions, resource.availableActions).map(action => html`
            <button type="button" @click=${() => this.requestAction(action)}>${action.name}</button>
          `)}
        </section>`;
      case "task-list":
        return this.renderList(section, resource.tasks);
      case "document-list":
        return this.renderList(section, resource.documents);
      case "milestone-list":
        return this.renderList(section, resource.milestones);
      case "sla-list":
        return this.renderList(section, resource.slas);
      case "plan-tree":
        return this.renderList(section, resource.planItems);
      case "form":
        return this.renderForm(section, heading, labelledBy);
      case "search":
        return html`<section class="card" aria-labelledby=${labelledBy}>
          ${heading}<slot name=${`search:${section.searchProfileId ?? section.id}`}></slot>
        </section>`;
      case "extension-slot":
        return this.renderExtension(section, resource, heading, labelledBy);
      default:
        return nothing;
    }
  }

  private renderForm(section: PresentationSection, heading: unknown, labelledBy: unknown) {
    const formId = section.formId ?? section.id;
    const form = this.forms[formId];
    if (!form) {
      return html`<section class="card" aria-labelledby=${labelledBy}>
        ${heading}<p class="error" role="alert">Form '${formId}' is unavailable.</p>
      </section>`;
    }
    return html`<section class="card" aria-labelledby=${labelledBy}>
      ${heading}<case-json-schema-form .schema=${form.schema} .uiSchema=${form.uiSchema}
        .value=${form.value ?? {}}></case-json-schema-form>
    </section>`;
  }

  private renderExtension(section: PresentationSection, resource: PresentableResource,
                          heading: unknown, labelledBy: unknown) {
    const tagName = this.extensionRegistry.resolve(section.component);
    if (!tagName) {
      return html`<section class="card" aria-labelledby=${labelledBy}>
        ${heading}<p class="error" role="alert">Extension '${section.component ?? ""}' is not allowed.</p>
      </section>`;
    }
    const tag = unsafeStatic(tagName);
    const props = {
      ...(section.props ?? {}),
      fields: Object.fromEntries((section.fields ?? []).map(fieldId =>
        [fieldId, resolveField(resource, fieldId)]))
    };
    return staticHtml`<section class="card" aria-labelledby=${labelledBy}>
      ${heading}<${tag} ${ref(element => {
        if (!element) return;
        Object.assign(element, { props, capabilities: this.capabilityFacade });
      })}></${tag}>
    </section>`;
  }

  private renderFields(fields: string[], resource: PresentableResource) {
    return html`<dl class="field-grid">${fields.map(fieldId => html`
      <div>
        <dt>${fieldId}</dt>
        <dd>${formatValue(resolveField(resource, fieldId))}</dd>
      </div>
    `)}</dl>`;
  }

  private renderList(section: PresentationSection, value: unknown) {
    const items = Array.isArray(value) ? value : [];
    return html`<section class="card">
      ${section.title ? html`<h2>${section.title}</h2>` : nothing}
      <ul>${items.map(item => html`<li>${formatListItem(item)}</li>`)}</ul>
    </section>`;
  }

  private requestAction(action: AvailableAction): void {
    this.dispatchEvent(new CustomEvent<CaseActionRequestDetail>("case-action-request", {
      bubbles: true,
      composed: true,
      detail: { action }
    }));
  }

  static styles = css`
    :host {
      --cm-card-background: #fff;
      --cm-card-border: #d9e2ec;
      --cm-focus: #2563eb;
      display: block;
      font: 14px/1.5 system-ui, sans-serif;
    }
    .presentation { display: grid; gap: 1rem; }
    .card {
      background: var(--cm-card-background);
      border: 1px solid var(--cm-card-border);
      border-radius: .5rem;
      padding: 1rem;
    }
    h2 { font-size: 1rem; margin: 0 0 .75rem; }
    .field-grid {
      display: grid;
      gap: .75rem;
      grid-template-columns: repeat(auto-fit, minmax(10rem, 1fr));
      margin: 0;
    }
    .field-grid div { min-width: 0; }
    dt { color: #52606d; font-size: .8rem; overflow-wrap: anywhere; }
    dd { margin: .2rem 0 0; overflow-wrap: anywhere; }
    .actions { display: flex; flex-wrap: wrap; gap: .5rem; }
    button { font: inherit; min-height: 2.75rem; padding: .5rem .85rem; }
    button:focus-visible { outline: 3px solid var(--cm-focus); outline-offset: 2px; }
    .error { color: #b42318; }
    .empty { color: #52606d; }
    @media (max-width: 32rem) { .field-grid { grid-template-columns: 1fr; } }
  `;
}

function formatValue(value: unknown): string {
  if (value === null || value === undefined || value === "") return "—";
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

function formatListItem(value: unknown): string {
  if (typeof value === "object" && value !== null) {
    const item = value as Record<string, unknown>;
    return formatValue(item.name ?? item.title ?? item.id ?? value);
  }
  return formatValue(value);
}
