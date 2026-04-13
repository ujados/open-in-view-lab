package com.example.osivlab.service;

import com.example.osivlab.dto.StoreDto;
import com.example.osivlab.dto.StoreProjection;
import com.example.osivlab.mapper.StoreMapper;
import com.example.osivlab.repository.StoreRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class StoreService {

    private final StoreRepository storeRepository;
    private final StoreMapper storeMapper;
    private final EntityManager entityManager;

    /**
     * No @Transactional — triggers N+1 via OSIV lazy loading.
     */
    public List<StoreDto> getAllStores() {
        return storeRepository.findAll().stream()
                .map(storeMapper::toDto)
                .toList();
    }

    /**
     * @Transactional(readOnly=true) — still N+1 but within a single transaction.
     */
    @Transactional(readOnly = true)
    public List<StoreDto> getAllStoresTransactional() {
        return storeRepository.findAll().stream()
                .map(storeMapper::toDto)
                .toList();
    }

    /**
     * @EntityGraph — single JOIN query, no N+1.
     */
    public List<StoreDto> getAllStoresWithEntityGraph() {
        return storeRepository.findAllWithAllRelationsBy().stream()
                .map(storeMapper::toDto)
                .toList();
    }

    /**
     * DTO Projection — optimal SQL, no entity loaded.
     */
    @Transactional(readOnly = true)
    public List<StoreProjection> getAllStoresProjection() {
        return entityManager.createQuery(
                        "SELECT new com.example.osivlab.dto.StoreProjection(" +
                                "s.id, s.name, s.address, st.name, r.name, tz.zoneId) " +
                                "FROM Store s " +
                                "LEFT JOIN s.storeType st " +
                                "LEFT JOIN s.region r " +
                                "LEFT JOIN s.timezone tz",
                        StoreProjection.class)
                .getResultList();
    }
}
