package com.transitanalytics.domain;

import jakarta.persistence.*;
import java.time.LocalDate;

/** A service added or removed on a date (GTFS calendar_dates.txt). */
@Entity
@Table(name = "calendar_date")
public class CalendarDate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "service_id", nullable = false, length = 64)
    private String serviceId;

    @Column(nullable = false)
    private LocalDate date;

    /** 1 = service added, 2 = service removed. */
    @Column(name = "exception_type", nullable = false)
    private Short exceptionType;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getServiceId() {
        return serviceId;
    }

    public void setServiceId(String serviceId) {
        this.serviceId = serviceId;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public Short getExceptionType() {
        return exceptionType;
    }

    public void setExceptionType(Short exceptionType) {
        this.exceptionType = exceptionType;
    }
}
