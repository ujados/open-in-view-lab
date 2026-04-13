package com.example.osivlab.dto;

import java.util.List;

/**
 * DTO Projection for Department — assembled from multiple flat queries.
 * No entities loaded, no persistence context, no lazy risk.
 */
public record DepartmentProjection(
        Long id,
        String name,
        String code,
        String regionName,
        List<String> employeeNames,
        List<String> projectNames,
        List<String> budgetNames,
        List<String> equipmentNames,
        List<String> policyTitles,
        List<String> documentTitles
) {
}
