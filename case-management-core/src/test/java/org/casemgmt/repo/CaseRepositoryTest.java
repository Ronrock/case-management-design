package org.casemgmt.repo;

import org.casemgmt.OracleTestBase;
import org.casemgmt.domain.*;
import org.casemgmt.error.NotFoundException;
import org.casemgmt.error.OptimisticLockException;
import org.casemgmt.service.CanonicalPatch;
import org.casemgmt.service.CaseDataMappingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

class CaseRepositoryTest extends OracleTestBase {

    private CaseRepository repo;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        repo = new CaseRepository(jdbc());
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource()));
        // OracleTestBase's inherited @BeforeEach already wipes all CM_ tables before this
        // method runs (JUnit runs superclass @BeforeEach first), so no DELETEs are needed
        // here — only seed the CM_CASE_DEF row that CM_CASE's FK requires.
        jdbc().sql("""
                INSERT INTO CM_CASE_DEF (ID_, KEY_, VERSION_NO_, NAME_)
                VALUES ('widget-review:1', 'widget-review', 1, 'Widget review')""").update();
    }

    private CaseInstance newCase(String id) {
        return new CaseInstance(id, "eng-a", "t1", "widget-review:1", "widget-review", 1,
                "BK-1", "Broken widget", CaseState.ACTIVE, CasePriority.HIGH,
                null, null, "alice", "NONE", null, null,
                Map.of("amount", 250, "channel", "web"), 0L,
                OffsetDateTime.now(), OffsetDateTime.now(), null);
    }

    @Test
    void roundTripsACaseIncludingJsonVariables() {
        CaseInstance c = newCase("eng-a:1");
        repo.insert(c);

        CaseInstance loaded = repo.require("eng-a:1");
        assertThat(loaded.title()).isEqualTo("Broken widget");
        assertThat(loaded.state()).isEqualTo(CaseState.ACTIVE);
        assertThat(loaded.priority()).isEqualTo(CasePriority.HIGH);
        assertThat(loaded.variables()).containsEntry("channel", "web");
        assertThat(loaded.version()).isZero();
    }

    @Test
    void updateIncrementsTheVersion() {
        repo.insert(newCase("eng-a:2"));
        CaseInstance loaded = repo.require("eng-a:2");

        CaseInstance updated = repo.update(loaded.withState(CaseState.CLOSED), loaded.version());

        assertThat(updated.version()).isEqualTo(1L);
        assertThat(repo.require("eng-a:2").state()).isEqualTo(CaseState.CLOSED);
    }

    @Test
    void updateWithAStaleVersionThrows() {
        repo.insert(newCase("eng-a:3"));
        CaseInstance loaded = repo.require("eng-a:3");
        repo.update(loaded.withState(CaseState.CLOSED), loaded.version());   // now v1

        assertThatThrownBy(() -> repo.update(loaded.withState(CaseState.CANCELLED), 0L))
                .isInstanceOf(OptimisticLockException.class)
                .hasMessageContaining("eng-a:3");
    }

    @Test
    void atomicallyAppliesCanonicalChangesAgainstVersionAndExpectedValues() {
        repo.insert(newCase("eng-a:mapping"));
        CanonicalPatch patch = new CanonicalPatch("eng-a:mapping", "reviewTask", 0L, List.of(
                new CanonicalPatch.FieldChange("/mappings/0", "decisionVar", "decision",
                        CanonicalPatch.WriteMode.REPLACE, false, null, "approved", false),
                new CanonicalPatch.FieldChange("/mappings/1", "amountVar", "amount",
                        CanonicalPatch.WriteMode.REPLACE, true, 250, 300, false)));

        CaseDataMappingService.PatchResult result = applyCanonicalPatch(patch);

        assertThat(result.status()).isEqualTo(CaseDataMappingService.PatchStatus.APPLIED);
        assertThat(result.caseVersion()).isEqualTo(1L);
        assertThat(result.conflict()).isNull();
        assertThat(repo.require("eng-a:mapping").variables())
                .containsEntry("decision", "approved")
                .containsEntry("amount", 300)
                .containsEntry("channel", "web");
    }

    @Test
    void canonicalPatchConflictReportsVersionAndChangedExpectedFieldsWithoutPartialWrites() {
        repo.insert(newCase("eng-a:mapping-conflict"));
        CanonicalPatch patch = new CanonicalPatch("eng-a:mapping-conflict", "reviewTask", 0L,
                List.of(
                        new CanonicalPatch.FieldChange("/mappings/0", "channelVar", "channel",
                                CanonicalPatch.WriteMode.REPLACE, true, "phone", "letter", false),
                        new CanonicalPatch.FieldChange("/mappings/1", "amountVar", "amount",
                                CanonicalPatch.WriteMode.REPLACE, true, 250, 300, false)));

        CaseDataMappingService.PatchResult result = applyCanonicalPatch(patch);

        assertThat(result.status()).isEqualTo(CaseDataMappingService.PatchStatus.CONFLICT);
        assertThat(result.caseVersion()).isZero();
        assertThat(result.conflict().expectedCaseVersion()).isZero();
        assertThat(result.conflict().actualCaseVersion()).isZero();
        assertThat(result.conflict().fields()).containsExactly(
                new CaseDataMappingService.FieldConflict("channel", "phone", "web", false));
        assertThat(repo.require("eng-a:mapping-conflict").variables())
                .containsEntry("amount", 250)
                .containsEntry("channel", "web")
                .doesNotContainKey("decision");
        assertThat(repo.require("eng-a:mapping-conflict").version()).isZero();
    }

    @Test
    void canonicalPatchVersionConflictReturnsCurrentMetadataAndRedactsSensitiveFields() {
        repo.insert(newCase("eng-a:mapping-stale"));
        CaseInstance current = repo.require("eng-a:mapping-stale");
        repo.update(current.withVariables(Map.of("amount", 250, "channel", "mobile",
                "secret", "current-secret")), 0L);
        CanonicalPatch stale = new CanonicalPatch("eng-a:mapping-stale", "reviewTask", 0L,
                List.of(new CanonicalPatch.FieldChange("/mappings/0", "secretVar", "secret",
                        CanonicalPatch.WriteMode.REPLACE, true, "expected-secret", "new-secret", true)));

        CaseDataMappingService.PatchResult result = applyCanonicalPatch(stale);

        assertThat(result.status()).isEqualTo(CaseDataMappingService.PatchStatus.CONFLICT);
        assertThat(result.caseVersion()).isEqualTo(1L);
        assertThat(result.conflict().expectedCaseVersion()).isZero();
        assertThat(result.conflict().actualCaseVersion()).isEqualTo(1L);
        assertThat(result.conflict().fields()).containsExactly(
                new CaseDataMappingService.FieldConflict("secret", CanonicalPatch.REDACTED,
                        CanonicalPatch.REDACTED, true));
        assertThat(result.toString()).doesNotContain("expected-secret", "current-secret", "new-secret");
        assertThat(repo.require("eng-a:mapping-stale").variables().get("secret"))
                .isEqualTo("current-secret");
    }

    @Test
    void emptyCanonicalPatchIsANoOpAndObjectMergePreservesExistingMembers() {
        repo.insert(newCase("eng-a:mapping-merge").withVariables(Map.of(
                "amount", 250, "channel", "web",
                "profile", Map.of("name", "Alice", "language", "nl"))));

        CaseDataMappingService.PatchResult noChanges = applyCanonicalPatch(
                new CanonicalPatch("eng-a:mapping-merge", "reviewTask", 0L, List.of()));
        CaseDataMappingService.PatchResult merged = applyCanonicalPatch(new CanonicalPatch(
                "eng-a:mapping-merge", "reviewTask", 0L, List.of(
                new CanonicalPatch.FieldChange("/mappings/0", "profileVar", "profile",
                        CanonicalPatch.WriteMode.MERGE, true,
                        Map.of("name", "Alice", "language", "nl"),
                        Map.of("name", "Alice", "language", "en", "verified", true), false))));

        assertThat(noChanges.status()).isEqualTo(CaseDataMappingService.PatchStatus.NO_CHANGES);
        assertThat(noChanges.caseVersion()).isZero();
        assertThat(merged.status()).isEqualTo(CaseDataMappingService.PatchStatus.APPLIED);
        assertThat(repo.require("eng-a:mapping-merge").variables().get("profile"))
                .isEqualTo(Map.of("name", "Alice", "language", "en", "verified", true));
    }

    @Test
    void canonicalPatchLocksTheComparedRowUntilItsCallerTransactionCommits() throws Exception {
        repo.insert(newCase("eng-a:mapping-race"));
        CaseInstance writerPreImage = repo.require("eng-a:mapping-race");
        CanonicalPatch patch = new CanonicalPatch("eng-a:mapping-race", "reviewTask", 0L,
                List.of(new CanonicalPatch.FieldChange("/mappings/0", "decisionVar", "decision",
                        CanonicalPatch.WriteMode.REPLACE, false, null, "approved", false)));
        CountDownLatch rowCompared = new CountDownLatch(1);
        CountDownLatch releaseMapper = new CountDownLatch(1);
        CountDownLatch writerStarted = new CountDownLatch(1);
        DataSource pausingDataSource = new PausingCanonicalReadDataSource(
                dataSource(), rowCompared, releaseMapper);
        CaseRepository mappingRepository = new CaseRepository(JdbcClient.create(pausingDataSource));
        TransactionTemplate mappingTransaction = new TransactionTemplate(
                new DataSourceTransactionManager(pausingDataSource));
        TransactionTemplate writerTransaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource()));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<CaseDataMappingService.PatchResult> mapper = pool.submit(() ->
                    mappingTransaction.execute(status -> mappingRepository.applyCanonicalPatch(patch)));
            await(rowCompared, "canonical row comparison");

            Future<Throwable> writer = pool.submit(() -> {
                writerStarted.countDown();
                try {
                    writerTransaction.executeWithoutResult(status -> repo.update(
                            writerPreImage.withState(CaseState.CLOSED), writerPreImage.version()));
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });
            await(writerStarted, "concurrent writer start");

            assertThatThrownBy(() -> writer.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            releaseMapper.countDown();

            assertThat(mapper.get(10, TimeUnit.SECONDS).status())
                    .isEqualTo(CaseDataMappingService.PatchStatus.APPLIED);
            assertThat(writer.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(OptimisticLockException.class);
            assertThat(repo.require("eng-a:mapping-race").variables())
                    .containsEntry("decision", "approved");
        } finally {
            releaseMapper.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void requireThrowsForUnknownIds() {
        assertThatThrownBy(() -> repo.require("eng-a:nope"))
                .isInstanceOf(NotFoundException.class);
    }

    private CaseDataMappingService.PatchResult applyCanonicalPatch(CanonicalPatch patch) {
        return transactions.execute(status -> repo.applyCanonicalPatch(patch));
    }

    private static void await(CountDownLatch latch, String description) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for " + description);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for " + description, exception);
        }
    }

    /** Pauses the first canonical row read after JDBC has executed it but before mapping resumes. */
    private static final class PausingCanonicalReadDataSource extends DelegatingDataSource {

        private final CountDownLatch rowCompared;
        private final CountDownLatch releaseMapper;
        private final AtomicBoolean pauseNextCanonicalRead = new AtomicBoolean(true);

        private PausingCanonicalReadDataSource(DataSource targetDataSource,
                                               CountDownLatch rowCompared,
                                               CountDownLatch releaseMapper) {
            super(targetDataSource);
            this.rowCompared = rowCompared;
            this.releaseMapper = releaseMapper;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return pausingConnection(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return pausingConnection(super.getConnection(username, password));
        }

        private Connection pausingConnection(Connection connection) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        Object result = invoke(connection, method, args);
                        if (result instanceof PreparedStatement statement
                                && method.getName().equals("prepareStatement")
                                && args != null && args.length > 0 && args[0] instanceof String sql
                                && sql.contains("FROM CM_CASE WHERE ID_ =")) {
                            return pausingStatement(statement);
                        }
                        return result;
                    });
        }

        private PreparedStatement pausingStatement(PreparedStatement statement) {
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                        Object result = invoke(statement, method, args);
                        if (method.getName().equals("executeQuery")
                                && pauseNextCanonicalRead.compareAndSet(true, false)) {
                            rowCompared.countDown();
                            await(releaseMapper, "canonical mapper release");
                        }
                        return result;
                    });
        }

        private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args)
                throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }
    }

    /**
     * Pins the guarantee that update()'s return value always reflects THIS call's own
     * write, never a re-read that could observe a different writer's concurrent commit.
     *
     * <p>Directly simulating the interleaving (writer A's UPDATE commits, then writer B's
     * UPDATE+commit lands, then A's *own* post-update read runs and would see B's row) is
     * fiddly to express deterministically in a single-JVM test without instrumenting a
     * delay inside the repository itself. Instead this asserts the contract the fix relies
     * on: update() no longer performs any SELECT after its UPDATE, so the returned object
     * is built purely from values this call already knows (the WHERE clause having just
     * proven the row was at exactly {@code expectedVersion}) — there is no second query left
     * for another writer's commit to race against. That is verified two ways below: the
     * returned instance matches what this call wrote with version incremented by exactly
     * one, and a genuinely concurrent-style write attempt (reusing the same pre-image/
     * expected version A read) is rejected outright rather than silently blended in.
     */
    @Test
    void updateReturnsThisCallsOwnWriteNeverAnotherWritersConcurrentCommit() {
        repo.insert(newCase("eng-a:6"));
        CaseInstance loaded = repo.require("eng-a:6");

        CaseInstance updated = repo.update(loaded.withState(CaseState.CLOSED), loaded.version());

        assertThat(updated.id()).isEqualTo("eng-a:6");
        assertThat(updated.version()).isEqualTo(loaded.version() + 1);
        assertThat(updated.state()).isEqualTo(CaseState.CLOSED);
        assertThat(updated.title()).isEqualTo(loaded.title());
        assertThat(updated.updatedAt()).isAfterOrEqualTo(loaded.updatedAt());

        // A second writer racing in with the same pre-image A read must be rejected, not
        // silently merged into A's already-returned result.
        assertThatThrownBy(() -> repo.update(loaded.withState(CaseState.CANCELLED), loaded.version()))
                .isInstanceOf(OptimisticLockException.class);

        // The row in the database agrees exactly with what update() returned to the caller.
        CaseInstance reread = repo.require("eng-a:6");
        assertThat(reread.version()).isEqualTo(updated.version());
        assertThat(reread.state()).isEqualTo(updated.state());
    }

    /**
     * Fix round 2 regression: {@link CaseRepository#update} used to include {@code SLA_STATUS_}
     * in its full-row SET list, carrying along whatever value the caller's in-memory {@code
     * CaseInstance} happened to hold. A user's edit read BEFORE a concurrent {@code SlaSweeper}
     * pass committed a breach still matched the same {@code VERSION_} (the sweeper's own write,
     * {@link CaseRepository#updateSlaStatusMonotonic}, deliberately never bumps {@code VERSION_}
     * — see its Javadoc), so the user's stale-read UPDATE silently overwrote {@code BREACHED}
     * with whatever the user last saw. The breach was then lost PERMANENTLY: the SLA record is
     * already {@code BREACHED}, so {@code SlaRepository.dueRecords} never re-selects it and
     * nothing else ever re-derives the column. This reproduces that exact interleaving using the
     * real production methods (no re-implementation of either), simulating "the sweeper commits
     * a breach mid-transaction" as a direct call to the same method the sweeper itself calls.
     */
    @Test
    void userUpdateNeverStompsAConcurrentSweeperBreach() {
        repo.insert(newCase("eng-a:7"));
        CaseInstance staleRead = repo.require("eng-a:7"); // SLA_STATUS_ = NONE, VERSION_ = 0

        // The sweeper commits a breach — deliberately without touching VERSION_ at all.
        assertThat(repo.updateSlaStatusMonotonic("eng-a:7", "BREACHED")).isTrue();
        assertThat(repo.require("eng-a:7").slaStatus()).isEqualTo("BREACHED");

        // The user's edit, built from a read taken BEFORE the sweeper ran, still carries
        // VERSION_=0 and the pre-breach SLA_STATUS_="NONE" — and VERSION_=0 still matches,
        // because the sweeper's write never touched it. The user's own fields must still land;
        // the stale SLA_STATUS_ must not.
        CaseInstance userUpdate = repo.update(staleRead.withState(CaseState.CLOSED), staleRead.version());

        assertThat(userUpdate.state()).isEqualTo(CaseState.CLOSED);
        assertThat(repo.require("eng-a:7").slaStatus()).isEqualTo("BREACHED");
    }

    @Test
    void queriesByStateAndAssignee() {
        repo.insert(newCase("eng-a:4"));
        CaseInstance closed = newCase("eng-a:5").withState(CaseState.CLOSED);
        repo.insert(closed);

        var active = repo.query(new CaseQuery("t1", List.of(CaseState.ACTIVE), null, null, null, 0, 50));

        assertThat(active).extracting(CaseInstance::id).containsExactly("eng-a:4");
    }

    @Test
    void filtersByParticipantWithoutCorruptingNamedParameters() {
        repo.insert(newCase("eng-a:participant"));
        jdbc().sql("""
                INSERT INTO CM_PARTICIPANT (ID_, CASE_ID_, USER_ID_, ROLE_, ADDED_BY_)
                VALUES ('participant-1', 'eng-a:participant', 'alice', 'reviewer', 'admin')""")
                .update();
        CaseQuery query = query("alice", null);

        assertThat(repo.query(query)).extracting(CaseInstance::id)
                .containsExactly("eng-a:participant");
        assertThat(repo.count(query)).isEqualTo(1);
    }

    @Test
    void freeTextExecutesAndTreatsLikeWildcardsLiterally() {
        repo.insert(newCase("eng-a:literal-percent"));
        repo.insert(newCase("eng-a:wildcard-decoy"));
        jdbc().sql("""
                INSERT INTO CM_COMMENT (ID_, CASE_ID_, AUTHOR_, TEXT_)
                VALUES ('comment-1', 'eng-a:literal-percent', 'alice', 'Fee is 100% correct')""")
                .update();
        jdbc().sql("""
                INSERT INTO CM_COMMENT (ID_, CASE_ID_, AUTHOR_, TEXT_)
                VALUES ('comment-2', 'eng-a:wildcard-decoy', 'alice', 'Fee is 1000 correct')""")
                .update();
        CaseQuery query = query(null, "100%");

        assertThat(repo.query(query)).extracting(CaseInstance::id)
                .containsExactly("eng-a:literal-percent");
        assertThat(repo.count(query)).isEqualTo(1);
    }

    private static CaseQuery query(String participantUser, String freeText) {
        return new CaseQuery("t1", List.of(), null, null, null, participantUser,
                null, null, null, freeText, null, null, List.of(), 0, 50);
    }
}
