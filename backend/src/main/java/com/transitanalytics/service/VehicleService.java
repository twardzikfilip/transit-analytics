package com.transitanalytics.service;

import com.transitanalytics.domain.Vehicle;
import com.transitanalytics.dto.VehicleDto;
import com.transitanalytics.repository.VehicleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class VehicleService extends CrudService<Vehicle, VehicleDto.Request, VehicleDto.Response> {

    private final VehicleRepository vehicles;

    public VehicleService(VehicleRepository repository) {
        super(repository, "Vehicle");
        this.vehicles = repository;
    }

    /**
     * Stores the vehicles whose number is not in the database yet; existing rows are left untouched.
     *
     * @return database id per vehicle number, for every given vehicle
     */
    @Transactional
    public Map<String, Long> registerMissing(Collection<VehicleDto.Discovered> discovered) {
        Map<String, VehicleDto.Discovered> byNumber = new LinkedHashMap<>();
        discovered.forEach(d -> byNumber.putIfAbsent(d.vehicleNumber(), d));

        Map<String, Long> ids = new HashMap<>();
        vehicles.findByVehicleNumberIn(byNumber.keySet()).forEach(v -> ids.put(v.getVehicleNumber(), v.getId()));

        List<Vehicle> missing = byNumber.values().stream()
                .filter(d -> !ids.containsKey(d.vehicleNumber()))
                .map(VehicleService::toEntity)
                .toList();
        vehicles.saveAll(missing).forEach(v -> ids.put(v.getVehicleNumber(), v.getId()));
        return ids;
    }

    private static Vehicle toEntity(VehicleDto.Discovered d) {
        Vehicle v = new Vehicle();
        v.setVehicleNumber(d.vehicleNumber());
        v.setType(d.type());
        v.setAgency(d.agency());
        v.setModel(d.model());
        return v;
    }

    @Override
    protected Vehicle newEntity() {
        return new Vehicle();
    }

    @Override
    protected void apply(VehicleDto.Request r, Vehicle e, boolean creating) {
        e.setVehicleNumber(r.vehicleNumber());
        e.setType(r.type());
        e.setCapacity(r.capacity());
        if (r.active() != null) {
            e.setActive(r.active());
        }
        e.setAgency(r.agency());
        e.setModel(r.model());
    }

    @Override
    protected VehicleDto.Response toResponse(Vehicle e) {
        return new VehicleDto.Response(e.getId(), e.getVehicleNumber(), e.getType(), e.getCapacity(), e.isActive(),
                e.getAgency(), e.getModel());
    }
}
