package com.transitanalytics.classification;

import com.transitanalytics.domain.Vehicle;
import com.transitanalytics.domain.VehicleClass;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static com.transitanalytics.domain.VehicleClass.DOUBLE_DECKER;
import static com.transitanalytics.domain.VehicleClass.MAXI;
import static com.transitanalytics.domain.VehicleClass.MEGA;
import static com.transitanalytics.domain.VehicleClass.MIDI;
import static org.assertj.core.api.Assertions.assertThat;

class VehicleClassifierTest {

    private final VehicleClassifier classifier = new VehicleClassifier(ClassificationConfigs.vehicles());

    /** Every model on the Białystok feed (BKM and the regional operators) as of November 2026. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "-", textBlock = """
            Solaris Urbino 18                          | MEGA          | 145
            Solaris Urbino 18 mild hybrid              | MEGA          | 145
            Solaris Urbino 18 W29                      | MEGA          | 145
            Mercedes-Benz Conecto G                    | MEGA          | 150
            Mercedes-Benz O530G C2                     | MEGA          | 164
            Mercedes-Benz O530G C2 Hybrid              | MEGA          | 164
            MAN NG313 Lion`s City G                    | MEGA          | 149
            MAN ND313 Lion`s City DD                   | DOUBLE_DECKER | 128
            Solaris Urbino 12 W33                      | MAXI          | 85
            Solaris Urbino 12 W13                      | MAXI          | 85
            Solaris Urbino 12 W9                       | MAXI          | 85
            Solaris Urbino 12 LE lite hybrid           | MAXI          | 85
            Solaris InterUrbino 12                     | MAXI          | -
            Solaris Urbino 15 LE                       | MAXI          | -
            Yutong U12                                 | MAXI          | 78
            Yutong ZK6128BEVG E12                      | MAXI          | 70
            Mercedes-Benz Conecto                      | MAXI          | 101
            Mercedes-Benz O530 C2                      | MAXI          | 100
            Mercedes-Benz O530 C2 Hybrid               | MAXI          | 100
            Volvo 7900 Hybrid 12,1m                    | MAXI          | 102
            MAN NL323 Lion`s City                      | MAXI          | -
            Volvo 8700 12,7m                           | MAXI          | -
            SOR C12                                    | MAXI          | -
            SOR C10,5                                  | MIDI          | -
            Irisbus Arway 10.6M                        | MIDI          | -
            Solaris Urbino 8,9 LE                      | MIDI          | -
            Mercedes-Benz Sprinter City 77             | MIDI          | -
            Mercedes-Benz 516 CDI Sprinter City 65     | MINI          | -
            Mercedes-Benz 517 CDI Sprinter Transfer 45 | MINI          | 23
            Mercedes-Benz 518 CDI / KHMC               | MINI          | -
            Karsan Jest                                | MINI          | 21
            """)
    void classesEveryModelOnTheFeed(String model, VehicleClass expected, Integer capacity) {
        VehicleClassifier.Spec spec = classifier.classify(model);
        assertThat(spec.vehicleClass()).isEqualTo(expected);
        assertThat(spec.capacity()).isEqualTo(capacity);
    }

    @Test
    void takesAnUnknownModelsLengthFromItsName() {
        assertThat(classifier.classify("Solaris Urbino 9 LE electric").vehicleClass()).isEqualTo(MIDI);
        assertThat(classifier.classify("Solaris Urbino 18 hydrogen").vehicleClass()).isEqualTo(MEGA);
        assertThat(classifier.classify("Some Bus 13.5m").length()).isEqualTo(13.5);
        assertThat(classifier.classify("Some Bus 13.5m").vehicleClass()).isEqualTo(MAXI);
        assertThat(classifier.classify("Yutong E7").vehicleClass()).isNull();
        assertThat(classifier.classify("Yutong E10").vehicleClass()).isEqualTo(MIDI);
        assertThat(classifier.classify("Some DD Bus").vehicleClass()).isEqualTo(DOUBLE_DECKER);
        assertThat(classifier.classify("Isuzu Citiport")).isEqualTo(new VehicleClassifier.Spec(null, null, null));
        assertThat(classifier.classify(" ")).isEqualTo(new VehicleClassifier.Spec(null, null, null));
        assertThat(classifier.classify(null).vehicleClass()).isNull();
    }

    @Test
    void appliesTheClassAndKnownCapacityButKeepsACapacityEnteredByHand() {
        Vehicle known = new Vehicle();
        assertThat(VehicleClasses.apply(known, classifier.classify("Solaris Urbino 18"))).isTrue();
        assertThat(known.getVehicleClass()).isEqualTo(MEGA);
        assertThat(known.getCapacity()).isEqualTo(145);
        assertThat(VehicleClasses.apply(known, classifier.classify("Solaris Urbino 18"))).isFalse();

        Vehicle byHand = new Vehicle();
        byHand.setCapacity(40);
        VehicleClasses.apply(byHand, classifier.classify("Solaris Urbino 8,9 LE"));
        assertThat(byHand.getVehicleClass()).isEqualTo(MIDI);
        assertThat(byHand.getCapacity()).isEqualTo(40);
    }
}
