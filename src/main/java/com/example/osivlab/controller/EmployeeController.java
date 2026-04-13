package com.example.osivlab.controller;

import com.example.osivlab.dto.EmployeeDto;
import com.example.osivlab.service.ChangeEmployeePasswordService;
import com.example.osivlab.service.EmployeeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/employees")
@RequiredArgsConstructor
public class EmployeeController {

    private final EmployeeService employeeService;
    private final ChangeEmployeePasswordService passwordService;

    @GetMapping("/{id}")
    public ResponseEntity<EmployeeDto> getEmployee(@PathVariable Long id) {
        return ResponseEntity.ok(employeeService.getEmployee(id));
    }

    @GetMapping("/{id}/transactional")
    public ResponseEntity<EmployeeDto> getEmployeeTransactional(@PathVariable Long id) {
        return ResponseEntity.ok(employeeService.getEmployeeTransactional(id));
    }

    @GetMapping("/{id}/entity-graph")
    public ResponseEntity<EmployeeDto> getEmployeeWithEntityGraph(@PathVariable Long id) {
        return ResponseEntity.ok(employeeService.getEmployeeWithEntityGraph(id));
    }

    @PutMapping("/{id}/password-buggy")
    public ResponseEntity<EmployeeDto> changePasswordBuggy(
            @PathVariable Long id, @RequestBody String newPassword) {
        return ResponseEntity.ok(passwordService.changePasswordBuggy(id, newPassword));
    }

    @PutMapping("/{id}/password-correct")
    public ResponseEntity<EmployeeDto> changePasswordCorrect(
            @PathVariable Long id, @RequestBody String newPassword) {
        return ResponseEntity.ok(passwordService.changePasswordCorrect(id, newPassword));
    }
}
