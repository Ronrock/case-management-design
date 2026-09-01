// @vitest-environment jsdom
import { describe, expect, it } from "vitest";
import { CaseJsonSchemaForm } from "./json-schema-form";

describe("case-json-schema-form", () => {
  it("renders required canonical fields and emits bounded form data", async () => {
    const form = new CaseJsonSchemaForm();
    form.schema = {
      type: "object",
      required: ["complaint.subject"],
      properties: {
        "complaint.subject": { type: "string", title: "Subject" },
        urgent: { type: "boolean", title: "Urgent" }
      }
    };
    document.body.append(form);
    await form.updateComplete;

    const subject = form.shadowRoot?.querySelector<HTMLInputElement>(
      "input[name='complaint.subject']"
    );
    expect(subject?.required).toBe(true);
    subject!.value = "Late delivery";
    subject!.dispatchEvent(new Event("input"));
    await form.updateComplete;
    expect(form.value).toEqual({ "complaint.subject": "Late delivery" });
  });
});
