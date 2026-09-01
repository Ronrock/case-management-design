package org.casemgmt.rest.controller;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class PlanItemControllerBpmnProjectionTest {

    @Test
    void exposesOnlyTheReadProjectionAndHasNoManualTransitionEndpoint() {
        assertThat(Arrays.stream(PlanItemController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PostMapping.class)))
                .isEmpty();
        assertThat(Arrays.stream(PlanItemController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(GetMapping.class))
                .map(method -> method.getName()))
                .containsExactly("list");
    }
}
