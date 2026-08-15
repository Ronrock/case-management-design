package org.casemgmt.rest.policy;

/**
 * Everything a renderer needs to invoke the action, on the first read: no second
 * call to discover how (spec §8 obligation 2).
 */
public record AvailableAction(String action, String name, String href, String method, String formKey) {

    public static AvailableAction post(String action, String href) {
        return new AvailableAction(action, defaultName(action), href, "POST", null);
    }

    public static AvailableAction post(String action, String href, String formKey) {
        return new AvailableAction(action, defaultName(action), href, "POST", formKey);
    }

    public static AvailableAction patch(String action, String href) {
        return new AvailableAction(action, defaultName(action), href, "PATCH", null);
    }

    public static AvailableAction get(String action, String href) {
        return new AvailableAction(action, defaultName(action), href, "GET", null);
    }

    private static String defaultName(String action) {
        return switch (action) {
            case "update" -> "Update case";
            case "cancel" -> "Cancel case";
            case "close" -> "Close case";
            case "enable" -> "Enable";
            case "start" -> "Start";
            case "complete" -> "Complete";
            case "terminate" -> "Terminate";
            case "claim" -> "Claim";
            case "comment" -> "Add comment";
            case "start-process" -> "Start process";
            case "achieve" -> "Achieve";
            case "pause" -> "Pause";
            case "resume" -> "Resume";
            case "deploy-case-definition" -> "Deploy case definition";
            case "subscribe-webhook" -> "Subscribe webhook";
            case "view-webhook-dead-letters" -> "View webhook dead letters";
            case "redeliver-webhook-dead-letters" -> "Redeliver webhook dead letters";
            default -> action;
        };
    }
}
