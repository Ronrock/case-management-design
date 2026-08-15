import { LitElement, css, html } from "lit";
import { customElement, property, state } from "lit/decorators.js";
import { CaseApiClient, type CaseSummary, type TaskSummary } from "../api/case-api-client";
import { detectPortalAdapter } from "../portal/detect-portal-adapter";
import type { PortalAdapter, PortalUser } from "../portal/portal-adapter";

@customElement("case-management-shell")
export class CaseManagementShell extends LitElement {
  @property({ type: String, attribute: "api-base-url" })
  apiBaseUrl = "/case-api/v2";

  @state()
  private cases: CaseSummary[] = [];

  @state()
  private tasks: TaskSummary[] = [];

  @state()
  private user?: PortalUser;

  @state()
  private error?: string;

  private adapter: PortalAdapter = detectPortalAdapter();

  connectedCallback(): void {
    super.connectedCallback();
    void this.load();
  }

  async load(): Promise<void> {
    this.error = undefined;
    const client = new CaseApiClient({
      baseUrl: this.apiBaseUrl,
      getAccessToken: () => this.adapter.getAccessToken()
    });
    try {
      const [user, cases, tasks] = await Promise.all([
        this.adapter.getUser(),
        client.cases(),
        client.tasks()
      ]);
      this.user = user;
      this.cases = cases;
      this.tasks = tasks;
    } catch (error) {
      this.error = error instanceof Error ? error.message : String(error);
    }
  }

  render() {
    return html`
      <section class="shell">
        <header>
          <div>
            <strong>Case Management</strong>
            <span>${this.adapter.kind}${this.user?.displayName ? ` - ${this.user.displayName}` : ""}</span>
          </div>
          <button type="button" @click=${() => this.load()}>Refresh</button>
        </header>
        ${this.error ? html`<p class="error">${this.error}</p>` : ""}
        <main>
          <section>
            <h2>Cases</h2>
            ${this.cases.map(item => html`
              <button class="row" type="button" @click=${() => this.adapter.navigate(`/cases/${item.id}`)}>
                <span>${item.title ?? item.id}</span>
                <small>${item.state}</small>
              </button>
            `)}
          </section>
          <section>
            <h2>Tasks</h2>
            ${this.tasks.map(item => html`
              <button class="row" type="button" @click=${() => this.adapter.navigate(`/tasks/${item.id}`)}>
                <span>${item.name}</span>
                <small>${item.state}</small>
              </button>
            `)}
          </section>
        </main>
      </section>
    `;
  }

  static styles = css`
    :host {
      display: block;
      color: #1f2933;
      font: 14px/1.4 system-ui, sans-serif;
    }

    .shell {
      min-height: 100%;
      background: #f8fafc;
    }

    header {
      align-items: center;
      background: #ffffff;
      border-bottom: 1px solid #d9e2ec;
      display: flex;
      justify-content: space-between;
      padding: 12px 16px;
    }

    header span {
      color: #52606d;
      display: block;
      font-size: 12px;
    }

    button {
      border: 1px solid #bcccdc;
      background: #ffffff;
      border-radius: 4px;
      color: inherit;
      cursor: pointer;
      font: inherit;
      padding: 8px 10px;
    }

    main {
      display: grid;
      gap: 16px;
      grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
      padding: 16px;
    }

    h2 {
      font-size: 14px;
      margin: 0 0 8px;
    }

    .row {
      align-items: center;
      display: flex;
      justify-content: space-between;
      margin-bottom: 8px;
      width: 100%;
    }

    small,
    .error {
      color: #627d98;
    }

    .error {
      margin: 12px 16px 0;
    }
  `;
}
