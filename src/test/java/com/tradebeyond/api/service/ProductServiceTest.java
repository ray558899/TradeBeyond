package com.tradebeyond.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.tradebeyond.api.entity.Product;
import com.tradebeyond.api.exception.ProductNotFoundException;
import com.tradebeyond.api.repository.ProductRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    private ProductService productService;

    @BeforeEach
    void setUp() {
        productService = new ProductService(productRepository);
    }

    @Test
    void getById_returnsProduct_whenProductExists() {
        // 商品存在時，getById 應直接回傳該 Product，供 OrderService 後續讀取 unitPrice/taxRate
        Product product = new Product();
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        Product result = productService.getById(1L);

        assertThat(result).isSameAs(product);
    }

    @Test
    void getById_throwsProductNotFoundException_whenProductDoesNotExist() {
        // 商品不存在（含已軟刪除，@SQLRestriction 會讓 findById 直接查不到）時，
        // 必須丟出 ProductNotFoundException，讓上層轉成 404，而不是回傳 null 或拋出未預期的例外
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.getById(99L))
                .isInstanceOf(ProductNotFoundException.class);
    }
}
