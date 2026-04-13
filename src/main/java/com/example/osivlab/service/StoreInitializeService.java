package com.example.osivlab.service;

import com.example.osivlab.domain.Store;
import com.example.osivlab.dto.StoreDto;
import com.example.osivlab.mapper.StoreMapper;
import com.example.osivlab.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Uses Hibernate.initialize() for explicit lazy loading control.
 * No @EntityGraph, no JOIN FETCH — just manual trigger.
 */
@Service
@RequiredArgsConstructor
public class StoreInitializeService {

    private final StoreRepository storeRepository;
    private final StoreMapper storeMapper;

    /**
     * Loads stores then explicitly initializes each lazy relation.
     * More verbose than @EntityGraph but gives full control.
     */
    @Transactional(readOnly = true)
    public List<StoreDto> getAllStoresWithInitialize() {
        List<Store> stores = storeRepository.findAll();
        stores.forEach(store -> {
            Hibernate.initialize(store.getStoreType());
            Hibernate.initialize(store.getRegion());
            Hibernate.initialize(store.getTimezone());
        });
        return stores.stream().map(storeMapper::toDto).toList();
    }
}
