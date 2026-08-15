package org.casemgmt.event;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.CaseIds;
import org.casemgmt.repo.EventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventRepositoryCursorSafetyTest extends OracleTestBase {

    @Test
    void eventAppendSerializesSequenceAllocationToCommitOrder() throws Exception {
        try (Connection first = dataSource().getConnection();
             Connection second = dataSource().getConnection();
             var pool = Executors.newSingleThreadExecutor()) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);

            EventRepository firstRepo = repositoryOn(first);
            EventRepository secondRepo = repositoryOn(second);

            long firstSeq = firstRepo.append(event("eng-a:1"));
            AtomicReference<Long> secondSeq = new AtomicReference<>();

            var blockedSecondAppend = pool.submit(() -> {
                secondSeq.set(secondRepo.append(event("eng-a:2")));
                second.commit();
                return null;
            });

            assertThatThrownBy(() -> blockedSecondAppend.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            assertThat(new EventRepository(jdbc()).after(0, 10))
                    .as("no higher sequence may become visible while a lower sequence is uncommitted")
                    .isEmpty();

            first.commit();
            blockedSecondAppend.get(10, TimeUnit.SECONDS);

            assertThat(secondSeq.get()).isGreaterThan(firstSeq);
            assertThat(new EventRepository(jdbc()).after(0, 10))
                    .extracting(EventRepository.StoredEvent::seq)
                    .containsExactly(firstSeq, secondSeq.get());
        }
    }

    @Test
    void bySeqsChunksAndReturnsExistingEventsInAscendingSequenceOrder() {
        EventRepository repo = new EventRepository(jdbc());
        long first = repo.append(event("eng-a:by-seq-1"));
        long second = repo.append(event("eng-a:by-seq-2"));
        long third = repo.append(event("eng-a:by-seq-3"));

        var requested = IntStream.range(0, 1_205)
                .mapToObj(i -> third + 1_000L + i)
                .collect(java.util.ArrayList<Long>::new, java.util.ArrayList::add, java.util.ArrayList::addAll);
        requested.add(third);
        requested.add(first);
        requested.add(second);

        assertThat(repo.bySeqs(requested))
                .extracting(EventRepository.StoredEvent::seq)
                .containsExactly(first, second, third);
    }

    @Test
    void tenantEventFeedIncludesGlobalEventsButNotAnotherTenantsEvents() {
        EventRepository repo = new EventRepository(jdbc());
        long global = repo.append(event("platform", null));
        long own = repo.append(event("eng-a:tenant-1", "t1"));
        repo.append(event("eng-a:tenant-2", "t2"));

        assertThat(repo.afterForTenant("t1", 0, 10))
                .extracting(EventRepository.StoredEvent::seq)
                .containsExactly(global, own);
    }

    private static EventRepository repositoryOn(Connection connection) {
        return new EventRepository(JdbcClient.create(new SingleConnectionDataSource(connection, true)));
    }

    private static CaseEvent event(String subject) {
        return event(subject, "t1");
    }

    private static CaseEvent event(String subject, String tenantId) {
        return new CaseEvent(CaseIds.newId(), "eng-a", "case.created", subject,
                tenantId, OffsetDateTime.now(), Map.of());
    }
}
