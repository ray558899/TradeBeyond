package com.tradebeyond.api.controller;

import com.tradebeyond.api.dto.ProductResponse;
import com.tradebeyond.api.service.ProductService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping("/api/product/{productId}")
    public ProductResponse getProduct(@PathVariable Long productId) {
        return ProductResponse.from(productService.getById(productId));
    }
}
