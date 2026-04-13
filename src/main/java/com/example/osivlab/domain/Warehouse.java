package com.example.osivlab.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * Part of deep chain: warehouse → department → budgets.
 * Mapped from: route (original codebase).
 */
@Entity
@Table(name = "warehouses")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Warehouse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String address;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;
}
