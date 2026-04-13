package com.example.osivlab.mapper;

import com.example.osivlab.domain.Employee;
import com.example.osivlab.dto.EmployeeDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface EmployeeMapper {

    EmployeeDto toDto(Employee employee);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "roles", ignore = true)
    @Mapping(target = "department", ignore = true)
    @Mapping(target = "password", ignore = true)
    Employee toEntity(EmployeeDto dto);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "roles", ignore = true)
    @Mapping(target = "department", ignore = true)
    @Mapping(target = "password", ignore = true)
    void updateEntity(EmployeeDto dto, @MappingTarget Employee employee);
}
