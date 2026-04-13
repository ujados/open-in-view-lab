package com.example.osivlab.dto;

import lombok.*;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class StoreDto {
    private Long id;
    private String name;
    private String address;
    private String storeTypeName;
    private String regionName;
    private String timezoneZoneId;
}
