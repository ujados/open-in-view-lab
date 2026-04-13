package com.example.osivlab.dto;

import lombok.*;

import java.util.Set;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class EmployeeDto {
    private Long id;
    private String name;
    private String email;
    private String password;
    private boolean active;
    private Set<RoleDto> roles;
}
