package com.example.osivlab.dto;

/**
 * Spring Data Interface Projection for Store.
 * Less boilerplate than constructor DTO — just declare getters.
 * Spring Data generates the implementation at runtime.
 */
public interface StoreView {
    Long getId();
    String getName();
    String getAddress();
    String getStoreTypeName();
    String getRegionName();
    String getTimezoneZoneId();
}
