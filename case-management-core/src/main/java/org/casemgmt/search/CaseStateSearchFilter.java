package org.casemgmt.search;

import org.casemgmt.domain.CaseState;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CaseStateSearchFilter {

    private CaseStateSearchFilter() {
    }

    public static List<CaseState> states(Map<String, Object> filters) {
        Object value = filters.containsKey("state") ? filters.get("state") : filters.get("status");
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return list.stream().map(Object::toString).map(CaseStateSearchFilter::state).toList();
        }
        return List.of(state(value.toString()));
    }

    public static void normalize(Map<String, Object> filters) {
        List<CaseState> states = states(filters);
        if (states.isEmpty()) {
            return;
        }
        Object original = filters.containsKey("state") ? filters.get("state") : filters.get("status");
        filters.put("state", original instanceof List<?>
                ? states.stream().map(CaseState::name).toList()
                : states.getFirst().name());
        filters.remove("status");
    }

    public static CaseState state(String raw) {
        try {
            return CaseState.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid value '" + raw
                    + "' for state; legal values are CREATED, ACTIVE, SUSPENDED, CLOSED, CANCELLED", e);
        }
    }
}
