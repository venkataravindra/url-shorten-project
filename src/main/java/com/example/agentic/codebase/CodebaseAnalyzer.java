package com.example.agentic.codebase;

import jakarta.persistence.Entity;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.data.repository.Repository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Brownfield baseline: a real reflection scan of the URL shortener module that is part of this very application.
 * It reports what is actually on the classpath (entities, endpoints, services, repositories, schedulers and,
 * when test classes are present, test class names). It never reads source files and never executes anything.
 */
@Component
public class CodebaseAnalyzer {
    public static final String BASE_PACKAGE = "com.example.agentic.urlshortener";

    public Map<String, Object> analyzeUrlShortenerModule() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", "Reflection scan of package " + BASE_PACKAGE + " on the running classpath");
        result.put("entities", entities());
        result.put("controllers", controllers());
        result.put("services", names(scan(new AnnotationTypeFilter(Service.class), false)));
        result.put("repositories", names(scan(new AssignableTypeFilter(Repository.class), true)));
        result.put("schedulerPresent", schedulerPresent());
        List<String> tests = names(scan(new RegexPatternTypeFilter(Pattern.compile(".*Tests?$")), false));
        result.put("existingTests", tests);
        result.put("testsDiscoverable", !tests.isEmpty());
        return result;
    }

    private List<Map<String, Object>> entities() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Class<?> c : scan(new AnnotationTypeFilter(Entity.class), false)) {
            List<String> fields = new ArrayList<>();
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && !f.isSynthetic()) {
                    fields.add(f.getName());
                }
            }
            out.add(Map.of("name", c.getSimpleName(), "fields", fields));
        }
        return out;
    }

    private List<Map<String, Object>> controllers() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Class<?> c : scan(new AnnotationTypeFilter(RestController.class), false)) {
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(c, RequestMapping.class);
            String basePath = base != null && base.path().length > 0 ? base.path()[0] : "";
            List<String> endpoints = new ArrayList<>();
            for (Method m : c.getDeclaredMethods()) {
                RequestMapping rm = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                if (rm != null) {
                    String verb = rm.method().length > 0 ? rm.method()[0].name() : "ANY";
                    String path = rm.path().length > 0 ? rm.path()[0] : "";
                    endpoints.add(verb + " " + basePath + path);
                }
            }
            Collections.sort(endpoints);
            out.add(Map.of("name", c.getSimpleName(), "endpoints", endpoints));
        }
        return out;
    }

    private boolean schedulerPresent() {
        for (Class<?> c : scan(new AnnotationTypeFilter(Component.class), false)) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.isAnnotationPresent(Scheduled.class)) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<Class<?>> scan(org.springframework.core.type.filter.TypeFilter filter, boolean allowInterfaces) {
        ClassPathScanningCandidateComponentProvider provider = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(org.springframework.beans.factory.annotation.AnnotatedBeanDefinition d) {
                return allowInterfaces ? d.getMetadata().isInterface() || d.getMetadata().isConcrete() : super.isCandidateComponent(d);
            }
        };
        provider.addIncludeFilter(filter);
        List<Class<?>> classes = new ArrayList<>();
        for (BeanDefinition bd : provider.findCandidateComponents(BASE_PACKAGE)) {
            try {
                classes.add(Class.forName(bd.getBeanClassName(), false, getClass().getClassLoader()));
            } catch (ClassNotFoundException ignored) {
                // class vanished between scan and load; skip
            }
        }
        classes.sort(Comparator.comparing(Class::getName));
        return classes;
    }

    private static List<String> names(List<Class<?>> classes) {
        return classes.stream().map(Class::getSimpleName).toList();
    }
}