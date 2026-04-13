package com.example.osivlab.repository;

import com.example.osivlab.domain.DepartmentWithBatchSize;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DepartmentWithBatchSizeRepository extends JpaRepository<DepartmentWithBatchSize, Long> {
}
