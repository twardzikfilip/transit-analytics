package com.transitanalytics.trips;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class AnomalyNotesTest {

    @Test
    void storedLongNotesBecomeShortOnes() {
        JsonNode old = JsonMapper.builder().build().readTree("""
                [{"trip": {"note": [
                    {"key": "runInFull", "params": {}},
                    {"key": "lostForGood", "params": {"bus": "1", "stop": "A", "index": 3, "total": 9, "time": "07:00"}},
                    {"key": "silentAtOnce", "params": {"bus": "1"}}],
                  "changeFate": "broken-down"}},
                 {"trip": {"note": [{"key": "noBus", "params": {}}, {"key": "brokenDown", "params": {"bus": "2"}}]}}]""");

        JsonNode upgraded = AnomalyNotes.upgrade(old);
        JsonNode note = upgraded.get(0).get("trip").get("note");

        assertThat(note).extracting(p -> p.get("key").asString()).containsExactly("lostAt", "notUsedAgain");
        assertThat(note.get(0).get("params").get("index").asInt()).isEqualTo(3);
        assertThat(upgraded.get(0).get("trip").get("changeFate").asString()).isEqualTo("not-used");
        // Phrases written now are kept as they are.
        assertThat(upgraded.get(1).get("trip").get("note")).extracting(p -> p.get("key").asString())
                .containsExactly("noBus", "notUsedAgain");
    }
}
