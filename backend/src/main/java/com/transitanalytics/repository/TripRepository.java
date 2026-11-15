package com.transitanalytics.repository;

import com.transitanalytics.domain.Trip;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TripRepository extends JpaRepository<Trip, Long> {}
