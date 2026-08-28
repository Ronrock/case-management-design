package org.casemgmt.engine;

import org.casemgmt.repo.EngineCommandRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyMigrationBoundaryTest {

    @Test
    void legacyEvidenceHasNoPublicRepositoryConstructionSurface() {
        assertThat(Modifier.isPublic(LegacyDoneCommandMigration.class.getModifiers())).isFalse();
        assertThat(Arrays.stream(LegacyDoneCommandMigration.class.getDeclaredMethods())
                .anyMatch(method -> Modifier.isPublic(method.getModifiers()))).isFalse();
        assertThat(Arrays.stream(EngineCommandRepository.class.getMethods())
                .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                .map(Class::getName)
                .anyMatch(name -> name.contains("LegacyDone")
                        || name.contains("LegacyConfirmation"))).isFalse();
        assertThat(Arrays.stream(EngineCommandRepository.class.getMethods())
                .map(method -> method.getName())
                .filter(name -> name.equals("commitLeaseDecision")
                        || name.equals("appendActionAndTransition"))).isEmpty();
    }
}
