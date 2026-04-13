package com.example.osivlab.domain;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Entity with 3 ManyToOne (N+1 demo).
 * Mapped from: Zone (original codebase).
 */
@Entity
@Table(name = "stores")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Store {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String address;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_type_id")
    private StoreType storeType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_id")
    private Region region;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "timezone_id")
    private Timezone timezone;

    @OneToMany(mappedBy = "store", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<StoreEmployee> storeEmployees = new ArrayList<>();
}
