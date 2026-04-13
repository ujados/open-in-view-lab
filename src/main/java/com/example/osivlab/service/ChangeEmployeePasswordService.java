package com.example.osivlab.service;

import com.example.osivlab.domain.Employee;
import com.example.osivlab.dto.EmployeeDto;
import com.example.osivlab.mapper.EmployeeMapper;
import com.example.osivlab.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Demonstrates the dirty checking bug with OSIV.
 *
 * The buggy method has NO @Transactional. It loads the entity, modifies it,
 * then runs a TransactionTemplate for "post-processing". With OSIV, the
 * TransactionTemplate reuses the OSIV EntityManager → Hibernate flushes
 * ALL dirty entities at commit → password silently persisted.
 *
 * Without OSIV, the entity from findById is detached. The TransactionTemplate
 * creates its own EntityManager where the entity doesn't exist → no flush.
 */
@Service
@RequiredArgsConstructor
public class ChangeEmployeePasswordService {

    private final EmployeeRepository employeeRepository;
    private final EmployeeMapper employeeMapper;
    private final TransactionTemplate transactionTemplate;

    /**
     * BUGGY: NO @Transactional. Password persistence depends entirely on OSIV.
     *
     * With OSIV=true:
     *   1. findById uses the OSIV EntityManager → entity stays managed
     *   2. setPassword modifies the managed entity (dirty)
     *   3. TransactionTemplate opens a tx in the OSIV EM → commit flushes dirty entity → PERSISTED
     *
     * With OSIV=false:
     *   1. findById opens own EM+tx, loads entity, commits, closes EM → entity detached
     *   2. setPassword modifies detached entity
     *   3. TransactionTemplate opens new EM+tx → entity NOT in this PC → no flush → LOST
     */
    public EmployeeDto changePasswordBuggy(Long employeeId, String newPassword) {
        Employee employee = employeeRepository.findWithRolesAndPermissionsById(employeeId)
                .orElseThrow(() -> new RuntimeException("Employee not found: " + employeeId));

        // Modify the managed (OSIV) or detached (no OSIV) entity
        employee.setPassword(newPassword);

        // "Post-processing" transaction — this is the trigger.
        // In real code this could be logging, notifications, audit trail, etc.
        // With OSIV: commit flushes ALL dirty entities in the shared EM → password saved
        // Without OSIV: separate EM, employee not in PC → password lost
        transactionTemplate.executeWithoutResult(status -> {
            // Any transactional work here. Even if empty, commit triggers flush.
            employee.getName(); // reference the entity to ensure it's in scope
        });

        return employeeMapper.toDto(employee);
    }

    /**
     * CORRECT: explicit @Transactional + saveAndFlush. Works regardless of OSIV.
     */
    @Transactional
    public EmployeeDto changePasswordCorrect(Long employeeId, String newPassword) {
        Employee employee = employeeRepository.findWithRolesAndPermissionsById(employeeId)
                .orElseThrow(() -> new RuntimeException("Employee not found: " + employeeId));

        employee.setPassword(newPassword);
        employeeRepository.saveAndFlush(employee);

        return employeeMapper.toDto(employee);
    }
}
