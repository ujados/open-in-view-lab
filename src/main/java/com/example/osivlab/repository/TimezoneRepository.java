package com.example.osivlab.repository;

import com.example.osivlab.domain.Timezone;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TimezoneRepository extends JpaRepository<Timezone, Long> {
}
