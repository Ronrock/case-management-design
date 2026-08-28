package org.casemgmt.engine;

/**
 * Remote command boundary. Implementations return only typed, command-bound facts; raw
 * response bodies and exception text never enter durable command state.
 */
@FunctionalInterface
public interface EngineCommandTransport {

    CommandDispatchOutcome dispatch(ProductionEngineCommandStore.StoredCommand command);
}
