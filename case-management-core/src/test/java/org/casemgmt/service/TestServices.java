package org.casemgmt.service;

import org.casemgmt.engine.EngineGateway;
import org.casemgmt.domain.CaseDefinition;
import org.casemgmt.domain.CaseIds;
import org.casemgmt.domain.CaseInstance;
import org.casemgmt.domain.CasePriority;
import org.casemgmt.domain.CaseState;
import org.casemgmt.event.EventPublisher;
import org.casemgmt.orchestration.BpmnOrchestration;
import org.casemgmt.orchestration.CaseOrchestrationRegistry;
import org.casemgmt.orchestration.EngineDeploymentIdentity;
import org.casemgmt.repo.*;
import org.casemgmt.release.BindingStatus;
import org.casemgmt.release.CaseDefinitionRelease;
import org.casemgmt.release.CaseDefinitionVersionBinding;
import org.casemgmt.release.ReleaseKind;
import org.casemgmt.release.ReleaseStatus;
import org.casemgmt.rules.*;
import org.casemgmt.sla.SlaService;
import org.casemgmt.sla.SlaSweeper;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Map;

/**
 * Test-only wiring so each test does not repeat eight constructor calls.
 *
 * <p>Takes a {@link DataSource}, not a pre-built {@link JdbcClient}: {@link CaseDefinitionRepository}
 * needs the {@link DataSource} directly (it runs its own atomic multi-statement insert on a
 * single borrowed connection — see its Javadoc), so a plain {@code JdbcClient} is not enough to
 * construct every repository this wiring needs. Every other repository is built from a
 * {@link JdbcClient} wrapping the same {@link DataSource}, so this opens no second connection
 * pool.
 */
public final class TestServices {

    private TestServices() {}

    /** Creates the minimal BPMN definition/case authority needed by non-orchestration tests. */
    public static CaseDefinition deployBpmnDefinition(DataSource dataSource, String key,
                                                       String tenantId) {
        return new CaseDefinitionService(new CaseDefinitionRepository(dataSource)).deployBpmn(
                key,
                """
                {"name":"Test BPMN case","roles":[],"attachmentCategories":[],"forms":{}}
                """,
                "system", tenantId);
    }

    /** Publishes the exact active release identity required for starting the supplied definition. */
    public static EngineDeploymentIdentity activateBpmnDefinition(
            DataSource dataSource, CaseDefinition definition) {
        String prefix = definition.id() + ":";
        EngineDeploymentIdentity identity = new EngineDeploymentIdentity(
                "deployment-" + definition.versionNo(),
                definition.key() + ":" + definition.versionNo() + ":exact",
                definition.key(), definition.versionNo(), definition.tenantId());
        var releases = new CaseDefinitionReleaseRepository(dataSource);
        CaseDefinitionRelease orchestration = release(prefix + "orchestration", definition,
                ReleaseKind.ORCHESTRATION, "application/zip", identity);
        CaseDefinitionRelease contract = release(prefix + "contract", definition,
                ReleaseKind.CONTRACT, "application/json", null);
        CaseDefinitionRelease presentation = release(prefix + "presentation", definition,
                ReleaseKind.PRESENTATION, "application/json", null);
        releases.insert(orchestration);
        releases.insert(contract);
        releases.insert(presentation);

        OffsetDateTime now = OffsetDateTime.now();
        new CaseDefinitionVersionBindingRepository(dataSource).insert(
                new CaseDefinitionVersionBinding(definition.id(), definition.key(),
                        definition.tenantId(), orchestration.id(), orchestration.sha256(),
                        contract.id(), contract.sha256(), presentation.id(), presentation.sha256(),
                        ReleaseStatus.ACTIVE, definition.orchestrationMode(), BindingStatus.ACTIVE,
                        identity, null, now, now, null, "system"));
        return identity;
    }

