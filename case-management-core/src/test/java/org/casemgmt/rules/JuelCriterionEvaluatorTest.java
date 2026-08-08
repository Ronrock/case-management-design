package org.casemgmt.rules;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class JuelCriterionEvaluatorTest {

    private final CriterionEvaluator evaluator = new JuelCriterionEvaluator();

    private EvaluationContext context() {
        return new EvaluationContext(
                Map.of("state", "ACTIVE", "priority", "HIGH"),
                Map.of("amount", 1500, "channel", "web", "escalated", false),
                Map.of("assess", Map.of("state", "COMPLETED"),
                       "investigate", Map.of("state", "AVAILABLE")));
    }

    @Test
    void readsSiblingPlanItemState() {
        assertThat(evaluator.matches("${items.assess.state == 'COMPLETED'}", context())).isTrue();
        assertThat(evaluator.matches("${items.investigate.state == 'COMPLETED'}", context())).isFalse();
    }

    @Test
    void readsCaseVariablesAndAttributes() {
        assertThat(evaluator.matches("${vars.amount > 1000}", context())).isTrue();
        assertThat(evaluator.matches("${case.priority == 'HIGH'}", context())).isTrue();
        assertThat(evaluator.matches("${vars.escalated}", context())).isFalse();
    }

    @Test
    void combinesConditions() {
        assertThat(evaluator.matches(
                "${items.assess.state == 'COMPLETED' && vars.amount > 1000}", context())).isTrue();
    }

    @Test
    void emptyCriteriaListMeansNoGate() {
        assertThat(evaluator.allMatch(List.of(), context())).isTrue();
    }

    @Test
    void allMatchRequiresEveryExpression() {
        assertThat(evaluator.allMatch(
                List.of("${vars.amount > 1000}", "${case.state == 'ACTIVE'}"), context())).isTrue();
        assertThat(evaluator.allMatch(
                List.of("${vars.amount > 1000}", "${case.state == 'CLOSED'}"), context())).isFalse();
    }

    @Test
    void unknownVariablesAreNullRatherThanExplosive() {
        assertThat(evaluator.matches("${vars.doesNotExist == null}", context())).isTrue();
    }

    @Test
    void nonBooleanResultIsRejected() {
        assertThatThrownBy(() -> evaluator.matches("${vars.amount}", context()))
                .isInstanceOf(CriterionEvaluationException.class)
                .hasMessageContaining("must evaluate to a boolean");
    }

    @Test
    void malformedExpressionIsRejectedWithTheExpressionInTheMessage() {
        assertThatThrownBy(() -> evaluator.matches("${items.assess.state ==}", context()))
                .isInstanceOf(CriterionEvaluationException.class)
                .hasMessageContaining("items.assess.state ==");
    }

    // ---- sandbox ----

    // NOTE: the "obvious" form of this test, `${case.class.name == 'x'}`, does NOT work and must
    // not be reintroduced. `case` is bound to a plain Map, so MapELResolver claims the property
    // lookup unconditionally and returns null for the missing key "class" -- the exact same code
    // path as a genuine miss like `case.doesNotExist`. The expression then quietly evaluates to
    // `null == 'x'` -> false, never throwing. Verified empirically: wiring a BeanELResolver into
    // the chain and re-running the same expression produces the identical `false`, no exception --
    // MapELResolver claims the Map base before BeanELResolver ever gets a turn, so this expression
    // cannot distinguish the sandboxed chain from a vulnerable one. `vars.amount` is an Integer,
    // not a Map/List, so no resolver in the sandboxed chain can handle a property lookup on it and
    // the expression genuinely throws -- while a chain with BeanELResolver added resolves it via
    // bean introspection to the real Class object and returns false instead of throwing. That
    // makes this expression an actual discriminator for the vulnerability, unlike the original.
    @Test
    void cannotReachJavaTypesThroughValuesThatAreNotMapsOrLists() {
        assertThatThrownBy(() -> evaluator.matches("${vars.amount.class.name == 'x'}", context()))
                .isInstanceOf(CriterionEvaluationException.class);
    }

    @Test
    void cannotInvokeMethodsOnValues() {
        assertThatThrownBy(() -> evaluator.matches("${vars.channel.getClass() != null}", context()))
                .isInstanceOf(CriterionEvaluationException.class);
    }
}
