package com.example.agentic.agent;

import com.example.agentic.exception.ValidationFailureException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Shared output-validation helpers: agents trust no provider output until its shape is checked. */
public abstract class AbstractAgent implements Agent {

    protected static void require(Map<String, Object> payload, String agent, String... keys) {
        for (String key : keys) {
            Object v = payload.get(key);
            if (v == null || (v instanceof java.util.Collection<?> c && c.isEmpty())) {
                throw new ValidationFailureException(agent + ": provider output is missing required field '" + key + "'");
            }
        }
    }

    @SuppressWarnings("unchecked")
    protected static List<Map<String, Object>> maps(Object value) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
        }
        return out;
    }

    protected static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : v.toString();
    }

    protected static void requireFields(List<Map<String, Object>> items, String agent, String what, String... keys) {
        for (Map<String, Object> item : items) {
            for (String k : keys) {
                if (item.get(k) == null || item.get(k).toString().isBlank()) {
                    throw new ValidationFailureException(agent + ": " + what + " entry is missing '" + k + "'");
                }
            }
        }
    }
}