package com.transitanalytics.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "vehicle")
public class Vehicle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "vehicle_number", nullable = false, unique = true, length = 32)
    private String vehicleNumber;

    @Column(nullable = false, length = 32)
    private String type;

    private Integer capacity;

    @Column(length = 64)
    private String agency;

    @Column(length = 128)
    private String model;

    /** Size class, from the model (see classification/vehicles.yml); null when the model isn't known. */
    @Column(name = "vehicle_class", length = 16)
    private VehicleClass vehicleClass;

    /** Who runs the bus: KPKM, KPK or KZK for BKM, else its agency (classification/operators.yml); null if not known. */
    @Column(name = "operating_agency", length = 64)
    private String operatingAgency;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getVehicleNumber() {
        return vehicleNumber;
    }

    public void setVehicleNumber(String vehicleNumber) {
        this.vehicleNumber = vehicleNumber;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Integer getCapacity() {
        return capacity;
    }

    public void setCapacity(Integer capacity) {
        this.capacity = capacity;
    }

    public String getAgency() {
        return agency;
    }

    public void setAgency(String agency) {
        this.agency = agency;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public VehicleClass getVehicleClass() {
        return vehicleClass;
    }

    public void setVehicleClass(VehicleClass vehicleClass) {
        this.vehicleClass = vehicleClass;
    }

    public String getOperatingAgency() {
        return operatingAgency;
    }

    public void setOperatingAgency(String operatingAgency) {
        this.operatingAgency = operatingAgency;
    }
}
