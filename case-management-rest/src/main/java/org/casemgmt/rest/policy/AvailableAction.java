package org.casemgmt.rest.policy;

/**
 * Everything a renderer needs to invoke the action, on the first read: no second
 * call to discover how (spec §8 obligation 2).
 */
public record AvailableAction(String action, String href, String method, String formKey) {

    public static AvailableAction post(String action, String href) {
        return new AvailableAction(action, href, "POST", null);
    }

    public static AvailableAction post(String action, String href, String formKey) {
        return new AvailableAction(action, href, "POST", formKey);
    }

    public static AvailableAction patch(String action, String href) {
        return new AvailableAction(action, href, "PATCH", null);
    }

    public static AvailableAction delete(String action, String href) {
        return new AvailableAction(action, href, "DELETE", null);
    }
}
