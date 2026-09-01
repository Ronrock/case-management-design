package org.casemgmt.release;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The lifecycle of one published release.
 *
 * <p>Publication and usability are deliberately separate facts. A release row exists as soon as
 * its bytes are stored, but an orchestration release is not runnable until an engine deployment
 * has been verified behind it — in remote mode that happens after the request that published it
 * has already returned. Collapsing the two (constructing straight into {@code ACTIVE}) is what
 * allowed a case to select a release whose BPMN the engine had never accepted.
 *
 * <p>Transitions are guarded rather than advisory. {@link #transitionTo} throws on an illegal
 * move, so an unexpected state cannot be written and then quietly read back somewhere else.
 *
 * <ul>
 *   <li>Orchestration: {@code DRAFT -> VALIDATED -> DEPLOYING -> ACTIVE}.</li>
 *   <li>Contract and presentation: {@code DRAFT -> VALIDATED -> ACTIVE} — nothing to deploy.</li>
 *   <li>Any pre-active state may fail; {@code ACTIVE} may be retired.</li>
 * </ul>
 *
 * <p>{@code FAILED} and {@code RETIRED} are terminal. A release id identifies exactly one
 * immutable artifact, so recovering from either means publishing a new release rather than
 * reviving this one under the same id and changing what that id historically meant.
 */
public enum ReleaseStatus {

    /** Stored, not yet checked. */
    DRAFT,
    /** Schema and structural validation passed; nothing has been deployed. */
    VALIDATED,
    /** An engine deployment is in flight (remote mode reports this until observation). */
    DEPLOYING,
    /** Verified and selectable. */
    ACTIVE,
    /** Definitively rejected; carries a bounded diagnostic. */
    FAILED,
    /** Withdrawn from new binding. Running cases pinned to it are unaffected. */
    RETIRED;

    private static final Map<ReleaseStatus, Set<ReleaseStatus>> LEGAL =
            new EnumMap<>(ReleaseStatus.class);

    static {
        LEGAL.put(DRAFT, EnumSet.of(VALIDATED, FAILED));
        LEGAL.put(VALIDATED, EnumSet.of(DEPLOYING, ACTIVE, FAILED));
        LEGAL.put(DEPLOYING, EnumSet.of(ACTIVE, FAILED));
        LEGAL.put(ACTIVE, EnumSet.of(RETIRED));
        LEGAL.put(FAILED, EnumSet.noneOf(ReleaseStatus.class));
        LEGAL.put(RETIRED, EnumSet.noneOf(ReleaseStatus.class));
    }

    public boolean canTransitionTo(ReleaseStatus next) {
        return next != null && Collections.unmodifiableSet(LEGAL.get(this)).contains(next);
    }

    /** @throws IllegalStateException if the move is not part of the lifecycle. */
    public ReleaseStatus transitionTo(ReleaseStatus next) {
        if (!canTransitionTo(next)) {
            throw new IllegalStateException(
                    "Illegal release transition " + this + " -> " + next);
        }
        return next;
    }

    /** Whether a release in this state may be bound and executed. */
    public boolean isActive() {
        return this == ACTIVE;
    }

    public boolean isTerminal() {
        return LEGAL.get(this).isEmpty();
    }
}
