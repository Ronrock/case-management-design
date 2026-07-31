package org.casemgmt;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BuildEnvironmentTest {

    @Test
    void runsOnJava21OrLater() {
        assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(21);
    }

    @Test
    void springJdbcClientIsOnTheClasspath() throws Exception {
        // Fails with ClassNotFoundException if the Spring BOM did not resolve.
        Class.forName("org.springframework.jdbc.core.simple.JdbcClient");
    }
}
