package com.transitanalytics.geo;

import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;

class CsvTableTest {

    @Test
    void readsCommentsAndRowsByColumnWithQuotedCommas() {
        CsvTable table = CsvTable.read(new StringReader("""
                # Residents, from the register.
                # Second line.
                name,population,source

                "Czerewki, Kożany",144,"Gmina, table 1"
                Turczyn,,
                "Say \"\"hi\"\"",1 200,x
                """));
        assertThat(table.about()).isEqualTo("Residents, from the register. Second line.");
        assertThat(table.header()).containsExactly("name", "population", "source");
        assertThat(table.rows()).hasSize(3);
        assertThat(table.rows().get(0).get("name")).isEqualTo("Czerewki, Kożany");
        assertThat(table.rows().get(0).integer("population")).isEqualTo(144);
        assertThat(table.rows().get(0).text("source")).isEqualTo("Gmina, table 1");
        assertThat(table.rows().get(1).integer("population")).isNull();
        assertThat(table.rows().get(1).text("source")).isNull();
        assertThat(table.rows().get(1).get("missing column")).isEmpty();
        assertThat(table.rows().get(2).get("name")).isEqualTo("Say \"hi\"");
        assertThat(table.rows().get(2).integer("population")).isEqualTo(1200);
    }
}
