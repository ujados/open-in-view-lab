package com.example.osivlab.service;

import com.example.osivlab.domain.Employee;
import com.example.osivlab.dto.EmployeeDto;
import com.example.osivlab.mapper.EmployeeMapper;
import com.example.osivlab.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EmployeeService {

    private final EmployeeRepository employeeRepository;
    private final EmployeeMapper employeeMapper;

    /**
     * Without @Transactional — relies on OSIV for lazy loading.
     * Will throw LazyInitializationException if OSIV is disabled.
     */
    public EmployeeDto getEmployee(Long id) {
        Employee employee = employeeRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Employee not found: " + id));
        return employeeMapper.toDto(employee);
    }

    /**
     * With @Transactional — works regardless of OSIV setting.
     */
    @Transactional(readOnly = true)
    public EmployeeDto getEmployeeTransactional(Long id) {
        Employee employee = employeeRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Employee not found: " + id));
        return employeeMapper.toDto(employee);
    }

    /**
     * With @EntityGraph — works regardless of OSIV setting, single query.
     */
    public EmployeeDto getEmployeeWithEntityGraph(Long id) {
        Employee employee = employeeRepository.findWithRolesAndPermissionsById(id)
                .orElseThrow(() -> new RuntimeException("Employee not found: " + id));
        return employeeMapper.toDto(employee);
    }
}
