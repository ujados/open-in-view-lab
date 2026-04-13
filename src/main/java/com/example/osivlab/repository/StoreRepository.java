package com.example.osivlab.repository;

import com.example.osivlab.domain.Store;
import com.example.osivlab.dto.StoreView;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface StoreRepository extends JpaRepository<Store, Long> {

    @EntityGraph(attributePaths = {"storeType", "region", "timezone"})
    Optional<Store> findWithAllRelationsById(Long id);

    @EntityGraph(attributePaths = {"storeType", "region", "timezone"})
    List<Store> findAllWithAllRelationsBy();

    /**
     * Interface Projection — Spring Data generates the implementation.
     * Only fetches the columns declared in StoreView.
     */
    @Query("SELECT s.id AS id, s.name AS name, s.address AS address, " +
           "st.name AS storeTypeName, r.name AS regionName, tz.zoneId AS timezoneZoneId " +
           "FROM Store s LEFT JOIN s.storeType st LEFT JOIN s.region r LEFT JOIN s.timezone tz")
    List<StoreView> findAllProjectedBy();

    /**
     * @EntityGraph with the storeEmployees collection — happy path (single collection works).
     */
    @EntityGraph(attributePaths = {"storeType", "region", "timezone", "storeEmployees"})
    List<Store> findAllWithRelationsAndEmployeesBy();
}
