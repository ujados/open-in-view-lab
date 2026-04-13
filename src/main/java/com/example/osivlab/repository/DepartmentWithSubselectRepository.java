package com.example.osivlab.repository;

import com.example.osivlab.domain.DepartmentWithSubselect;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DepartmentWithSubselectRepository extends JpaRepository<DepartmentWithSubselect, Long> {
}
