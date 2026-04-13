package com.example.osivlab.dto;

import lombok.*;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class PermissionDto {
    private Long id;
    private String name;
}
