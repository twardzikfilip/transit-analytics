package com.transitanalytics.service;

import com.transitanalytics.domain.Vehicle;
import com.transitanalytics.dto.VehicleDto;
import com.transitanalytics.repository.VehicleRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class VehicleServiceTest {

    private final VehicleRepository repository = mock(VehicleRepository.class);
    private final VehicleService service = new VehicleService(repository,
            new com.transitanalytics.classification.VehicleClassifier(
                    com.transitanalytics.classification.ClassificationConfigs.vehicles()),
            com.transitanalytics.classification.ClassificationConfigs.operators());

    @Test
    void classesAVehicleFromItsModelAndKeepsACapacityGiven() {
        given(repository.save(any(Vehicle.class))).willAnswer(inv -> inv.getArgument(0));

        var big = service.create(new VehicleDto.Request("1", "BUS", null, "BKM", "Solaris Urbino 18"));
        assertThat(big.vehicleClass()).isEqualTo(com.transitanalytics.domain.VehicleClass.MEGA);
        assertThat(big.capacity()).isEqualTo(145);

        var kept = service.create(new VehicleDto.Request("2", "BUS", 60, "BKM", "Solaris Urbino 18"));
        assertThat(kept.capacity()).isEqualTo(60);
        var unknown = service.create(new VehicleDto.Request("3", "BUS", null, "BKM", "Isuzu Citiport"));
        assertThat(unknown.vehicleClass()).isNull();
        assertThat(unknown.capacity()).isNull();
    }

    @Test
    void setsTheCompanyRunningABus() {
        given(repository.save(any(Vehicle.class))).willAnswer(inv -> inv.getArgument(0));

        assertThat(service.create(new VehicleDto.Request("230", "BUS", null, "BKM", null)).operatingAgency())
                .isEqualTo("KZK");
        assertThat(service.create(new VehicleDto.Request("230", "BUS", null, "TUROSN", null)).operatingAgency())
                .isEqualTo("TUROSN");
    }

    private static Vehicle vehicle(Long id, String number) {
        Vehicle v = new Vehicle();
        v.setId(id);
        v.setVehicleNumber(number);
        return v;
    }
}
