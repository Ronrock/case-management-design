package org.casemgmt.rules;

import jakarta.el.*;
import org.operaton.bpm.impl.juel.ExpressionFactoryImpl;
import org.operaton.bpm.impl.juel.SimpleContext;

import java.util.Map;

/**
 * JUEL with a deliberately minimal resolver chain.
 *
 * Only MapELResolver and ListELResolver are registered. There is NO BeanELResolver,
 * so expressions cannot call methods or walk into Java types — `${x.getClass()}`
 * fails to resolve instead of escaping the sandbox. Case definitions are deployed
 * over the API by other teams; without this, POST /case-definitions would be an
 * arbitrary-code-execution endpoint.
 */
public class JuelCriterionEvaluator implements CriterionEvaluator {

    private final ExpressionFactory factory = new ExpressionFactoryImpl();

    @Override
    public boolean matches(String expression, EvaluationContext context) {
        ELContext elContext = elContext(context);
        Object value;
        try {
            ValueExpression ve = factory.createValueExpression(elContext, expression, Object.class);
            value = ve.getValue(elContext);
        } catch (ELException e) {
            throw new CriterionEvaluationException(expression, "could not be evaluated", e);
        }
        if (value == null) {
            throw new CriterionEvaluationException(expression, "must evaluate to a boolean but was null", null);
        }
        if (!(value instanceof Boolean b)) {
            throw new CriterionEvaluationException(expression,
                    "must evaluate to a boolean but was " + value.getClass().getSimpleName(), null);
        }
        return b;
    }

    private ELContext elContext(EvaluationContext context) {
        CompositeELResolver resolver = new CompositeELResolver();
        resolver.add(new MapELResolver(true));    // read-only
        resolver.add(new ListELResolver(true));   // read-only

        SimpleContext ctx = new SimpleContext(resolver);
        bind(ctx, "case", context.caseAttributes());
        bind(ctx, "vars", context.variables());
        bind(ctx, "items", context.items());
        return ctx;
    }

    private void bind(SimpleContext ctx, String name, Map<String, ?> value) {
        ctx.setVariable(name, factory.createValueExpression(
                value == null ? Map.of() : value, Object.class));
    }
}
