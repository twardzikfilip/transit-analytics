package com.transitanalytics.timetable;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StopPlacesTest {

    private static StopPlaces.StopRef at(String name, double lat, double lon) {
        return new StopPlaces.StopRef(name, lat, lon);
    }

    @Test
    void stripsTheStopCodeFromNames() {
        assertThat(StopPlaces.stopName("Lodowa/Chłodna(654)")).isEqualTo("Lodowa/Chłodna");
        assertThat(StopPlaces.stopName("Koplany/Cegielnia NŻ (911)")).isEqualTo("Koplany/Cegielnia NŻ");
        assertThat(StopPlaces.stopName("Rynek")).isEqualTo("Rynek");
    }

    @Test
    void stopsCloserThan500MetresAreOnePlaceNamedWhereRunsEnd() {
        // Centrum st. 1 (departures) and st. 4 (arrivals) are ~55 m apart; Juchnowiec is far away.
        var st1 = at("Centrum st. 1", 53.13350, 23.14400);
        var st4 = at("Centrum st. 4", 53.13370, 23.14470);
        var juch = at("Juchnowiec", 53.04000, 23.21000);
        var places = StopPlaces.places(List.of(st4, st4, juch), List.of(st1, st1, st1, juch));

        assertThat(places).extracting(StopPlaces.Place::name).containsExactly("Centrum st. 4", "Juchnowiec");
        assertThat(StopPlaces.atPlace(places, at("Centrum st. 1", 53.13350, 23.14400))).isTrue();
        // A stop nearby that isn't one of the place's stops is somewhere else.
        assertThat(StopPlaces.atPlace(places, at("Elsewhere", 53.13775, 23.14470))).isFalse();
        // Near (450 m from st. 4) joins; Far is 350 m from Near but 800 m from st. 4, so it stays apart.
        var near = at("Near", 53.13775, 23.14470);
        var far = at("Far", 53.14090, 23.14470);
        assertThat(StopPlaces.places(List.of(st4, near, far), List.of(st1)))
                .extracting(StopPlaces.Place::name).containsExactly("Centrum st. 4", "Far");
        assertThat(StopPlaces.meters(53.13370, 23.14470, 53.13775, 23.14470)).isBetween(440.0, 460.0);
    }
}
