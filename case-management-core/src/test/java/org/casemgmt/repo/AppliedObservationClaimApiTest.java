package org.casemgmt.repo;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** A caller may retain an ownership claim, but must never mint or forge one. */
class AppliedObservationClaimApiTest {

    @Test
    void claimHasNoPublicConstructionPath() {
        assertThat(AppliedObservationRepository.Claim.class.getConstructors()).isEmpty();
        assertThat(Arrays.stream(AppliedObservationRepository.Claim.class.getDeclaredConstructors()))
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()));
    }
}
