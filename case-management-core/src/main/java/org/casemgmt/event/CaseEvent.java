package org.casemgmt.event;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

public record CaseEvent(String id, String source, String type, String subject,
                        String tenantId, OffsetDateTime time, Map<String, Object> data) {

    /** CloudEvents 1.0, structured JSON mode (spec §6.2). */
    public Map<String, Object> toCloudEvent() {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("specversion", "1.0");
        envelope.put("id", id);
        envelope.put("source", source);
        envelope.put("type", type);
        envelope.put("subject", subject);
        envelope.put("time", time.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        envelope.put("datacontenttype", "application/json");
        if (tenantId != null) {
            envelope.put("tenantid", tenantId);
        }
        envelope.put("data", data);
        return envelope;
    }

    public CaseEvent withType(String fullType) {
        return new CaseEvent(id, source, fullType, subject, tenantId, time, data);
    }
}
