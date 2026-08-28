package org.casemgmt.repo;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Non-Docker regression guard for the Oracle confirmation predicate. */
class LinkedProcessConfirmationSqlStaticValidationTest {

    @Test
    void legacyConfirmationDoesNotCompareStoredDefinitionIdentityToNull() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/org/casemgmt/repo/LinkedProcessRepository.java"))
                .replaceAll("\\s+", " ");

        assertThat(source)
                .contains("(:processDefinitionId IS NULL OR PROC_DEF_ID_ IS NULL "
                        + "OR PROC_DEF_ID_ = :processDefinitionId)")
                .contains("(:processDefinitionKey IS NULL OR PROC_DEF_KEY_ IS NULL "
                        + "OR PROC_DEF_KEY_ = :processDefinitionKey)");
    }
}
