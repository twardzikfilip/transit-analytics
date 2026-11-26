package com.transitanalytics.service;

import org.springframework.data.jpa.domain.Specification;

import java.util.Locale;

/** Small building blocks for list filters. Each returns {@code null} (no condition) when the value is empty. */
public final class Specs {

    private Specs() {
    }

    public static <E> Specification<E> all(Specification<E>... specs) {
        Specification<E> result = Specification.unrestricted();
        for (Specification<E> spec : specs) {
            if (spec != null) {
                result = result.and(spec);
            }
        }
        return result;
    }

    public static <E> Specification<E> equal(String attribute, Object value) {
        if (value == null || (value instanceof String s && s.isBlank())) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get(attribute), value);
    }

    /** Whether an attribute, possibly of a to-one association ("trip.feed"), is another value. */
    public static <E> Specification<E> notEqual(String attribute, Object value) {
        return (root, query, cb) -> {
            jakarta.persistence.criteria.Path<?> path = root;
            for (String step : attribute.split("\\.")) {
                path = path.get(step);
            }
            return cb.notEqual(path, value);
        };
    }

    /** Equality on the id of a to-one association, e.g. {@code idOf("route", 5)}. */
    public static <E> Specification<E> idOf(String association, Long id) {
        if (id == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get(association).get("id"), id);
    }

    /** Case-insensitive "contains" across one or more text attributes. */
    public static <E> Specification<E> contains(String text, String... attributes) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String pattern = "%" + text.strip().toLowerCase(Locale.ROOT).replace("\\", "\\\\")
                .replace("%", "\\%").replace("_", "\\_") + "%";
        return (root, query, cb) -> cb.or(java.util.Arrays.stream(attributes)
                .map(a -> cb.like(cb.lower(root.get(a)), pattern, '\\'))
                .toArray(jakarta.persistence.criteria.Predicate[]::new));
    }
}
