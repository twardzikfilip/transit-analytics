package com.transitanalytics.service;

import com.transitanalytics.classification.VehicleClassifier;
import com.transitanalytics.domain.Vehicle;
import com.transitanalytics.dto.VehicleDto;
import com.transitanalytics.repository.VehicleRepository;
import org.springframework.stereotype.Service;

@Service
public class VehicleService extends CrudService<Vehicle, VehicleDto.Request, VehicleDto.Response, NoFilter> {

    private final VehicleRepository vehicles;
    private final VehicleClassifier classifier;

    public VehicleService(VehicleRepository repository, VehicleClassifier classifier) {
        super(repository, "Vehicle");
        this.vehicles = repository;
        this.classifier = classifier;
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
