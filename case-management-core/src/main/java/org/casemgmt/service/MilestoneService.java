package org.casemgmt.service;

import org.casemgmt.domain.CaseIds;
import org.casemgmt.domain.CaseInstance;
import org.casemgmt.error.CaseConflictException;
import org.casemgmt.error.NotFoundException;
import org.casemgmt.event.CaseEvent;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.event.EventTypes;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.MilestoneRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Manual achievement of an engine-projected milestone (spec §4.10).
 *
 * <p><b>Concurrent double-achieve is safe by construction.</b> {@link MilestoneRepository#achieve}
 * is the sole source of truth for "did THIS call just achieve it": its {@code UPDATE ... WHERE
 * ACHIEVED_ = 0} is naturally idempotent, and it returns the number of rows it actually matched.
 * This method reads the milestone first only to produce a friendly 404/409 message (the milestone
 * name) and to fail fast on a genuinely unknown id — it never uses that read's {@code achieved}
 * flag to decide whether to publish the event. Deciding from the earlier read instead of the
 * UPDATE's own result would reopen a TOCTOU race: two callers can both read {@code achieved =
 * false} before either writes, both proceed past that check, and both then publish a {@code
 * milestone.achieved} event even though the second UPDATE actually matched zero rows. Gating on
 * {@code rows == 0} from the UPDATE itself closes that window — Oracle serialises the two
 * UPDATEs on the same row, so only the caller whose UPDATE really flipped it ever reaches the
 * publish/audit calls below; the loser gets {@link CaseConflictException} and emits nothing. See
 * {@code CollaborationServicesTransactionalIntegrationTest
 * #concurrentDoubleAchieveProducesExactlyOneEvent} for a genuine two-thread proof.
 *
 * <p>{@code @Transactional} for the same reason as the other two services in this package: the
 * CM_MILESTONE update, the CM_EVENT row and the CM_AUDIT_LOG row must commit or roll back
 * together, which only happens behind the Spring AOP proxy (Task 15).
 */
public class MilestoneService {

    private final MilestoneRepository milestones;
    private final CaseRepository cases;
    private final EventPublisher publisher;

    public MilestoneService(MilestoneRepository milestones, CaseRepository cases,
                            EventPublisher publisher) {
        this.milestones = milestones;
        this.cases = cases;
        this.publisher = publisher;
    }

    @Transactional
    public MilestoneRepository.MilestoneRow achieve(String caseId, String milestoneId, Actor actor) {
        CaseInstance c = cases.require(caseId);
        MilestoneRepository.MilestoneRow row = milestones.findByCase(caseId).stream()
                .filter(m -> m.id().equals(milestoneId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Milestone", milestoneId));

        // Authoritative check: the UPDATE's own affected-row count, not row.achieved() above —
        // see class Javadoc for why the earlier read cannot safely make this decision.
        int rows = milestones.achieve(milestoneId, actor.userId());
        if (rows == 0) {
            throw new CaseConflictException("milestone-achieved",
                    "Milestone " + row.name() + " is already achieved", List.of());
        }

        publisher.publish(new CaseEvent(CaseIds.newId(), publisher.engineId(),
                EventTypes.MILESTONE_ACHIEVED, caseId, c.tenantId(), OffsetDateTime.now(),
                Map.of("milestoneId", milestoneId, "name", row.name())));
        publisher.audit(caseId, c.tenantId(), actor.userId(), "milestone.achieve", "Milestone",
                milestoneId, Map.of("achieved", false), Map.of("achieved", true));

        return milestones.findByCase(caseId).stream()
                .filter(m -> m.id().equals(milestoneId)).findFirst().orElseThrow();
    }

    public List<MilestoneRepository.MilestoneRow> forCase(String caseId) {
        return milestones.findByCase(caseId);
    }
}
