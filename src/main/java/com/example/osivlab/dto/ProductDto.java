package com.example.osivlab.dto;

import lombok.*;

import java.math.BigDecimal;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class ProductDto {
    private Long id;
    private String name;
    private String sku;
    private BigDecimal price;
    private String categoryName;
}
