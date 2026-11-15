package com.transitanalytics.repository;

import com.transitanalytics.domain.Stop;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StopRepository extends JpaRepository<Stop, Long> {}
