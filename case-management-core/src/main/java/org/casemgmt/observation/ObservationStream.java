package org.casemgmt.observation;

/** Independent remote-history feeds. A cursor for one feed never advances another feed. */
public enum ObservationStream {
    TASKS,
    TASK_TERMINALS,
    ACTIVITIES,
    ACTIVITY_TERMINALS,
    PROCESSES
}
