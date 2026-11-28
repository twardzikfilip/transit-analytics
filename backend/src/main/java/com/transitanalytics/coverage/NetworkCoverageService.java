package com.transitanalytics.coverage;

import com.transitanalytics.coverage.PlaceReferenceData.Feature;
import com.transitanalytics.coverage.PlaceReferenceData.Residents;
import com.transitanalytics.domain.TimetableFeed;
import com.transitanalytics.dto.NetworkCoverageDto.Coverage;
import com.transitanalytics.dto.NetworkCoverageDto.Forest;
import com.transitanalytics.dto.NetworkCoverageDto.PlaceDensity;
import com.transitanalytics.dto.NetworkCoverageDto.RadiusCoverage;
import com.transitanalytics.dto.NetworkCoverageDto.Shape;
import com.transitanalytics.dto.NetworkCoverageDto;
import com.transitanalytics.geo.Geo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * Network coverage: the share of the city within each walking distance of the nearest stop of the city's routes, of
 * all of it and of the part where people can live (outside its woods and the valley of the river Biała); the woods and
 * the valley; and the population density of the city's districts and of the villages and towns around it, also
 * without those (around the city, also without fields and other open land). The places come from {@code places/} (see
 * {@link PlaceReferenceData}), the settings from {@code map/network-coverage.yml}. Areas are measured on a grid.
 */
@Service
public class NetworkCoverageService {

    private final JdbcTemplate jdbc;
    private final NetworkCoverageProperties settings;
    private final PlaceReferenceData places;
    private volatile Cached cached;

    private record Cached(String version, NetworkCoverageDto coverage) {
    }

    @Autowired
    public NetworkCoverageService(JdbcTemplate jdbc, NetworkCoverageProperties settings) {
        this(jdbc, settings, PlaceReferenceData.load());
    }

    NetworkCoverageService(JdbcTemplate jdbc, NetworkCoverageProperties settings, PlaceReferenceData places) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.places = places;
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

    /**
     * One point per place where the city's routes stop (the main timetable's, not other operators'; their stops in
     * the city are the city's own).
     */
    List<double[]> stops() {
        return jdbc.query("""
                SELECT DISTINCT round(s.latitude::numeric, 5)::float8 AS lat, round(s.longitude::numeric, 5)::float8 AS lon
                FROM stop s
                WHERE EXISTS (SELECT 1 FROM stop_time st JOIN trip t ON t.id = st.trip_id
                              WHERE st.stop_id = s.id AND t.feed = ?)
                ORDER BY 1, 2""", (rs, i) -> new double[] {rs.getDouble("lat"), rs.getDouble("lon")},
                TimetableFeed.MAIN);
    }

    NetworkCoverageDto compute(List<double[]> stops) {
        List<double[]> stopsXy = stops.stream().map(s -> Geo.xy(s[0], s[1])).toList();
        PlaceReferenceData.Areas areas = places.city();
        List<double[][]> cityRings = areas.city().rings();

        AreaGrid grid = AreaGrid.around(cityRings, settings.cellMeters());
        BitSet city = new BitSet(grid.size());
        grid.fill(cityRings, city::set);
        PlaceGrid districts = new PlaceGrid(grid, city, areas.districts().stream().map(Feature::rings).toList());

        // Woods: a cell counts once, for the first mapped wood that takes it.
        BitSet forest = new BitSet(grid.size());
        List<WoodPiece> pieces = new ArrayList<>();
        for (Feature f : areas.forests()) {
            int[] count = new int[1];
            int[] byDistrict = new int[districts.places + 1];
            grid.fill(f.rings(), cell -> {
                if (city.get(cell) && !forest.get(cell)) {
                    forest.set(cell);
                    count[0]++;
                    byDistrict[districts.placeOf(cell)]++;
                }
            });
            if (count[0] > 0) {
                pieces.add(new WoodPiece(f, count[0], byDistrict));
            }
        }

        // The Biała valley: in the city, outside the woods; a cell counts once.
        BitSet valley = new BitSet(grid.size());
        List<NetworkCoverageDto.ValleyStretch> stretches = new ArrayList<>();
        for (Feature f : areas.valley()) {
            int[] count = new int[1];
            grid.fill(f.rings(), cell -> {
                if (city.get(cell) && !forest.get(cell) && !valley.get(cell)) {
                    valley.set(cell);
                    count[0]++;
                }
            });
            stretches.add(new NetworkCoverageDto.ValleyStretch(f.name(), round(grid.km2(count[0])), f.shape()));
        }
        BitSet uninhabited = (BitSet) forest.clone();
        uninhabited.or(valley);

        List<BitSet> near = near(grid, stopsXy);
        List<RadiusCoverage> coverages = new ArrayList<>();
        long cityCells = city.cardinality();
        long forestCells = forest.cardinality();
        long emptyCells = uninhabited.cardinality();
        for (int r = 0; r < near.size(); r++) {
            BitSet covered = (BitSet) near.get(r).clone();
            covered.and(city);
            BitSet coveredEmpty = (BitSet) covered.clone();
            coveredEmpty.and(uninhabited);
            coverages.add(new RadiusCoverage(settings.radii().get(r), coverage(grid, cityCells, covered.cardinality()),
                    coverage(grid, cityCells - emptyCells, covered.cardinality() - coveredEmpty.cardinality())));
        }

        List<Feature> districtShapes = areas.districts();
        List<PlaceDensity> districtRows = measure(districts, forest, valley, new BitSet(), near, i -> {
            Feature f = districtShapes.get(i);
            return new Place(f, "district", null,
                    places.districtPopulation().getOrDefault(f.name(), Residents.UNKNOWN));
        });

        return new NetworkCoverageDto(stops, settings.cellMeters(),
                new NetworkCoverageDto.Place(areas.city().name(), areas.city().shape(), round(grid.km2(cityCells))),
                coverages, forests(grid, pieces, districtShapes), round(grid.km2(forestCells)), stretches,
                round(grid.km2(valley.cardinality())), districtRows,
                settings.largeForestShare(), places.official(),
                new NetworkCoverageDto.Sources(areas.source(), areas.fetched(), places.districtPopulationSource()),
                belt(stopsXy, cityRings));
    }

