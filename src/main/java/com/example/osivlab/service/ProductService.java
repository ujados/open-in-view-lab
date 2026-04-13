package com.example.osivlab.service;

import com.example.osivlab.dto.ProductDto;
import com.example.osivlab.mapper.ProductMapper;
import com.example.osivlab.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductMapper productMapper;

    public ProductDto getProduct(Long id) {
        return productRepository.findById(id)
                .map(productMapper::toDto)
                .orElseThrow(() -> new RuntimeException("Product not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<ProductDto> getAllProductsTransactional() {
        return productRepository.findAll().stream()
                .map(productMapper::toDto)
                .toList();
    }

    public List<ProductDto> getAllProductsWithEntityGraph() {
        return productRepository.findAllWithCategoryBy().stream()
                .map(productMapper::toDto)
                .toList();
    }
}
