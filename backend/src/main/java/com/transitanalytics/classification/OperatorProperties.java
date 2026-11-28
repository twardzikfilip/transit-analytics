package com.transitanalytics.classification;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Who runs the routes, from {@code classification/operators.yml}: the organiser (BKM) and the operating companies,
 * each with its routes, buses and depot.
 *
 * @param sharedAgency the agency whose buses the companies run between them ("BKM")
 */
@ConfigurationProperties("app.operators")
public record OperatorProperties(String organiser, String sharedAgency, List<Company> companies) {

    public OperatorProperties {
        companies = companies == null ? List.of() : List.copyOf(companies);
    }

    /** The company running a route (by its name, e.g. "103"), or null. */
    public Company companyOf(String route) {
        return companies.stream().filter(c -> c.routes().contains(route)).findFirst().orElse(null);
    }

    /** The company with this code, or null. */
    public Company company(String code) {
        return companies.stream().filter(c -> c.code().equals(code)).findFirst().orElse(null);
    }

    /**
     * Who runs a bus: the company its number belongs to for a bus of the shared agency, else its own agency; null when
     * not known.
     */
    public String operatingAgency(String agency, String vehicleNumber) {
        if (agency == null || !agency.equals(sharedAgency)) {
            return agency;
        }
        return companies.stream().filter(c -> c.runs(vehicleNumber)).map(Company::code).findFirst().orElse(null);
    }

    /**
     * @param depot    its depot's code in {@code map/depots.yml}
     * @param vehicles the numbers of its buses: single ones or ranges ("031-040")
     */
    public record Company(String code, String name, String website, String depot, List<String> routes,
                          List<String> vehicles) {

        private static final Pattern NUMBERS = Pattern.compile("(\\d+)(?:-(\\d+))?");

        public Company {
            routes = routes == null ? List.of() : List.copyOf(routes);
            vehicles = vehicles == null ? List.of() : List.copyOf(vehicles);
            vehicles.forEach(Company::numbers);
        }

        /** Whether a bus number is one of its buses. */
        boolean runs(String number) {
            if (!number.matches("\\d+")) {
                return false;
            }
            return vehicles.stream().map(Company::numbers).anyMatch(r -> number.length() == r[0].length()
                    && number.compareTo(r[0]) >= 0 && number.compareTo(r[1]) <= 0);
        }

        /** The first and last number of a range; both have the same length, so they compare as text. */
        private static String[] numbers(String range) {
            Matcher m = NUMBERS.matcher(range);
            if (!m.matches()) {
                throw new IllegalArgumentException("Not a vehicle number or range: " + range);
            }
            String last = m.group(2) == null ? m.group(1) : m.group(2);
            if (last.length() != m.group(1).length() || last.compareTo(m.group(1)) < 0) {
                throw new IllegalArgumentException("Not a range of vehicle numbers: " + range);
            }
            return new String[] {m.group(1), last};
        }
    }
}
