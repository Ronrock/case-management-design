package org.casemgmt.permissions;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionDecisionTest {

    @Test
    void missingOrEmptyFieldDecisionDeniesDisclosure() {
        assertThat(new PermissionDecision("case-1", true, null).allowsField("title")).isFalse();
        assertThat(new PermissionDecision("case-1", true, List.of()).allowsField("title")).isFalse();
    }

    @Test
    void explicitFieldAndWildcardPermitDisclosure() {
        assertThat(new PermissionDecision("case-1", true, List.of("title"))
                .allowsField("title")).isTrue();
        assertThat(PermissionDecision.allow("case-1").allowsField("title")).isTrue();
        assertThat(PermissionDecision.deny("case-1").allowsField("title")).isFalse();
    }
}
