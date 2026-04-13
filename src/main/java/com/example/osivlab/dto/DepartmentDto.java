package com.example.osivlab.dto;

import lombok.*;

import java.util.List;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class DepartmentDto {
    private Long id;
    private String name;
    private String code;
    private String regionName;
    private List<String> employeeNames;
    private List<String> projectNames;
    private List<String> budgetNames;
    private List<String> equipmentNames;
    private List<String> policyTitles;
    private List<String> documentTitles;
}
