package com.example.osivlab.mapper;

import com.example.osivlab.domain.Store;
import com.example.osivlab.dto.StoreDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface StoreMapper {

    @Mapping(source = "storeType.name", target = "storeTypeName")
    @Mapping(source = "region.name", target = "regionName")
    @Mapping(source = "timezone.zoneId", target = "timezoneZoneId")
    StoreDto toDto(Store store);
}
