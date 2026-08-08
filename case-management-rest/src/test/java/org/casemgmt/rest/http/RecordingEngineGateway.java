package org.casemgmt.rest.http;

import org.casemgmt.engine.EngineGateway;
import org.casemgmt.engine.EngineProcessRef;
import org.casemgmt.engine.EngineTaskQuery;
import org.casemgmt.engine.EngineTaskRef;
import org.casemgmt.engine.HumanTaskRequest;
import org.casemgmt.engine.StartProcessRequest;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An {@link EngineGateway} that behaves like embedded mode: every call succeeds synchronously
 * and hands back a real id, so a created human task lands {@code ENGINE_SYNC_ = SYNCED} and is
 * claimable. The controllers under test never touch the engine directly — they exercise it only
 * through the services — so a real Operaton engine would add nothing these tests could assert
 * that this does not, while adding a container and a startup to every run.
 */
class RecordingEngineGateway implements EngineGateway {

    private final AtomicLong ids = new AtomicLong();

    @Override
    public EngineTaskRef createHumanTask(HumanTaskRequest request) {
        return new EngineTaskRef("engine-task-" + ids.incrementAndGet(), request.name(),
                request.assignee(), request.caseId(), OffsetDateTime.now());
    }

    @Override
    public void claimTask(String engineTaskId, String userId) { }

    @Override
    public void completeTask(String engineTaskId, Map<String, Object> variables) { }

    @Override
    public EngineProcessRef startProcess(StartProcessRequest request) {
        return new EngineProcessRef("engine-proc-" + ids.incrementAndGet(),
                request.processDefinitionKey());
    }

    @Override
    public void cancelProcess(String processInstanceId, String reason) { }

    @Override
    public List<EngineTaskRef> findTasks(EngineTaskQuery query) {
        return List.of();
    }
}
