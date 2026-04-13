package com.example.osivlab.dto;

/**
 * DTO Projection for Store (constructor expression in JPQL).
 */
public record StoreProjection(
        Long id,
        String name,
        String address,
        String storeTypeName,
        String regionName,
        String timezoneZoneId
) {
}
