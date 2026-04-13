package com.example.osivlab.mapper;

import com.example.osivlab.domain.Product;
import com.example.osivlab.dto.ProductDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ProductMapper {

    @Mapping(source = "category.name", target = "categoryName")
    ProductDto toDto(Product product);
}
