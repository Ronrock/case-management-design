package org.casemgmt.observation;

import org.casemgmt.repo.JsonCodec;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Versioned durable representation of a remote lifecycle fact. */
public record ObservationEnvelope(EngineObservation observation) {
    public static final int VERSION = 1;

    public String payload() {
        EngineObservation o = observation;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", VERSION); body.put("kind", kind(o));
        body.put("observationId", o.observationId()); body.put("observationVersion", o.observationVersion());
        body.put("source", o.source()); body.put("engineId", o.engineId()); body.put("tenantId", o.tenantId());
        body.put("caseId", o.caseId()); body.put("processInstanceId", o.processInstanceId());
        body.put("entityId", o.entityId()); body.put("entityRevision", o.entityRevision());
        body.put("eventType", o.eventType().name()); body.put("engineOccurredAt", o.engineOccurredAt().toString());
        body.put("receivedAt", o.receivedAt().toString()); body.put("attributes", o.attributes());
        return JsonCodec.canonicalJson(body);
    }

    @SuppressWarnings("unchecked")
    public static ObservationEnvelope decode(String payload) {
        Map<String, Object> body = JsonCodec.toMap(payload);
        if (!Integer.valueOf(VERSION).equals(body.get("version"))) throw new IllegalArgumentException("Unsupported observation envelope version");
        String kind = required(body, "kind");
        String id = required(body, "observationId"), source = required(body, "source"), engine = required(body, "engineId");
        String tenant = string(body.get("tenantId")), caseId = required(body, "caseId"), process = required(body, "processInstanceId"), entity = required(body, "entityId");
        int version = ((Number) body.get("observationVersion")).intValue();
        Long revision = body.get("entityRevision") == null ? null : ((Number) body.get("entityRevision")).longValue();
        Instant occurred = Instant.parse(required(body, "engineOccurredAt")), received = Instant.parse(required(body, "receivedAt"));
        Map<String, Object> attributes = body.get("attributes") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        String event = required(body, "eventType");
        EngineObservation observation = switch (kind) {
            case "PROCESS" -> new ProcessObservation(id, version, source, engine, tenant, caseId, process, entity, revision,
                    ProcessObservation.EventType.valueOf(event), occurred, received, attributes);
            case "TASK" -> new UserTaskObservation(id, version, source, engine, tenant, caseId, process, entity, revision,
                    UserTaskObservation.EventType.valueOf(event), occurred, received, attributes);
            case "ACTIVITY" -> new ActivityLifecycleObservation(id, version, source, engine, tenant, caseId, process, entity, revision,
                    ActivityLifecycleObservation.EventType.valueOf(event), occurred, received, attributes);
            case "MILESTONE" -> new MilestoneObservation(id, version, source, engine, tenant, caseId, process, entity, revision,
                    MilestoneObservation.EventType.valueOf(event), occurred, received, attributes);
            default -> throw new IllegalArgumentException("Unsupported observation kind " + kind);
        };
        return new ObservationEnvelope(observation);
    }

    private static String kind(EngineObservation o) {
        if (o instanceof ProcessObservation) return "PROCESS";
        if (o instanceof UserTaskObservation) return "TASK";
        if (o instanceof ActivityLifecycleObservation) return "ACTIVITY";
        if (o instanceof MilestoneObservation) return "MILESTONE";
        throw new IllegalArgumentException("Unsupported remote observation type " + o.getClass().getSimpleName());
    }
    private static String required(Map<String, Object> body, String key) { String value = string(body.get(key)); if (value == null) throw new IllegalArgumentException("Missing " + key); return value; }
    private static String string(Object value) { return value == null ? null : value.toString(); }
}
