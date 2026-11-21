package com.transitanalytics.service;

import com.transitanalytics.dto.NetworkCoverageDto;
import com.transitanalytics.dto.NetworkCoverageDto.Coverage;
import com.transitanalytics.dto.NetworkCoverageDto.District;
import com.transitanalytics.dto.NetworkCoverageDto.Forest;
import com.transitanalytics.dto.NetworkCoverageDto.Shape;
import com.transitanalytics.geo.Geo;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Network coverage: the share of the city within {@link #RADIUS_METERS} of a stop of the city's lines, of all of it
 * and of the part outside its woods; the woods; and the districts' population densities, with and without their woods.
 * The city's border, districts and woods come from OpenStreetMap ({@value #AREAS}), the districts' residents from the
 * city's register ({@value #POPULATION}). Areas are measured on a grid of {@link #CELL_METERS} m cells.
 */
@Service
public class NetworkCoverageService {

    static final String AREAS = "map/bialystok-areas.json";
    static final String POPULATION = "map/bialystok-district-population.csv";
    static final String BELT = "map/bialystok-belt.json";
    static final String BELT_POPULATION = "map/belt-population.csv";
    /** The belt: villages and towns reaching within this distance of the city limits. */
    static final double BELT_KM = 5;
    /** The belt is measured on a coarser grid: it is some 35 km across. */
    static final double BELT_CELL_METERS = 20;
    /** Woods in the belt smaller than this (km²) are counted but not drawn. */
    static final double BELT_WOOD_DRAWN_KM2 = 0.002;
    static final int RADIUS_METERS = 400;
    static final double CELL_METERS = 10;
    /** Districts with at least this share of woods get a density without them. */
    static final double LARGE_FOREST_SHARE = 0.10;
    /**
     * The city's population at the end of 2025 by Statistics Poland (GUS), as the city's report gives it: everyone
     * living here, registered or not; higher than the register the districts' figures come from.
     */
    static final int OFFICIAL_POPULATION = 289_217;
    /** Unnamed woods at least this large are listed on their own; the smaller ones together. */
    static final double LISTED_KM2 = 0.1;

    /** A shape from {@value #AREAS}: points are [latitude, longitude]. */
    record Feature(String osm, String name, List<List<double[]>> outer, List<List<double[]>> inner) {
        Shape shape() {
            return new Shape(outer, inner);
        }

        /** All rings in metres, for {@link AreaGrid#fill}. */
        List<double[][]> rings() {
            List<double[][]> rings = new ArrayList<>();
            for (List<List<double[]>> group : List.of(outer, inner)) {
                for (List<double[]> ring : group) {
                    rings.add(ring.stream().map(p -> Geo.xy(p[0], p[1])).toArray(double[][]::new));
                }
            }
            return rings;
        }
    }

    record Areas(String source, String fetched, Feature city, List<Feature> districts, List<Feature> forests) {
    }

    /** A village or town of the belt from {@value #BELT}. */
    record BeltFeature(String osm, String name, String gmina, String kind, List<List<double[]>> outer,
                       List<List<double[]>> inner) {
        Feature feature() {
            return new Feature(osm, name, outer, inner);
        }
    }

    record BeltAreas(String source, List<BeltFeature> villages, List<Feature> woods) {
    }

    private record BeltPopulation(Integer population, String date, String source) {
    }

    private final JdbcTemplate jdbc;
    private final Areas areas;
    private final BeltAreas beltAreas;
    private final Map<String, BeltPopulation> beltPopulation;
    private final Map<String, Integer> population;
    private final String populationSource;
    private volatile Cached cached;

    private record Cached(String version, NetworkCoverageDto coverage) {
    }

    public NetworkCoverageService(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        try (InputStream in = new ClassPathResource(AREAS).getInputStream()) {
            this.areas = json.readValue(in, Areas.class);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        this.population = new HashMap<>();
        StringBuilder source = new StringBuilder();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new ClassPathResource(POPULATION).getInputStream(), StandardCharsets.UTF_8))) {
            in.lines().forEach(line -> {
                if (line.startsWith("#")) {
                    source.append(line.substring(1).trim()).append(' ');
                } else if (!line.startsWith("district,") && !line.isBlank()) {
                    String[] f = line.split(",");
                    population.put(f[0], Integer.parseInt(f[3]) + Integer.parseInt(f[4]));
                }
            });
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        this.populationSource = source.toString().trim();
        try (InputStream in = new ClassPathResource(BELT).getInputStream()) {
            this.beltAreas = json.readValue(in, BeltAreas.class);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        this.beltPopulation = new HashMap<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new ClassPathResource(BELT_POPULATION).getInputStream(), StandardCharsets.UTF_8))) {
            in.lines().filter(line -> !line.startsWith("#") && !line.startsWith("osm,") && !line.isBlank())
                    .forEach(line -> {
                        List<String> f = csv(line);
                        beltPopulation.put(f.get(0), new BeltPopulation(
                                f.get(3).isEmpty() ? null : Integer.valueOf(f.get(3)), blankToNull(f.get(4)),
                                blankToNull(f.get(5))));
                    });
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Worked out again when the timetable changes (another import can move stops). */
    public NetworkCoverageDto coverage() {
        Map<String, Object> v = jdbc.queryForMap("SELECT count(*) AS stops, max(id) AS last FROM stop");
        String version = v.get("stops") + ":" + v.get("last");
        Cached current = cached;
        if (current == null || !current.version().equals(version)) {
            current = new Cached(version, compute(stops()));
            cached = current;
        }
        return current.coverage();
    }

    /** One point per place where the city's lines stop (the main timetable's agencies, not other operators'). */
    List<double[]> stops() {
        return jdbc.query("""
                SELECT DISTINCT round(s.latitude::numeric, 5)::float8 AS lat, round(s.longitude::numeric, 5)::float8 AS lon
                FROM stop s
                WHERE EXISTS (SELECT 1 FROM stop_time st JOIN trip t ON t.id = st.trip_id
                                       JOIN route r ON r.id = t.route_id JOIN agency a ON a.id = r.agency_id
                              WHERE st.stop_id = s.id AND strpos(a.gtfs_id, ':') = 0)
                ORDER BY 1, 2""", (rs, i) -> new double[] {rs.getDouble("lat"), rs.getDouble("lon")});
    }

    NetworkCoverageDto compute(List<double[]> stops) {
        List<double[][]> cityRings = areas.city().rings();
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (double[][] ring : cityRings) {
            for (double[] p : ring) {
                minX = Math.min(minX, p[0]);
                minY = Math.min(minY, p[1]);
                maxX = Math.max(maxX, p[0]);
                maxY = Math.max(maxY, p[1]);
            }
        }
        AreaGrid grid = new AreaGrid(minX, minY, maxX, maxY, CELL_METERS);
        BitSet city = new BitSet(grid.size());
        grid.fill(cityRings, city::set);

        BitSet covered = new BitSet(grid.size());
        for (double[] stop : stops) {
            double[] p = Geo.xy(stop[0], stop[1]);
            grid.disc(p[0], p[1], RADIUS_METERS, covered::set);
        }
        covered.and(city);

        // Districts: each cell of the city in one (the first whose border takes it).
        List<Feature> districtShapes = areas.districts();
        byte[] districtOf = new byte[grid.size()];
        for (int d = 0; d < districtShapes.size(); d++) {
            byte id = (byte) (d + 1);
            grid.fill(districtShapes.get(d).rings(), cell -> {
                if (districtOf[cell] == 0 && city.get(cell)) {
                    districtOf[cell] = id;
                }
            });
        }

        // Woods: a cell counts once, for the first mapped wood that takes it.
        BitSet forest = new BitSet(grid.size());
        List<WoodPiece> pieces = new ArrayList<>();
        for (Feature f : areas.forests()) {
            int[] count = new int[1];
            int[] byDistrict = new int[districtShapes.size() + 1];
            grid.fill(f.rings(), cell -> {
                if (city.get(cell) && !forest.get(cell)) {
                    forest.set(cell);
                    count[0]++;
                    byDistrict[districtOf[cell]]++;
                }
            });
            if (count[0] > 0) {
                pieces.add(new WoodPiece(f, count[0], byDistrict));
            }
        }

        long cityCells = city.cardinality();
        long coveredCells = covered.cardinality();
        long forestCells = forest.cardinality();
        BitSet coveredForest = (BitSet) covered.clone();
        coveredForest.and(forest);
        long coveredLand = coveredCells - coveredForest.cardinality();

        // The districts' cells, woods and covered cells.
        long[] cells = new long[districtShapes.size() + 1];
        long[] woods = new long[districtShapes.size() + 1];
        long[] near = new long[districtShapes.size() + 1];
        for (int cell = city.nextSetBit(0); cell >= 0; cell = city.nextSetBit(cell + 1)) {
            int d = districtOf[cell];
            cells[d]++;
            if (forest.get(cell)) {
                woods[d]++;
            }
            if (covered.get(cell)) {
                near[d]++;
            }
        }
        List<District> districts = new ArrayList<>();
        for (int d = 0; d < districtShapes.size(); d++) {
            Feature f = districtShapes.get(d);
            double area = grid.km2(cells[d + 1]);
            double wood = grid.km2(woods[d + 1]);
            double land = area - wood;
            int people = population.getOrDefault(f.name(), 0);
            districts.add(new District(f.name(), f.shape(), round(area), people, ratio(people, area), round(wood),
                    round(land), ratio(people, land), area > 0 && wood / area >= LARGE_FOREST_SHARE,
                    cells[d + 1] == 0 ? 0 : (double) near[d + 1] / cells[d + 1]));
        }

        return new NetworkCoverageDto(RADIUS_METERS, stops, CELL_METERS,
                new NetworkCoverageDto.Place(areas.city().name(), areas.city().shape(), round(grid.km2(cityCells))),
                coverage(grid, cityCells, coveredCells), coverage(grid, cityCells - forestCells, coveredLand),
                forests(grid, pieces, districtShapes),
                round(grid.km2(forestCells)), districts, LARGE_FOREST_SHARE, OFFICIAL_POPULATION,
                new NetworkCoverageDto.Sources(areas.source(), areas.fetched(), populationSource),
                belt(stops, cityRings));
    }

    /**
     * The villages and towns around the city: each one's area outside the city limits, its woods, its residents and
     * densities (none without a figure), and how much of it is near a stop.
     */
    private NetworkCoverageDto.Belt belt(List<double[]> stops, List<double[][]> cityRings) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        List<List<double[][]>> placeRings = new ArrayList<>();
        for (BeltFeature v : beltAreas.villages()) {
            List<double[][]> rings = v.feature().rings();
            placeRings.add(rings);
            for (double[][] ring : rings) {
                for (double[] p : ring) {
                    minX = Math.min(minX, p[0]);
                    minY = Math.min(minY, p[1]);
                    maxX = Math.max(maxX, p[0]);
                    maxY = Math.max(maxY, p[1]);
                }
            }
        }
        if (placeRings.isEmpty()) {
            return new NetworkCoverageDto.Belt(BELT_KM, BELT_CELL_METERS, List.of(), List.of());
        }
        AreaGrid grid = new AreaGrid(minX, minY, maxX, maxY, BELT_CELL_METERS);
        BitSet city = new BitSet(grid.size());
        grid.fill(cityRings, city::set);
        // Each cell outside the city in one place (the first whose border takes it).
        short[] placeOf = new short[grid.size()];
        for (int i = 0; i < placeRings.size(); i++) {
            short id = (short) (i + 1);
            grid.fill(placeRings.get(i), cell -> {
                if (placeOf[cell] == 0 && !city.get(cell)) {
                    placeOf[cell] = id;
                }
            });
        }
        BitSet woods = new BitSet(grid.size());
        List<Shape> drawn = new ArrayList<>();
        for (Feature f : beltAreas.woods()) {
            int[] count = new int[1];
            grid.fill(f.rings(), cell -> {
                if (placeOf[cell] != 0) {
                    woods.set(cell);
                    count[0]++;
                }
            });
            if (grid.km2(count[0]) >= BELT_WOOD_DRAWN_KM2) {
                drawn.add(f.shape());
            }
        }
        BitSet covered = new BitSet(grid.size());
        for (double[] stop : stops) {
            double[] p = Geo.xy(stop[0], stop[1]);
            grid.disc(p[0], p[1], RADIUS_METERS, covered::set);
        }
        long[] cells = new long[placeRings.size() + 1];
        long[] wood = new long[placeRings.size() + 1];
        long[] near = new long[placeRings.size() + 1];
        for (int cell = 0; cell < placeOf.length; cell++) {
            int v = placeOf[cell];
            if (v == 0) {
                continue;
            }
            cells[v]++;
            if (woods.get(cell)) {
                wood[v]++;
            }
            if (covered.get(cell)) {
                near[v]++;
            }
        }
        List<NetworkCoverageDto.BeltPlace> places = new ArrayList<>();
        for (int i = 0; i < placeRings.size(); i++) {
            BeltFeature v = beltAreas.villages().get(i);
            BeltPopulation people = beltPopulation.getOrDefault(v.osm(), new BeltPopulation(null, null, null));
            double area = grid.km2(cells[i + 1]);
            double forest = grid.km2(wood[i + 1]);
            double land = area - forest;
            Integer n = people.population();
            places.add(new NetworkCoverageDto.BeltPlace(v.name(), v.gmina(), v.kind(), v.feature().shape(),
                    round(area), n, people.date(), people.source(), n == null ? null : ratio(n, area),
                    round(forest), round(land), n == null ? null : ratio(n, land),
                    area > 0 && forest / area >= LARGE_FOREST_SHARE,
                    cells[i + 1] == 0 ? 0 : (double) near[i + 1] / cells[i + 1]));
        }
        return new NetworkCoverageDto.Belt(BELT_KM, BELT_CELL_METERS, places, drawn);
    }

    /** The fields of a CSV line, double quotes taken off. */
    static List<String> csv(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (char c : line.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
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

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private record WoodPiece(Feature feature, int cells, int[] byDistrict) {
    }

    /**
     * The woods to list: the named ones (pieces with one name together), the unnamed ones of at least
     * {@link #LISTED_KM2} on their own, named after their district, and the smaller ones together.
     */
    private static List<Forest> forests(AreaGrid grid, List<WoodPiece> pieces, List<Feature> districtShapes) {
        Map<String, List<WoodPiece>> groups = new LinkedHashMap<>();
        List<WoodPiece> small = new ArrayList<>();
        for (WoodPiece p : pieces) {
            String name = p.feature().name();
            if (name != null && !name.isBlank()) {
                groups.computeIfAbsent(name, k -> new ArrayList<>()).add(p);
            } else if (grid.km2(p.cells()) >= LISTED_KM2) {
                groups.put("\0" + p.feature().osm(), List.of(p));
            } else {
                small.add(p);
            }
        }
        List<Forest> forests = new ArrayList<>();
        Map<String, Integer> unnamedIn = new HashMap<>();
        List<Map.Entry<String, List<WoodPiece>>> ordered = new ArrayList<>(groups.entrySet());
        ordered.sort(Comparator.comparingLong(
                (Map.Entry<String, List<WoodPiece>> e) -> -e.getValue().stream().mapToLong(WoodPiece::cells).sum()));
        for (Map.Entry<String, List<WoodPiece>> e : ordered) {
            List<WoodPiece> group = e.getValue();
            String district = mainDistrict(group, districtShapes);
            boolean named = !e.getKey().startsWith("\0");
            String name = e.getKey();
            if (!named) {
                int n = unnamedIn.merge(district, 1, Integer::sum);
                name = "Woodland in " + district + (n > 1 ? " (" + n + ")" : "");
            }
            forests.add(new Forest(name, named, district,
                    round(grid.km2(group.stream().mapToLong(WoodPiece::cells).sum())), group.size(),
                    group.stream().map(p -> p.feature().shape()).toList()));
        }
        if (!small.isEmpty()) {
            forests.add(new Forest("Smaller woods", false, null,
                    round(grid.km2(small.stream().mapToLong(WoodPiece::cells).sum())), small.size(),
                    small.stream().map(p -> p.feature().shape()).toList()));
        }
        return forests;
    }

    private static String mainDistrict(List<WoodPiece> group, List<Feature> districtShapes) {
        int[] total = new int[districtShapes.size() + 1];
        group.forEach(p -> {
            for (int d = 0; d < total.length; d++) {
                total[d] += p.byDistrict()[d];
            }
        });
        int best = 1;
        for (int d = 1; d < total.length; d++) {
            if (total[d] > total[best]) {
                best = d;
            }
        }
        return districtShapes.isEmpty() ? null : districtShapes.get(best - 1).name();
    }

    private static Coverage coverage(AreaGrid grid, long cells, long covered) {
        return new Coverage(round(grid.km2(cells)), round(grid.km2(covered)), cells == 0 ? 0 : (double) covered / cells);
    }

    private static double ratio(int people, double km2) {
        return km2 <= 0 ? 0 : Math.round(people / km2);
    }

    /** To 0.001 km² (1000 m²): finer than the grid measures. */
    private static double round(double km2) {
        return Math.round(km2 * 1000) / 1000.0;
    }
}
