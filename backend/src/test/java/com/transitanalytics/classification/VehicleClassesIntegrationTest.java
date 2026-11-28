package com.transitanalytics.classification;

import com.transitanalytics.DbIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/** Classing the buses stored: all at startup, then those transit-recorder registered since. */
@DbIntegrationTest
@Transactional
class VehicleClassesIntegrationTest {

    @Autowired
    VehicleClasses classes;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void classesNewBusesOnce() {
        classes.applyAll();
        jdbc.update("INSERT INTO vehicle (vehicle_number, type, agency, model) VALUES ('IT-931', 'BUS', 'BKM', ?)",
                "Solaris Urbino 18");

        assertThat(classes.applyToNew()).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT vehicle_class, capacity FROM vehicle WHERE vehicle_number = 'IT-931'"))
                .containsEntry("vehicle_class", "mega").containsEntry("capacity", 145);
        assertThat(classes.applyToNew()).as("nothing left to change").isZero();
    }
}
