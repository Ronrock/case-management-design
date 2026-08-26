import { LitElement, css, html } from "lit";
import { customElement, property, state } from "lit/decorators.js";

export interface JsonSchemaProperty {
  type?: "string" | "number" | "integer" | "boolean";
  title?: string;
  description?: string;
  enum?: Array<string | number>;
  format?: string;
  default?: unknown;
}

export interface JsonObjectSchema {
  type?: "object";
  properties?: Record<string, JsonSchemaProperty>;
  required?: string[];
}

export interface FormUiField {
  label?: string;
  widget?: "text" | "textarea" | "date" | "select" | "checkbox";
  help?: string;
}

export interface FormUiSchema {
  order?: string[];
  fields?: Record<string, FormUiField>;
}

@customElement("case-json-schema-form")
export class CaseJsonSchemaForm extends LitElement {
  @property({ attribute: false }) schema?: JsonObjectSchema;
  @property({ attribute: false }) uiSchema?: FormUiSchema;
  @property({ attribute: false }) value: Record<string, unknown> = {};
  @property({ type: Boolean }) disabled = false;
  @state() private validationMessage?: string;

  render() {
    const properties = this.schema?.properties ?? {};
    const required = new Set(this.schema?.required ?? []);
    const order = this.uiSchema?.order?.filter(id => id in properties)
      ?? Object.keys(properties);
    return html`<form @submit=${this.submit} novalidate>
      ${order.map(id => this.field(id, properties[id], required.has(id)))}
      ${this.validationMessage
        ? html`<p class="error" role="alert">${this.validationMessage}</p>` : ""}
      <button type="submit" ?disabled=${this.disabled}>Submit</button>
    </form>`;
  }

  private field(id: string, schema: JsonSchemaProperty, required: boolean) {
    const ui = this.uiSchema?.fields?.[id];
    const widget = ui?.widget ?? inferredWidget(schema);
    const label = ui?.label ?? schema.title ?? id;
    const describedBy = ui?.help || schema.description ? `${id}-help` : undefined;
    let control;
    if (schema.enum || widget === "select") {
      control = html`<select id=${id} name=${id} ?required=${required} ?disabled=${this.disabled}
        aria-describedby=${describedBy ?? ""} @change=${this.changed}>
        <option value="">Select…</option>
        ${(schema.enum ?? []).map(option => html`<option
          value=${String(option)} ?selected=${this.value[id] === option}>${option}</option>`)}
      </select>`;
    } else if (widget === "textarea") {
      control = html`<textarea id=${id} name=${id} ?required=${required} ?disabled=${this.disabled}
        aria-describedby=${describedBy ?? ""} @input=${this.changed}>${this.value[id] ?? ""}</textarea>`;
    } else if (widget === "checkbox" || schema.type === "boolean") {
      control = html`<input id=${id} name=${id} ?required=${required} ?disabled=${this.disabled}
        aria-describedby=${describedBy ?? ""} type="checkbox"
        .checked=${Boolean(this.value[id])} @change=${this.changed}>`;
    } else {
      const type = widget === "date" || schema.format === "date" ? "date"
        : schema.type === "number" || schema.type === "integer" ? "number" : "text";
      control = html`<input id=${id} name=${id} ?required=${required} ?disabled=${this.disabled}
        aria-describedby=${describedBy ?? ""} type=${type} .value=${String(this.value[id] ?? "")}
        @input=${this.changed}>`;
    }
    return html`<div class="field">
      <label for=${id}>${label}${required ? html` <span aria-hidden="true">*</span>` : ""}</label>
      ${control}
      ${describedBy ? html`<small id=${describedBy}>${ui?.help ?? schema.description}</small>` : ""}
    </div>`;
  }

  private changed(event: Event): void {
    const input = event.currentTarget as HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement;
    const schema = this.schema?.properties?.[input.name];
    const next = { ...this.value };
    next[input.name] = input instanceof HTMLInputElement && input.type === "checkbox"
      ? input.checked
      : schema?.type === "number" || schema?.type === "integer"
        ? (input.value === "" ? undefined : Number(input.value)) : input.value;
    this.value = next;
    this.dispatchEvent(new CustomEvent("form-value-change", {
      bubbles: true, composed: true, detail: { value: next }
    }));
  }

  private submit(event: SubmitEvent): void {
    event.preventDefault();
    const form = event.currentTarget as HTMLFormElement;
    if (!form.reportValidity()) {
      this.validationMessage = "Complete the required fields before submitting.";
      return;
    }
    this.validationMessage = undefined;
    this.dispatchEvent(new CustomEvent("form-submit", {
      bubbles: true, composed: true, detail: { value: { ...this.value } }
    }));
  }

  static styles = css`
    form, .field { display: grid; gap: .5rem; }
    form { gap: 1rem; }
    label { font-weight: 600; }
    input, select, textarea, button { box-sizing: border-box; font: inherit; min-height: 2.75rem; }
    input, select, textarea { border: 1px solid var(--cm-input-border, #9fb3c8); border-radius: .25rem; padding: .5rem; width: 100%; }
    textarea { min-height: 7rem; resize: vertical; }
    :is(input, select, textarea, button):focus-visible { outline: 3px solid var(--cm-focus, #2563eb); outline-offset: 2px; }
    small { color: #52606d; }
    .error { color: #b42318; margin: 0; }
  `;
}

function inferredWidget(schema: JsonSchemaProperty): FormUiField["widget"] {
  if (schema.enum) return "select";
  if (schema.type === "boolean") return "checkbox";
  if (schema.format === "date") return "date";
  return "text";
}
