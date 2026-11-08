package com.example.agentic.orchestration;

import com.example.agentic.exception.ValidationFailureException;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Evaluates node conditions of the form "path == literal", "path != literal" or "path nonEmpty". */
@Component
public class ConditionEvaluator {
    private static final Pattern EXPR = Pattern.compile("^\\s*([\\w.-]+)\\s*(==|!=|nonEmpty)\\s*(.*?)\\s*$");

    public boolean evaluate(String expression, WorkflowContext context) {
        if (expression == null || expression.isBlank()) {
            return true;
        }
        Matcher m = EXPR.matcher(expression);
        if (!m.matches()) {
            throw new ValidationFailureException("Unsupported condition: " + expression);
        }
        Object value = context.get(m.group(1));
        String op = m.group(2);
        if (op.equals("nonEmpty")) {
            return value instanceof Collection<?> c ? !c.isEmpty()
                    : value instanceof Map<?, ?> mm ? !mm.isEmpty()
                    : value != null && !value.toString().isBlank();
        }
        String literal = m.group(3).replaceAll("^['\"]|['\"]$", "");
        boolean equal = String.valueOf(value).equals(literal);
        return op.equals("==") == equal;
    }
}