package com.transitanalytics;

import com.transitanalytics.dto.TripAnomalyDto.Phrase;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Phrases written in English with the frontend's own texts ({@code frontend/src/i18n/en.json}, {@code tripNotes}),
 * as the page shows them, so tests read as sentences. A key or a value the text lacks fails the test, which keeps the
 * backend's phrases and the frontend's texts in step.
 */
public final class EnglishPhrases {

    private static final Path EN = Path.of("..", "frontend", "src", "i18n", "en.json");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*(\\w+)\\s*}}");
    private static JsonNode notes;

    private EnglishPhrases() {
    }

    /** Phrases as the page writes them: one after another, separated by dots. */
    public static String text(List<Phrase> phrases) {
        return phrases == null ? null : phrases.stream().map(EnglishPhrases::text).collect(Collectors.joining(" · "));
    }

    public static String text(Phrase phrase) {
        JsonNode node = notes().get(phrase.key());
        if (node == null) {
            throw new AssertionError("No English text for tripNotes." + phrase.key());
        }
        Map<String, Object> params = phrase.params();
        Matcher m = PLACEHOLDER.matcher(node.asString());
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            if (!params.containsKey(m.group(1))) {
                throw new AssertionError("tripNotes." + phrase.key() + " needs " + m.group(1) + ", got " + params);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value(params.get(m.group(1)))));
        }
        return m.appendTail(out).toString();
    }

    private static String value(Object value) {
        if (value instanceof Double d && d == Math.rint(d)) {
            return String.valueOf(d.longValue());
        }
        return String.valueOf(value);
    }

    private static synchronized JsonNode notes() {
        if (notes == null) {
            try {
                notes = JsonMapper.builder().build().readTree(Files.readString(EN)).get("tripNotes");
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Can't read " + EN.toAbsolutePath(), e);
            }
        }
        return notes;
    }
}