    /**
     * The villages and towns around the city, measured like the districts, outside the city limits, on a coarser
     * grid (the belt is some 50 km across).
     */
    private NetworkCoverageDto.Belt belt(List<double[]> stopsXy, List<double[][]> cityRings) {
        List<PlaceReferenceData.BeltFeature> villages = places.belt().villages();
        List<List<double[][]>> placeRings = villages.stream().map(v -> v.feature().rings()).toList();
        AreaGrid grid = AreaGrid.around(placeRings.stream().flatMap(List::stream).toList(), settings.beltCellMeters());
        BitSet outsideCity = new BitSet(grid.size());
        grid.fill(cityRings, outsideCity::set);
        outsideCity.flip(0, grid.size());
        PlaceGrid belt = new PlaceGrid(grid, outsideCity, placeRings);

        BitSet woods = new BitSet(grid.size());
        List<Shape> drawnWoods = fill(belt, places.belt().woods(), woods, new BitSet());
        BitSet open = new BitSet(grid.size());
        List<Shape> drawnOpen = fill(belt, places.belt().open(), open, woods);
        List<PlaceDensity> rows = measure(belt, woods, new BitSet(), open, near(grid, stopsXy), i -> {
            PlaceReferenceData.BeltFeature v = villages.get(i);
            return new Place(v.feature(), v.kind(), v.gmina(),
                    places.beltPopulation().getOrDefault(v.osm(), Residents.UNKNOWN));
        });
        return new NetworkCoverageDto.Belt(settings.beltKm(), settings.beltFarKm(), settings.beltCellMeters(), rows,
                drawnWoods, drawnOpen);
    }

    /** Adds the features' cells in the belt that aren't {@code taken}; returns the shapes large enough to draw. */
    private List<Shape> fill(PlaceGrid belt, List<Feature> features, BitSet cells, BitSet taken) {
        List<Shape> drawn = new ArrayList<>();
        for (Feature f : features) {
            int[] count = new int[1];
            belt.grid.fill(f.rings(), cell -> {
                if (belt.placeOf(cell) != 0 && !taken.get(cell)) {
                    cells.set(cell);
                    count[0]++;
                }
            });
            if (belt.grid.km2(count[0]) >= settings.drawnWoodsKm2()) {
                drawn.add(f.shape());
            }
        }
        return drawn;
    }

    /** A place to measure: its shape, what it is and its residents. */
    private record Place(Feature feature, String kind, String gmina, Residents residents) {
    }

    /** The cells within each walking distance of a stop, as {@link NetworkCoverageProperties#radii()}. */
    private List<BitSet> near(AreaGrid grid, List<double[]> stopsXy) {
        return settings.radii().stream().map(radius -> grid.near(stopsXy, radius)).toList();
    }

    /** Each place's area, woods, valley, open land, densities and share near a stop (at each distance). */
    private List<PlaceDensity> measure(PlaceGrid grid, BitSet woods, BitSet valley, BitSet open, List<BitSet> near,
                                       IntFunction<Place> place) {
        long[] cells = grid.count(null);
        long[] wood = grid.count(woods);
        long[] river = grid.count(valley);
        long[] fields = grid.count(open);
        List<long[]> covered = near.stream().map(grid::count).toList();
        List<PlaceDensity> rows = new ArrayList<>();
        for (int i = 0; i < grid.places; i++) {
            Place p = place.apply(i);
            long n = cells[i + 1];
            double area = grid.grid.km2(n);
            double forest = grid.grid.km2(wood[i + 1]);
            double valleyKm2 = grid.grid.km2(river[i + 1]);
            double openKm2 = grid.grid.km2(fields[i + 1]);
            double land = area - forest - valleyKm2 - openKm2;
            Integer people = p.residents().population();
            int id = i + 1;
            List<Double> shares = covered.stream().map(c -> n == 0 ? 0 : (double) c[id] / n).toList();
            rows.add(new PlaceDensity(p.feature().name(), p.kind(), p.gmina(), p.feature().shape(), round(area),
                    people, p.residents().date(), p.residents().source(),
                    people == null ? null : ratio(people, area), round(forest), round(valleyKm2), round(openKm2), round(land),
                    people == null ? null : ratio(people, land), area > 0 && forest / area >= settings.largeForestShare(),
                    shares));
        }
        return rows;
    }

    private record WoodPiece(Feature feature, int cells, int[] byDistrict) {
    }

    /**
     * The woods to list: the named ones (pieces with one name together), the unnamed ones of at least
     * {@link NetworkCoverageProperties#listedWoodsKm2()} on their own, named after their district, and the smaller
     * ones together.
     */
    private List<Forest> forests(AreaGrid grid, List<WoodPiece> pieces, List<Feature> districtShapes) {
        Map<String, List<WoodPiece>> groups = new LinkedHashMap<>();
        List<WoodPiece> small = new ArrayList<>();
        for (WoodPiece p : pieces) {
            String name = p.feature().name();
            if (name != null && !name.isBlank()) {
                groups.computeIfAbsent(name, k -> new ArrayList<>()).add(p);
            } else if (grid.km2(p.cells()) >= settings.listedWoodsKm2()) {
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
        if (districtShapes.isEmpty()) {
            return null;
        }
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
        return districtShapes.get(best - 1).name();
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
