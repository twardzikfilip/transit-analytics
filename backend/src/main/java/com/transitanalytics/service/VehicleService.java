package com.transitanalytics.service;

import com.transitanalytics.classification.VehicleClasses;
import com.transitanalytics.classification.VehicleClassifier;
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
public class VehicleService extends CrudService<Vehicle, VehicleDto.Request, VehicleDto.Response, NoFilter> {

    private final VehicleRepository vehicles;
    private final VehicleClassifier classifier;

    public VehicleService(VehicleRepository repository, VehicleClassifier classifier) {
        super(repository, "Vehicle");
        this.vehicles = repository;
        this.classifier = classifier;
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
                .map(this::toEntity)
                .toList();
        vehicles.saveAll(missing).forEach(v -> ids.put(v.getVehicleNumber(), v.getId()));
        return ids;
    }

    private Vehicle toEntity(VehicleDto.Discovered d) {
        Vehicle v = new Vehicle();
        v.setVehicleNumber(d.vehicleNumber());
        v.setType(d.type());
        v.setAgency(d.agency());
        v.setModel(d.model());
        VehicleClasses.apply(v, classifier.classify(d.model()));
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
        e.setAgency(r.agency());
        e.setModel(r.model());
        // The class follows the model; a capacity given is kept, else the model's (when known).
        VehicleClassifier.Spec spec = classifier.classify(r.model());
        e.setVehicleClass(spec.vehicleClass());
        e.setCapacity(r.capacity() != null ? r.capacity() : spec.capacity());
    }

    @Override
    protected VehicleDto.Response toResponse(Vehicle e) {
        return new VehicleDto.Response(e.getId(), e.getVehicleNumber(), e.getType(), e.getCapacity(),
                e.getAgency(), e.getModel(), e.getVehicleClass());
    }
}
