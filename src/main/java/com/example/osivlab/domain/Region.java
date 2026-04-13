package com.example.osivlab.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "regions")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Region {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String code;
}
