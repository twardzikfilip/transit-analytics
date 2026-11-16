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
    private final VehicleService service = new VehicleService(repository);

    @Test
    @SuppressWarnings("unchecked")
    void registersOnlyVehiclesThatAreNotStoredYet() {
        given(repository.findByVehicleNumberIn(anyCollection())).willReturn(List.of(vehicle(1L, "230")));
        given(repository.saveAll(any(Iterable.class))).willAnswer(inv -> {
            List<Vehicle> saved = new ArrayList<>();
            long id = 10;
            for (Vehicle v : (Iterable<Vehicle>) inv.getArgument(0)) {
                v.setId(id++);
                saved.add(v);
            }
            return saved;
        });

        Map<String, Long> ids = service.registerMissing(List.of(
                new VehicleDto.Discovered("230", "BUS", "BKM", "Solaris Urbino 12 W33"),
                new VehicleDto.Discovered("009", "BUS", "BKM", "Yutong ZK6128BEVG E12"),
                new VehicleDto.Discovered("009", "BUS", "BKM", "Yutong ZK6128BEVG E12")));

        assertThat(ids).containsExactlyInAnyOrderEntriesOf(Map.of("230", 1L, "009", 10L));
        verify(repository).saveAll(org.mockito.ArgumentMatchers.<Iterable<Vehicle>>argThat(list -> {
            List<Vehicle> vehicles = new ArrayList<>();
            list.forEach(vehicles::add);
            return vehicles.size() == 1
                    && vehicles.get(0).getVehicleNumber().equals("009")
                    && vehicles.get(0).getAgency().equals("BKM")
                    && vehicles.get(0).getModel().equals("Yutong ZK6128BEVG E12");
        }));
    }

    private static Vehicle vehicle(Long id, String number) {
        Vehicle v = new Vehicle();
        v.setId(id);
        v.setVehicleNumber(number);
        return v;
    }
}
