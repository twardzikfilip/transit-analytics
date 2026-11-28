package com.transitanalytics.classification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperatorPropertiesTest {

    private final OperatorProperties operators = ClassificationConfigs.operators();

    @ParameterizedTest
    @CsvSource({"001,KZK", "006,KZK", "007,KPKM", "010,KPKM", "012,KPKM", "013,KPK", "030,KPK", "031,KZK", "040,KZK",
            "041,KPKM", "050,KPKM", "100,KZK", "199,KZK", "230,KZK", "299,KZK", "300,KPKM", "399,KPKM", "400,KPK",
            "500,KPK", "600,KPK", "799,KPK", "800,KPKM", "931,KPKM", "999,KPKM", "000,", "051,", "099,", "1020,",
            "31,", "BMC,"})
    void aBkmBusBelongsToTheCompanyItsNumberDoes(String number, String company) {
        assertThat(operators.operatingAgency("BKM", number)).isEqualTo(company);
    }

    @Test
    void noNumberBelongsToTwoCompanies() {
        for (int i = 0; i < 1000; i++) {
            String number = "%03d".formatted(i);
            assertThat(operators.companies().stream().filter(c -> c.runs(number))).as(number)
                    .hasSizeLessThanOrEqualTo(1);
        }
    }

    @ParameterizedTest
    @CsvSource({"WSCHODEXPRESS,1020", "TUROSN,RK 86021", "PKSNova,230"})
    void otherAgenciesRunTheirOwnBuses(String agency, String number) {
        assertThat(operators.operatingAgency(agency, number)).isEqualTo(agency);
    }

    @Test
    void aBusWithoutAnAgencyHasNoOperator() {
        assertThat(operators.operatingAgency(null, "230")).isNull();
    }

    @Test
    void refusesNumbersThatAreNoRange() {
        for (String bad : List.of("1xx", "40-031", "040-031", "")) {
            assertThatThrownBy(() -> new OperatorProperties.Company("X", null, null, null, null, List.of(bad)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(new OperatorProperties.Company("X", null, null, null, null, List.of("017")).runs("017")).isTrue();
    }

    @Test
    void classingSetsTheOperatingCompany() {
        VehicleClasses classes = new VehicleClasses(null, new VehicleClassifier(ClassificationConfigs.vehicles()),
                operators);

        assertThat(classes.classed(bus("230")).operatingAgency()).isEqualTo("KZK");
        assertThat(classes.classed(bus("931")).operatingAgency()).isEqualTo("KPKM");
    }

    private static VehicleClasses.Vehicle bus(String number) {
        return new VehicleClasses.Vehicle(1, number, "BKM", null, null, null, null);
    }
}
