package com.transitanalytics.rail.proposal;

import com.transitanalytics.common.WarmUp;
import com.transitanalytics.data.RailTrains;
import com.transitanalytics.domain.TimetableFeed;
import com.transitanalytics.dto.CityRailDto;
import com.transitanalytics.dto.CityRailProposalDto;
import com.transitanalytics.gtfs.GtfsImported;
import com.transitanalytics.rail.CityRailService;
import com.transitanalytics.rail.proposal.ProposalNetwork.RouteDef;
import com.transitanalytics.rail.proposal.ProposalScheduler.Pass;
import com.transitanalytics.rail.proposal.ProposalStore.Meta;
import com.transitanalytics.rail.proposal.ProposalStore.Stored;
import com.transitanalytics.data.RailTrains.Train;
import com.transitanalytics.rail.proposal.TrainPaths.Stretch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * City Rail proposal no. 1: city trains on the five railways from Białystok ({@link ProposalNetwork}), calling at every
 * station but Fasty, new ones included. Two ways of running them: five routes from Białystok, or routes running through
 * Białystok (and one ending there). Each is timetabled ({@link ProposalScheduler}) at four frequencies around the
 * trains of the weekday the City Rail page counts trains on ({@link RailTrains}, {@link TrainPaths}), with run times
 * from Polregio's ({@link RunTimeModel}), and shown beside those trains ({@link ProposalTimetables}).
 * <p>
 * An analysis of the timetable as it is, not a live view: worked out once and stored ({@link ProposalStore}); worked
 * out again only when the network file or {@link #MODEL} changes.
 */
@Service
public class CityRailProposal {

    private static final Logger log = LoggerFactory.getLogger(CityRailProposal.class);

    /** Raise when the way the timetables are worked out changes, so stored ones are worked out again. */
    static final String MODEL = "4";
    private static final String POLREGIO = "PR";

    /** A frequency: at least one train a period (seconds), each way. */
    record Frequency(String id, int period) {
    }

    static final List<Frequency> FREQUENCIES = List.of(new Frequency("1tp2h", 7200), new Frequency("1tph", 3600),
            new Frequency("2tph", 1800), new Frequency("3tph", 1200));

    /** The proposal, and each solution's variants by frequency. */
    private record Worked(CityRailProposalDto.Proposal proposal,
                          Map<String, Map<String, CityRailProposalDto.Variant>> variants) {
    }

    private final ProposalStore store;
    private final RailTrains trains;
    private final Clock clock;
    private final CityRailService rail;
    private final boolean warmUp;
    private final ProposalNetwork network = new ProposalNetwork();
    private final String networkHash = ProposalNetwork.hash(MODEL);
    private volatile Worked cached;

    public CityRailProposal(ProposalStore store, RailTrains trains, Clock clock, CityRailService rail,
                            @Value("${app.warm-up.enabled:true}") boolean warmUp) {
        this.store = store;
        this.trains = trains;
        this.clock = clock;
        this.rail = rail;
        this.warmUp = warmUp;
    }

    /** Reads the proposal, or works it out, at startup, in the background. */
    @EventListener(value = ApplicationReadyEvent.class, condition = WarmUp.ENABLED)
    public void warmUp() {
        WarmUp.inBackground("city-rail-proposal", CityRailProposal.class, this::proposal);
    }

    /** The trains' timetable imported: counts only while there is no proposal yet (it is worked out once). */
    @EventListener
    public void afterImport(GtfsImported imported) {
        Worked current = cached;
        if (TimetableFeed.RAIL.equals(imported.feed()) && (current == null || current.variants().isEmpty())) {
            // The weekday comes from the report: forget the old one first, whichever listener runs first.
            rail.afterImport(imported);
            cached = null;
            if (warmUp) {
                warmUp();
            }
        }
    }

    /** The proposal as stored; worked out (and stored) when there is none for this network yet. */
    public CityRailProposalDto.Proposal proposal() {
        return worked().proposal();
    }

    /** One solution's routes at a frequency; empty when there is no such solution or frequency. */
    public Optional<CityRailProposalDto.Variant> variant(String solution, String frequency) {
        return Optional.ofNullable(worked().variants().getOrDefault(solution, Map.of()).get(frequency));
    }

    private synchronized Worked worked() {
        Worked current = cached;
        if (current == null) {
            Stored stored = store.load(networkHash, List.copyOf(ProposalNetwork.SOLUTIONS.keySet()),
                    FREQUENCIES.stream().map(Frequency::id).toList()).orElseGet(() -> {
                        Stored proposed = propose();
                        if (!proposed.variants().isEmpty()) {
                            store.save(networkHash, proposed, clock.instant());
                        }
                        return proposed;
                    });
            current = worked(stored);
            cached = current;
        }
        return current;
    }

    private Worked worked(Stored stored) {
        Map<String, Map<String, CityRailProposalDto.Variant>> variants = new LinkedHashMap<>();
        List<CityRailProposalDto.Solution> solutions = new ArrayList<>();
        stored.variants().forEach((id, list) -> {
            Map<String, CityRailProposalDto.Variant> byFrequency = new LinkedHashMap<>();
            list.forEach(v -> byFrequency.put(v.frequency(), v));
            variants.put(id, byFrequency);
            solutions.add(new CityRailProposalDto.Solution(id, list.stream()
                    .map(v -> new CityRailProposalDto.Choice(v.frequency(), v.vehicles())).toList()));
        });
        return new Worked(new CityRailProposalDto.Proposal(stored.day(), stored.meta().rules(),
                network.toDto(Set.copyOf(stored.meta().served())), solutions), variants);
    }

    /** Works the proposal out around the trains of the weekday counted on; none without the trains' timetable. */
    private Stored propose() {
        LocalDate day = rail.report().days().stream().filter(d -> "weekday".equals(d.kind()))
                .map(CityRailDto.Day::date).findFirst().orElse(null);
        if (day == null) {
            return new Stored(null, new Meta(rules(60, 0), List.of()), Map.of());
        }
        long started = System.nanoTime();
        List<Train> all = trains.of(day);
        TrainPaths paths = new TrainPaths(network);
        List<Pass> running = new ArrayList<>();
        Map<Train, List<Stretch>> today = new LinkedHashMap<>();
        Set<String> served = new TreeSet<>();
        for (Train train : all) {
            List<Stretch> stretches = paths.of(train);
            if (!stretches.isEmpty()) {
                today.put(train, stretches);
                stretches.forEach(st -> {
                    running.add(st.pass(train.id()));
                    served.add(st.segment().railway());
                });
            }
        }
        RunTimeModel runTimes = new RunTimeModel(network, all.stream().filter(t -> POLREGIO.equals(t.agency())).toList());
        ProposalTimetables timetables = new ProposalTimetables(network, today);
        Map<String, List<CityRailProposalDto.Variant>> solutions = new LinkedHashMap<>();
        ProposalNetwork.SOLUTIONS.forEach((id, routes) -> {
            List<CityRailProposalDto.Variant> variants = new ArrayList<>();
            for (Frequency f : FREQUENCIES) {
                ProposalScheduler scheduler = new ProposalScheduler(running, f.period());
                List<CityRailProposalDto.Route> dtos = new ArrayList<>();
                for (RouteDef route : routes) {
                    var timetable = scheduler.schedule(runTimes.run(route, true), runTimes.run(route, false));
                    dtos.add(timetables.route(route, timetable, scheduler));
                }
                variants.add(new CityRailProposalDto.Variant(f.id(),
                        dtos.stream().mapToInt(CityRailProposalDto.Route::vehicles).sum(), dtos));
            }
            solutions.put(id, variants);
        });
        log.info("City rail proposal: {} trains kept clear of, {} variants worked out in {} ms", today.size(),
                ProposalNetwork.SOLUTIONS.size() * FREQUENCIES.size(), (System.nanoTime() - started) / 1_000_000);
        return new Stored(day, new Meta(rules(runTimes.newDwell(), today.size()), List.copyOf(served)), solutions);
    }

    private static CityRailProposalDto.Rules rules(int newDwell, int trains) {
        return new CityRailProposalDto.Rules(ProposalScheduler.HEADWAY / 60, ProposalScheduler.TURNAROUND / 60,
                ProposalTimetables.hhmm(ProposalScheduler.FIRST), ProposalTimetables.hhmm(ProposalScheduler.LAST),
                newDwell, RunTimeModel.HUB_DWELL, trains);
    }
}
