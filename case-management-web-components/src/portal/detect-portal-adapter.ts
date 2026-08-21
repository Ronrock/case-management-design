import { hasPortalGlobal, type PortalAdapter } from "./portal-adapter";
import { EmbeddedPortalAdapter } from "./embedded-portal-adapter";
import { StandalonePortalAdapter } from "./standalone-adapter";

export function detectPortalAdapter(): PortalAdapter {
  if (hasPortalGlobal("CASE_MANAGEMENT_HOST")) {
    return new EmbeddedPortalAdapter();
  }
  return new StandalonePortalAdapter();
}
