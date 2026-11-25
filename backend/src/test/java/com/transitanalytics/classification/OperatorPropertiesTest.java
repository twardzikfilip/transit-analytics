package com.transitanalytics.classification;

import com.transitanalytics.domain.Vehicle;
import com.transitanalytics.repository.VehicleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

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
    void operatorsAreStoredForAllBusesAndForNewOnes() {
        VehicleRepository vehicles = mock(VehicleRepository.class);
        Vehicle known = bus("230");
        Vehicle fresh = bus("931");
        given(vehicles.findAll()).willReturn(List.of(known));
        given(vehicles.findUnclassified()).willReturn(List.of(fresh));
        VehicleClasses classes = new VehicleClasses(vehicles,
                new VehicleClassifier(ClassificationConfigs.vehicles()), operators);

        classes.applyAll();
        assertThat(known.getOperatingAgency()).isEqualTo("KZK");
        assertThat(classes.applyToNew()).isEqualTo(1);
        assertThat(fresh.getOperatingAgency()).isEqualTo("KPKM");
        assertThat(classes.applyToNew()).as("nothing left to change").isZero();
    }

    private static Vehicle bus(String number) {
        Vehicle v = new Vehicle();
        v.setVehicleNumber(number);
        v.setAgency("BKM");
        return v;
    }
}
