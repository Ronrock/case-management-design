package org.casemgmt.permissions;

public final class PermissionActions {

    public static final String SEARCH_EXECUTE = "search.execute";

    public static final String CASE_CREATE = "case.create";
    public static final String CASE_READ = "case.read";
    public static final String CASE_UPDATE = "case.update";
    public static final String CASE_CLOSE = "case.close";
    public static final String CASE_CANCEL = "case.cancel";

    public static final String TASK_READ = "task.read";
    public static final String TASK_CLAIM = "task.claim";
    public static final String TASK_COMPLETE = "task.complete";

    public static final String DOCUMENT_READ = "document.read";
    public static final String DOCUMENT_LINK = "document.link";
    public static final String DOCUMENT_REMOVE = "document.remove";

    public static final String COMMENT_READ = "comment.read";
    public static final String COMMENT_ADD = "comment.add";

    public static final String MILESTONE_READ = "milestone.read";

    public static final String PROCESS_READ = "process.read";
    public static final String PROCESS_START = "process.start";

    private PermissionActions() {}
}
