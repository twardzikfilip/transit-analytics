package com.transitanalytics.service;

import com.transitanalytics.domain.Vehicle;
import com.transitanalytics.dto.VehicleDto;
import com.transitanalytics.repository.VehicleRepository;
import org.springframework.stereotype.Service;

@Service
public class VehicleService extends CrudService<Vehicle, VehicleDto.Request, VehicleDto.Response> {

    public VehicleService(VehicleRepository repository) {
        super(repository, "Vehicle");
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
    }

    @Override
    protected VehicleDto.Response toResponse(Vehicle e) {
        return new VehicleDto.Response(e.getId(), e.getVehicleNumber(), e.getType(), e.getCapacity(), e.isActive());
    }
}
