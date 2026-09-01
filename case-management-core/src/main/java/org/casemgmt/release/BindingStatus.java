package org.casemgmt.release;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The lifecycle of a case-definition version binding — the row that ties one orchestration, one
 * contract and one presentation release together into a runnable version.
 *
 * <p>A binding needs its own state rather than borrowing the orchestration release's. The two
 * answer different questions: a release's state is about one artifact, a binding's is about
 * whether this particular <em>combination</em> may be selected to start new cases. A binding can
 * be retired while every release it references stays active and in use by other versions,
 * because releases are content-addressed and shared.
 *
 * <p>Selecting a version by "most recently bound" is what this replaces. Ordering is not a
 * status: it silently promotes a half-finished or withdrawn binding the moment it happens to be
 * the newest row.
 */
public enum BindingStatus {

    /** Written, but not yet usable — its orchestration may still be deploying. */
    DRAFT,
    /** Selectable for new cases. */
    ACTIVE,
    /** Withdrawn from new cases. Cases already pinned to it keep running unchanged. */
    RETIRED,
    /** Definitively unusable, e.g. its orchestration deployment failed. */
    FAILED;

    private static final Map<BindingStatus, Set<BindingStatus>> LEGAL =
            new EnumMap<>(BindingStatus.class);

    static {
        LEGAL.put(DRAFT, EnumSet.of(ACTIVE, FAILED));
        LEGAL.put(ACTIVE, EnumSet.of(RETIRED));
        LEGAL.put(RETIRED, EnumSet.noneOf(BindingStatus.class));
        LEGAL.put(FAILED, EnumSet.noneOf(BindingStatus.class));
    }

    public boolean canTransitionTo(BindingStatus next) {
        return next != null && LEGAL.get(this).contains(next);
    }

    /** @throws IllegalStateException if the move is not part of the lifecycle. */
    public BindingStatus transitionTo(BindingStatus next) {
        if (!canTransitionTo(next)) {
            throw new IllegalStateException(
                    "Illegal binding transition " + this + " -> " + next);
        }
        return next;
    }

    /** Whether this binding may be selected to start a new case. */
    public boolean isActive() {
        return this == ACTIVE;
    }

    public boolean isTerminal() {
        return LEGAL.get(this).isEmpty();
    }
}
