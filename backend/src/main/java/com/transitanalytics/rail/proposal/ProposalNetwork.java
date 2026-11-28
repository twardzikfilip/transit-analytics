package com.transitanalytics.rail.proposal;

import com.transitanalytics.dto.CityRailProposalDto;
import com.transitanalytics.geo.PlaceData;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The proposal's railways and stations ({@code places/osm/city-rail-proposal.json}, built by
 * {@code tools/geo/build_city_rail_proposal.py}), and its two solutions: which routes run on them.
 */
final class ProposalNetwork {

    static final String FILE = "osm/city-rail-proposal.json";
    /** A station the city trains run through without stopping. */
    static final String PASSED = "passed";

    /** The network as built. */
    record NetworkFile(List<Corridor> corridors) {
    }

    /**
     * A railway from Białystok outwards.
     *
     * @param beyond the station past the end (null when the end is one of today's): its run time from the station
     *               before shares out to the end
     */
    record Corridor(String id, String ref, List<Station> stations, List<double[]> points, Beyond beyond) {

        /** For run times: its stations, and the one past its end when trains call there. */
        List<Station> timed() {
            if (beyond == null || beyond.id() == null) {
                return stations;
            }
            List<Station> timed = new ArrayList<>(stations);
            timed.add(new Station(beyond.id(), beyond.name(), "beyond", 0, 0, beyond.km()));
            return timed;
        }

        int index(String name) {
            for (int i = 0; i < stations.size(); i++) {
                if (stations.get(i).name().equals(name)) {
                    return i;
                }
            }
            throw new IllegalArgumentException(name + " isn't on the railway to " + id);
        }
    }

    /** @param id its id in the timetable, null for one no train calls at today */
    record Beyond(String id, String name, double km) {
    }

    /** @param id the station's id in the timetable, null for one no train calls at today */
    record Station(String id, String name, String kind, double lat, double lon, double km) {

        double[] point() {
            return new double[] {lat, lon};
        }
    }

    /** Part of a route: a railway, outwards from Białystok or inwards to it. */
    record Section(String corridor, boolean outward) {
    }

    record RouteDef(String id, List<Section> sections) {
    }

    /** A route's station, one way, with the railway to it from the one before ("" for the first). */
    record Stop(Station station, String railway) {
    }

    /** The two solutions, in order: five routes from Białystok; or two routes through it, and one ending there. */
    static final Map<String, List<RouteDef>> SOLUTIONS = new LinkedHashMap<>();

    static {
        SOLUTIONS.put("radial", List.of(
                new RouteDef("S1", List.of(new Section("niewodnica", true))),
                new RouteDef("S2", List.of(new Section("dobrzyniewo", true))),
                new RouteDef("S3", List.of(new Section("juchnowiec", true))),
                new RouteDef("S4", List.of(new Section("wasilkow", true))),
                new RouteDef("S5", List.of(new Section("henrykowo", true)))));
        SOLUTIONS.put("through", List.of(
                new RouteDef("S1", List.of(new Section("wasilkow", false), new Section("juchnowiec", true))),
                new RouteDef("S2", List.of(new Section("dobrzyniewo", false), new Section("henrykowo", true))),
                new RouteDef("S3", List.of(new Section("niewodnica", true)))));
    }

    private final List<Corridor> corridors;

    ProposalNetwork() {
        this.corridors = List.copyOf(PlaceData.json(FILE, NetworkFile.class).corridors());
    }

    List<Corridor> corridors() {
        return corridors;
    }

    Corridor corridor(String id) {
        return corridors.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    /** Where every railway starts. */
    String hub() {
        return corridors.get(0).stations().get(0).name();
    }

    /** A route's stations in order, one way. */
    List<Stop> stops(RouteDef route, boolean out) {
        List<Stop> stops = new ArrayList<>();
        List<Section> sections = new ArrayList<>(route.sections());
        if (!out) {
            Collections.reverse(sections);
        }
        for (Section section : sections) {
            List<Station> stations = new ArrayList<>(corridor(section.corridor()).stations());
            if (section.outward() != out) {
                Collections.reverse(stations);
            }
            for (Station s : stations) {
                if (stops.isEmpty() || !stops.get(stops.size() - 1).station().name().equals(s.name())) {
                    stops.add(new Stop(s, stops.isEmpty() ? "" : section.corridor()));
                }
            }
        }
        return stops;
    }

    /** A route's track, outwards: its railways' points in order. */
    List<double[]> points(RouteDef route) {
        List<double[]> points = new ArrayList<>();
        for (Section section : route.sections()) {
            List<double[]> p = new ArrayList<>(corridor(section.corridor()).points());
            if (!section.outward()) {
                Collections.reverse(p);
            }
            points.addAll(p);
        }
        return points;
    }

    /** Per pair of neighbouring stations ("a|b", both ways), the railways they neighbour on. */
    Map<String, Set<String>> neighbours() {
        Map<String, Set<String>> neighbours = new HashMap<>();
        for (Corridor c : corridors) {
            for (int i = 0; i < c.stations().size() - 1; i++) {
                String a = c.stations().get(i).name();
                String b = c.stations().get(i + 1).name();
                neighbours.computeIfAbsent(a + "|" + b, k -> new HashSet<>()).add(c.id());
                neighbours.computeIfAbsent(b + "|" + a, k -> new HashSet<>()).add(c.id());
            }
        }
        return neighbours;
    }

    /** Every station's place, by name. */
    Map<String, double[]> stationPoints() {
        Map<String, double[]> points = new LinkedHashMap<>();
        corridors.forEach(c -> c.stations().forEach(s -> points.putIfAbsent(s.name(), s.point())));
        return points;
    }

    /** Per timetable id, the station's name in the network. */
    Map<String, String> stationNames() {
        Map<String, String> names = new HashMap<>();
        corridors.forEach(c -> c.stations().stream().filter(s -> s.id() != null)
                .forEach(s -> names.put(s.id(), s.name())));
        return names;
    }

    /** The railways and their stations, for the page; {@code served}: those trains run on today. */
    List<CityRailProposalDto.Corridor> toDto(Set<String> served) {
        return corridors.stream().map(c -> new CityRailProposalDto.Corridor(c.id(), c.ref(), served.contains(c.id()),
                c.stations().stream().map(s -> new CityRailProposalDto.Station(s.name(), s.kind(), s.lat(), s.lon(),
                        s.km())).toList(), c.points())).toList();
    }

    /** The fingerprint of the network file and of how the timetables are worked out ({@code model}). */
    static String hash(String model) {
        try (InputStream in = PlaceData.open(FILE)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(model.getBytes(StandardCharsets.UTF_8));
            digest.update(in.readAllBytes());
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
