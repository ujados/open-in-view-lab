package com.example.osivlab.dto;

import lombok.*;

import java.util.Set;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class RoleDto {
    private Long id;
    private String name;
    private Set<PermissionDto> permissions;
}
