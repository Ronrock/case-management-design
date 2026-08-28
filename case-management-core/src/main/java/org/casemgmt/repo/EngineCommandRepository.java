package org.casemgmt.repo;

import org.casemgmt.engine.ProductionEngineCommandStore;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.time.Clock;

/** Public repository facade; legacy migration reconstruction remains engine-internal. */
public class EngineCommandRepository extends ProductionEngineCommandStore {

    public EngineCommandRepository(JdbcClient jdbc) {
        super(jdbc);
    }

    public EngineCommandRepository(DataSource dataSource) {
        super(dataSource);
    }

    public EngineCommandRepository(DataSource dataSource, Clock clock) {
        super(dataSource, clock);
    }
}
