package org.casemgmt.engine.embedded;

import org.casemgmt.observation.ProcessCaseAuthority;

/** @deprecated use the engine-neutral {@link ProcessCaseAuthority} port. */
@Deprecated(forRemoval = false)
@FunctionalInterface
public interface ProcessCaseCorrelation extends ProcessCaseAuthority { }
