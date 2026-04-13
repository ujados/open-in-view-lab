package com.example.osivlab.service;

import com.example.osivlab.dto.StoreProjection;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Same queries as StoreService but using JdbcClient (Spring 6.1+) instead of JPA.
 * No Hibernate session, no proxies, no lazy loading, no persistence context.
 * Pure SQL → DTO mapping.
 */
@Service
@RequiredArgsConstructor
public class StoreJdbcService {

    private final JdbcClient jdbcClient;

    /**
     * Equivalent to StoreService.getAllStoresProjection() but via JdbcClient.
     * Zero Hibernate overhead.
     */
    public List<StoreProjection> getAllStoresProjection() {
        return jdbcClient.sql("""
                SELECT s.id, s.name, s.address, st.name AS store_type_name,
                       r.name AS region_name, tz.zone_id AS timezone_zone_id
                FROM stores s
                LEFT JOIN store_types st ON s.store_type_id = st.id
                LEFT JOIN regions r ON s.region_id = r.id
                LEFT JOIN timezones tz ON s.timezone_id = tz.id
                """)
                .query((rs, _) -> new StoreProjection(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("address"),
                        rs.getString("store_type_name"),
                        rs.getString("region_name"),
                        rs.getString("timezone_zone_id")
                ))
                .list();
    }

    /**
     * Minimal projection: only 2 columns. Shows the minimum possible data transfer.
     */
    public List<String[]> getStoreAndRegionNames() {
        return jdbcClient.sql("""
                SELECT s.name, r.name AS region_name
                FROM stores s
                LEFT JOIN regions r ON s.region_id = r.id
                """)
                .query((rs, _) -> new String[]{
                        rs.getString("name"),
                        rs.getString("region_name")
                })
                .list();
    }
}
