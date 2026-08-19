import { hasPortalGlobal, type PortalAdapter } from "./portal-adapter";
import { IrisPortalAdapter } from "./iris-adapter";
import { IbPortalAdapter } from "./ib-portal-adapter";
import { StandalonePortalAdapter } from "./standalone-adapter";

export function detectPortalAdapter(): PortalAdapter {
  if (hasPortalGlobal("ING_IRIS")) {
    return new IrisPortalAdapter();
  }
  if (hasPortalGlobal("IB_PORTAL")) {
    return new IbPortalAdapter();
  }
  return new StandalonePortalAdapter();
}
