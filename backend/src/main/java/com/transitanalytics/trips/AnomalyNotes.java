package com.transitanalytics.trips;

import com.transitanalytics.dto.TripAnomalyDto.Phrase;
import com.transitanalytics.data.ScheduledTrip;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Set;

/**
 * The short notes of an abnormal trip, for the page to write in the reader's language ({@code tripNotes} in the
 * frontend's translations). Stops are numbered on the trip, from 1.
 */
final class AnomalyNotes {

    private AnomalyNotes() {
    }

    /** No bus ran the brigade (or the route) while recording went on. */
    static Phrase noBus() {
        return Phrase.of("noBus");
    }

    /** The brigade's bus reported nothing while the trip should have run… */
    static Phrase busSilent(String bus) {
        return Phrase.of("busSilent", "bus", bus);
    }

    /** …it was still on an earlier trip of the brigade, this many minutes late… */
    static Phrase busLate(String bus, int minutes) {
        return Phrase.of("busLate", "bus", bus, "n", minutes);
    }

    /** …it reported another trip of the brigade… */
    static Phrase busOnOtherTrip(String bus) {
        return Phrase.of("busOnOtherTrip", "bus", bus);
    }

    /** …or it ran another route. */
    static Phrase busOnRoute(String bus, String route) {
        return Phrase.of("busOnLine", "bus", bus, "route", route);
    }

    static Phrase detour(int meters) {
        return Phrase.of("detour", "meters", meters);
    }

    /** It came from another trip and joined midway (a short turn). */
    static Phrase joinedAt(ScheduledTrip trip, int index) {
        return at("joinedAt", trip, index);
    }

    /** It started reporting midway. */
    static Phrase seenFrom(ScheduledTrip trip, int index) {
        return at("seenFrom", trip, index);
    }

    /** It left the trip early, reporting all along (a short turn). */
    static Phrase leftAt(ScheduledTrip trip, int index) {
        return at("leftAt", trip, index);
    }

    /** It stopped reporting, and came back on the brigade's next trip: it probably finished this one. */
    static Phrase silentFrom(ScheduledTrip trip, int index) {
        return at("silentFrom", trip, index);
    }

    /** It stopped reporting and didn't come back on the brigade. */
    static Phrase lostAt(ScheduledTrip trip, int index) {
        return at("lostAt", trip, index);
    }

    /** Another bus took over where the first stopped. */
    static Phrase busChanged(String bus, String other, ScheduledTrip trip, int index) {
        return Phrase.of("busChanged", "bus", bus, "other", other, "index", index + 1, "total", trip.size());
    }

    /** The replaced bus wasn't used again that day… */
    static Phrase notUsedAgain(String bus) {
        return Phrase.of("notUsedAgain", "bus", bus);
    }

    /** …came back on the brigade later… */
    static Phrase backLater(String bus) {
        return Phrase.of("backLater", "bus", bus);
    }

    /** …or ran another brigade. */
    static Phrase usedElsewhere(String bus) {
        return Phrase.of("usedElsewhere", "bus", bus);
    }

    /** {@code index}: 0-based on the trip. */
    private static Phrase at(String key, ScheduledTrip trip, int index) {
        return Phrase.of(key, "index", index + 1, "total", trip.size());
    }

    /** The phrases written now. */
    private static final Set<String> KEYS = Set.of("noBus", "busSilent", "busLate", "busOnOtherTrip", "busOnLine",
            "detour", "joinedAt", "seenFrom", "leftAt", "silentFrom", "lostAt", "busChanged", "notUsedAgain",
            "backLater", "usedElsewhere");

    /** Phrases stored in an older form: each one's key now; those neither here nor in {@link #KEYS} are left out. */
    private static final Map<String, String> OLD_KEYS = Map.ofEntries(
            Map.entry("noBusOnBrigade", "noBus"), Map.entry("noBusOnLine", "noBus"),
            Map.entry("onOtherTrip", "busOnOtherTrip"), Map.entry("onOtherTripTowards", "busOnOtherTrip"),
            Map.entry("busOnLineBrigade", "busOnLine"), Map.entry("joinedMidway", "joinedAt"),
            Map.entry("startedReportingMidway", "seenFrom"), Map.entry("leftTrip", "leftAt"),
            Map.entry("probablyFinished", "silentFrom"), Map.entry("lostForGood", "lostAt"),
            Map.entry("lostSeenAgain", "lostAt"), Map.entry("brokenDown", "notUsedAgain"),
            Map.entry("stoodStillThere", "notUsedAgain"), Map.entry("silentAtOnce", "notUsedAgain"),
            Map.entry("silentAfterStanding", "notUsedAgain"), Map.entry("movedOffAtOnce", "notUsedAgain"),
            Map.entry("movedOffAfterStanding", "notUsedAgain"), Map.entry("backOnBrigade", "backLater"),
            Map.entry("usedAgainOn", "usedElsewhere"));

    /** The replaced bus's fate stored under its old name. */
    private static final String OLD_NOT_USED = "broken-down";

    /**
     * Stored trip outcomes as written now: old phrases become the short ones (their values are a superset), other
     * unknown phrases are left out, and old fate names are renamed. Days whose trips the timetable no longer has keep
     * their outcomes.
     */
    static JsonNode upgrade(JsonNode node) {
        if (node instanceof ArrayNode array) {
            array.forEach(AnomalyNotes::upgrade);
        } else if (node instanceof ObjectNode object) {
            if (object.get("note") instanceof ArrayNode note) {
                ArrayNode upgraded = note.arrayNode();
                for (JsonNode phrase : note) {
                    String old = phrase.path("key").asString();
                    String key = KEYS.contains(old) ? old : OLD_KEYS.get(old);
                    if (key != null) {
                        ((ObjectNode) phrase).put("key", key);
                        upgraded.add(phrase);
                    }
                }
                object.set("note", upgraded);
            }
            if (OLD_NOT_USED.equals(object.path("changeFate").asString(null))) {
                object.put("changeFate", TripHandovers.Fate.NOT_USED.key);
            }
            object.forEach(AnomalyNotes::upgrade);
        }
        return node;
    }
}
