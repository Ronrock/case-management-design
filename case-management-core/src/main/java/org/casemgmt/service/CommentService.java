package org.casemgmt.service;

import org.casemgmt.domain.CaseIds;
import org.casemgmt.domain.CaseInstance;
import org.casemgmt.event.CaseEvent;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.event.EventTypes;
import org.casemgmt.repo.CaseRepository;
import org.casemgmt.repo.CommentRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Case comments with the internal/external visibility split (spec §4.8). {@link
 * CommentRepository#insert} already rejects anything other than {@code internal}/{@code
 * external}; this class does not duplicate that check.
 *
 * <p>{@code @Transactional}, like every other mutating service method in this module: {@code
 * add} writes a CM_COMMENT row, a CM_EVENT row and a CM_AUDIT_LOG row, and only actually commits
 * them together because it runs behind the Spring AOP proxy {@code TransactionManagerConfig}
 * installs (Task 15) — a plain {@code new CommentService(...)} leaves this annotation inert. See
 * {@code CollaborationServicesTransactionalIntegrationTest} for the proxied rollback proof.
 */
public class CommentService {

    private final CommentRepository comments;
    private final CaseRepository cases;
    private final EventPublisher publisher;

    public CommentService(CommentRepository comments, CaseRepository cases, EventPublisher publisher) {
        this.comments = comments;
        this.cases = cases;
        this.publisher = publisher;
    }

    @Transactional
    public CommentRepository.CommentRow add(String caseId, String text, String visibility, Actor actor) {
        CaseInstance c = cases.require(caseId);
        String id = CaseIds.newId();
        comments.insert(id, caseId, actor.userId(), text, visibility);

        publisher.publish(new CaseEvent(CaseIds.newId(), publisher.engineId(),
                EventTypes.COMMENT_ADDED, caseId, c.tenantId(), OffsetDateTime.now(),
                Map.of("commentId", id, "visibility", visibility, "author", actor.userId())));
        publisher.audit(caseId, c.tenantId(), actor.userId(), "comment.add", "Comment", id,
                null, Map.of("visibility", visibility));

        return comments.findByCase(caseId, null).stream()
                .filter(row -> row.id().equals(id)).findFirst().orElseThrow();
    }

    public List<CommentRepository.CommentRow> forCase(String caseId, String visibility) {
        return comments.findByCase(caseId, visibility);
    }
}
