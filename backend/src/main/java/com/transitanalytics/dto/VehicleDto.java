package com.transitanalytics.dto;

import com.transitanalytics.domain.VehicleClass;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public final class VehicleDto {

    private VehicleDto() {
    }

    public record Request(
            @NotBlank @Size(max = 32) String vehicleNumber,
            @NotBlank @Size(max = 32) String type,
            @PositiveOrZero Integer capacity,
            @Size(max = 64) String agency,
            @Size(max = 128) String model) {
    }

    /**
     * @param vehicleClass    size class from the model (see classification/vehicles.yml); null when not known
     * @param operatingAgency who runs it: KPKM, KPK or KZK for BKM, else its agency (see
     *                        classification/operators.yml); null when not known
     */
    public record Response(Long id, String vehicleNumber, String type, Integer capacity, String agency, String model,
                           VehicleClass vehicleClass, String operatingAgency) {
    }
}
