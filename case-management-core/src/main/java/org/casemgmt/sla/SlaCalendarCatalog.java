package org.casemgmt.sla;

import java.util.Map;

/** Exact, tenant-scoped lookup for immutable calendar revisions used by published contracts. */
public interface SlaCalendarCatalog {

    record Revision(String tenantId, String calendarId, int revision,
                    String sha256, Map<String, Object> definition) { }

    Revision require(String tenantId, String calendarId, int revision);
}