    private static CaseDefinitionRelease release(String id, CaseDefinition definition,
                                                  ReleaseKind kind, String mediaType,
                                                  EngineDeploymentIdentity identity) {
        byte[] content = id.getBytes(StandardCharsets.UTF_8);
        return CaseDefinitionRelease.storedWithEngineIdentity(id, definition.key(),
                definition.tenantId(), kind, mediaType, content, sha256(content),
                ReleaseStatus.ACTIVE, identity, null, "system");
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM does not provide SHA-256", impossible);
        }
    }

    /** Inserts a BPMN case without starting an engine; useful when orchestration is not under test. */
    public static CaseInstance insertBpmnCase(DataSource dataSource, CaseDefinition definition,
                                               String title, String initiator) {
        OffsetDateTime now = OffsetDateTime.now();
        CaseInstance instance = new CaseInstance(CaseIds.newCaseId("eng-test"), "eng-test",
                definition.tenantId(), definition.id(), definition.key(), definition.versionNo(),
                null, title, CaseState.ACTIVE, CasePriority.MEDIUM, null, null, initiator, "NONE",
                null, null, Map.of(), 0L, now, now, null);
        new CaseRepository(dataSource).insert(instance);
        return instance;
    }

    public static CaseService caseService(DataSource dataSource, EngineGateway gateway) {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        var publisher = new EventPublisher(new EventRepository(jdbc), new AuditRepository(jdbc),
                new WebhookRepository(jdbc), "org.example.cm", "eng-test");
        var bindings = new CaseDefinitionVersionBindingRepository(dataSource);
        var processes = new LinkedProcessRepository(jdbc);
        var bpmn = new BpmnOrchestration(gateway, processes, bindings);
        return new CaseService(new CaseRepository(dataSource),
                new CaseDefinitionRepository(dataSource),
                new PlanItemRepository(jdbc), new ParticipantRepository(jdbc),
                new CaseOrchestrationRegistry(java.util.List.of(bpmn)), publisher, "eng-test");
    }

    public static CaseTaskService taskService(DataSource dataSource, EngineGateway gateway) {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        var publisher = new EventPublisher(new EventRepository(jdbc), new AuditRepository(jdbc),
                new WebhookRepository(jdbc), "org.example.cm", "eng-test");
        return new CaseTaskService(new CaseTaskRepository(jdbc), new CaseRepository(jdbc),
                new CaseDefinitionRepository(dataSource), gateway, new FormValidator(),
                publisher);
    }

    public static CommentService commentService(DataSource dataSource) {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        var publisher = new EventPublisher(new EventRepository(jdbc), new AuditRepository(jdbc),
                new WebhookRepository(jdbc), "org.example.cm", "eng-test");
        return new CommentService(new CommentRepository(jdbc), new CaseRepository(jdbc), publisher);
    }

    public static DocumentService documentService(DataSource dataSource) {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        var publisher = new EventPublisher(new EventRepository(jdbc), new AuditRepository(jdbc),
                new WebhookRepository(jdbc), "org.example.cm", "eng-test");
        return new DocumentService(new DocumentRepository(jdbc), new CaseRepository(jdbc), publisher);
    }

    public static MilestoneService milestoneService(DataSource dataSource) {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        var publisher = new EventPublisher(new EventRepository(jdbc), new AuditRepository(jdbc),
                new WebhookRepository(jdbc), "org.example.cm", "eng-test");
        return new MilestoneService(new MilestoneRepository(jdbc), new CaseRepository(jdbc), publisher);
    }

    public static LinkedProcessService processService(DataSource dataSource, EngineGateway gateway) {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        var publisher = new EventPublisher(new EventRepository(jdbc), new AuditRepository(jdbc),
                new WebhookRepository(jdbc), "org.example.cm", "eng-test");
        return new LinkedProcessService(new LinkedProcessRepository(jdbc), new CaseRepository(jdbc),
                gateway, publisher);
    }

    /**
     * Takes a {@link JdbcClient} directly, unlike the factories above: unlike
     * {@link CaseDefinitionRepository}, nothing {@link SlaService} depends on needs a raw
     * {@link DataSource}, so there is no reason to require callers to hold one just to build
     * a {@link JdbcClient} from it here.
     */
    public static SlaService slaService(JdbcClient jdbc) {
        var publisher = new EventPublisher(new EventRepository(jdbc), new AuditRepository(jdbc),
                new WebhookRepository(jdbc), "org.example.cm", "eng-test");
        return new SlaService(new SlaRepository(jdbc), new CaseRepository(jdbc), publisher);
    }

    public static SlaSweeper slaSweeper(JdbcClient jdbc) {
        var publisher = new EventPublisher(new EventRepository(jdbc), new AuditRepository(jdbc),
                new WebhookRepository(jdbc), "org.example.cm", "eng-test");
        return new SlaSweeper(new SlaRepository(jdbc), new CaseRepository(jdbc), publisher);
    }
}
