package com.transitanalytics.classification;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Who runs the lines, from {@code classification/operators.yml}: the organiser (BKM) and the operating companies,
 * each with its lines and depot.
 */
@ConfigurationProperties("app.operators")
public record OperatorProperties(String organiser, List<Company> companies) {

    public OperatorProperties {
        companies = companies == null ? List.of() : List.copyOf(companies);
    }

    /** The company running a line (by its name, e.g. "103"), or null. */
    public Company companyOf(String line) {
        return companies.stream().filter(c -> c.lines().contains(line)).findFirst().orElse(null);
    }

    /** The company with this code, or null. */
    public Company company(String code) {
        return companies.stream().filter(c -> c.code().equals(code)).findFirst().orElse(null);
    }

    /** @param depot its depot's code in {@code map/depots.yml} */
    public record Company(String code, String name, String website, String depot, List<String> lines) {

        public Company {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }
}
