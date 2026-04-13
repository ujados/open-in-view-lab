package com.example.osivlab.repository;

import com.example.osivlab.domain.Employee;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    @EntityGraph(attributePaths = {"roles", "roles.permissions"})
    Optional<Employee> findWithRolesAndPermissionsById(Long id);
}
