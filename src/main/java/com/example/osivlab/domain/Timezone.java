package com.example.osivlab.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "timezones")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Timezone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String zoneId;

    private String displayName;
}
