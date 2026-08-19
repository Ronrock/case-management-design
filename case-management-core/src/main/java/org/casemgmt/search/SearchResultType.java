package org.casemgmt.search;

public enum SearchResultType {
    CASE("case"),
    TASK("task"),
    WORKLIST_ITEM("worklistItem"),
    DOCUMENT("document"),
    TIMELINE_EVENT("timelineEvent"),
    ENTERPRISE_REFERENCE("enterpriseReference");

    private final String wireName;

    SearchResultType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
