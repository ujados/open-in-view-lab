package com.example.osivlab.domain;

import jakarta.persistence.*;
import lombok.*;

/**
 * Mapped from: Supplier (original codebase).
 */
@Entity
@Table(name = "vendors")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Vendor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String contactEmail;

    private String country;
}
