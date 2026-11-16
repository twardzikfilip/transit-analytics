package com.transitanalytics.domain;

import jakarta.persistence.*;

/** When a trip calls at a stop (GTFS stop_times.txt). */
@Entity
@Table(name = "stop_time")
public class StopTime {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stop_id", nullable = false)
    private Stop stop;

    @Column(name = "stop_sequence", nullable = false)
    private Integer stopSequence;

    /** "HH:MM:SS", may exceed 24:00:00. */
    @Column(name = "arrival_time", length = 8)
    private String arrivalTime;

    @Column(name = "departure_time", length = 8)
    private String departureTime;

    @Column(name = "stop_headsign")
    private String stopHeadsign;

    @Column(name = "pickup_type")
    private Short pickupType;

    @Column(name = "drop_off_type")
    private Short dropOffType;

    @Column(name = "shape_dist_traveled")
    private Double shapeDistTraveled;

    private Short timepoint;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Trip getTrip() {
        return trip;
    }

    public void setTrip(Trip trip) {
        this.trip = trip;
    }

    public Stop getStop() {
        return stop;
    }

    public void setStop(Stop stop) {
        this.stop = stop;
    }

    public Integer getStopSequence() {
        return stopSequence;
    }

    public void setStopSequence(Integer stopSequence) {
        this.stopSequence = stopSequence;
    }

    public String getArrivalTime() {
        return arrivalTime;
    }

    public void setArrivalTime(String arrivalTime) {
        this.arrivalTime = arrivalTime;
    }

    public String getDepartureTime() {
        return departureTime;
    }

    public void setDepartureTime(String departureTime) {
        this.departureTime = departureTime;
    }

    public String getStopHeadsign() {
        return stopHeadsign;
    }

    public void setStopHeadsign(String stopHeadsign) {
        this.stopHeadsign = stopHeadsign;
    }

    public Short getPickupType() {
        return pickupType;
    }

    public void setPickupType(Short pickupType) {
        this.pickupType = pickupType;
    }

    public Short getDropOffType() {
        return dropOffType;
    }

    public void setDropOffType(Short dropOffType) {
        this.dropOffType = dropOffType;
    }

    public Double getShapeDistTraveled() {
        return shapeDistTraveled;
    }

    public void setShapeDistTraveled(Double shapeDistTraveled) {
        this.shapeDistTraveled = shapeDistTraveled;
    }

    public Short getTimepoint() {
        return timepoint;
    }

    public void setTimepoint(Short timepoint) {
        this.timepoint = timepoint;
    }
}
