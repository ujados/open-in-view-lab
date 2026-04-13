package com.example.osivlab.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "policies")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Policy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    private String content;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;
}
