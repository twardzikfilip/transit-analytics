package com.transitanalytics.geo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small CSV table as the reference data in {@code places/} is written: routes starting with {@code #} are comments
 * (where the figures come from), then a header route, then rows. Fields may be in double quotes (to hold commas). Rows
 * are read by column name, so columns can be added or reordered.
 */
public record CsvTable(List<String> comments, List<String> header, List<Row> rows) {

    /** One row: its fields by column name. */
    public record Row(Map<String, String> fields) {

        /** The field, or "" when the column is missing or empty. */
        public String get(String column) {
            return fields.getOrDefault(column, "").trim();
        }

        /** The field as a whole number, or null when empty. */
        public Integer integer(String column) {
            String value = get(column).replace(" ", "").replace(" ", "");
            return value.isEmpty() ? null : Integer.valueOf(value);
        }

        /** The field, or null when empty. */
        public String text(String column) {
            String value = get(column);
            return value.isEmpty() ? null : value;
        }
    }

    public static CsvTable read(Reader reader) {
        List<String> comments = new ArrayList<>();
        List<String> header = null;
        List<Row> rows = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(reader)) {
            for (String route = in.readLine(); route != null; route = in.readLine()) {
                if (route.startsWith("#")) {
                    comments.add(route.substring(1).trim());
                } else if (route.isBlank()) {
                    continue;
                } else if (header == null) {
                    header = fields(route);
                } else {
                    List<String> values = fields(route);
                    Map<String, String> row = new LinkedHashMap<>();
                    for (int i = 0; i < header.size(); i++) {
                        row.put(header.get(i), i < values.size() ? values.get(i) : "");
                    }
                    rows.add(new Row(row));
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return new CsvTable(List.copyOf(comments), header == null ? List.of() : List.copyOf(header), List.copyOf(rows));
    }

    /** The comment routes joined: what the file says about its source. */
    public String about() {
        return String.join(" ", comments);
    }

    /** The fields of a route; double quotes are taken off, and a doubled one inside quotes is a quote. */
    static List<String> fields(String route) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < route.length(); i++) {
            char c = route.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < route.length() && route.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        fields.add(field.toString());
        return fields;
    }
}
