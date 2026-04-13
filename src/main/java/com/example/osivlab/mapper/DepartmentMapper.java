package com.example.osivlab.mapper;

import com.example.osivlab.domain.*;
import com.example.osivlab.dto.DepartmentDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.List;

@Mapper(componentModel = "spring")
public interface DepartmentMapper {

    @Mapping(source = "region.name", target = "regionName")
    @Mapping(source = "employees", target = "employeeNames", qualifiedByName = "employeeNames")
    @Mapping(source = "projects", target = "projectNames", qualifiedByName = "projectNames")
    @Mapping(source = "budgets", target = "budgetNames", qualifiedByName = "budgetNames")
    @Mapping(source = "equipment", target = "equipmentNames", qualifiedByName = "equipmentNames")
    @Mapping(source = "policies", target = "policyTitles", qualifiedByName = "policyTitles")
    @Mapping(source = "documents", target = "documentTitles", qualifiedByName = "documentTitles")
    DepartmentDto toDto(Department department);

    @Named("employeeNames")
    default List<String> employeeNames(List<Employee> employees) {
        return employees.stream().map(Employee::getName).toList();
    }

    @Named("projectNames")
    default List<String> projectNames(List<Project> projects) {
        return projects.stream().map(Project::getName).toList();
    }

    @Named("budgetNames")
    default List<String> budgetNames(List<Budget> budgets) {
        return budgets.stream().map(Budget::getName).toList();
    }

    @Named("equipmentNames")
    default List<String> equipmentNames(List<Equipment> equipment) {
        return equipment.stream().map(Equipment::getName).toList();
    }

    @Named("policyTitles")
    default List<String> policyTitles(List<Policy> policies) {
        return policies.stream().map(Policy::getTitle).toList();
    }

    @Named("documentTitles")
    default List<String> documentTitles(List<Document> documents) {
        return documents.stream().map(Document::getTitle).toList();
    }
}
