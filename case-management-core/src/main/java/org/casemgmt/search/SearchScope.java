package org.casemgmt.search;

import java.util.Locale;

public enum SearchScope {
    CASES("cases"),
    TASKS("tasks"),
    WORKLISTS("worklists"),
    DOCUMENTS("documents"),
    TIMELINE("timeline"),
    ENTERPRISE("enterprise"),
    SEMANTIC("semantic");

    private final String wireName;

    SearchScope(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static SearchScope fromWire(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Search scope must not be blank");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (SearchScope scope : values()) {
            if (scope.wireName.equals(normalized)) {
                return scope;
            }
        }
        throw new IllegalArgumentException("Unsupported search scope: " + value);
    }
}
