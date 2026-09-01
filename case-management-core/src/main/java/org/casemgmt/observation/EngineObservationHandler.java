package org.casemgmt.observation;

/** Applies engine-neutral lifecycle facts through the case platform's atomic effect boundary. */
public interface EngineObservationHandler {

    ApplyResult apply(EngineObservation observation);
}
