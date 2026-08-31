package com.tradebeyond.api.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tradebeyond.api.entity.Order;
import com.tradebeyond.api.entity.Product;
import com.tradebeyond.api.entity.ProductCategory;
import com.tradebeyond.api.entity.Users;
import com.tradebeyond.api.repository.OrderRepository;
import com.tradebeyond.api.repository.ProductCategoryRepository;
import com.tradebeyond.api.repository.ProductRepository;
import com.tradebeyond.api.repository.UsersRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 「排除已軟刪除的 Order」是 Order Entity 上 @SQLRestriction("delete_at IS NULL") 這條宣告式規則
 * 實際查詢時的效果，用 Mockito mock repository 驗證不到，所以這裡用 Testcontainers 打真的 PostgreSQL。
 */
@SpringBootTest(properties = "JWT_SECRET=test-jwt-secret-for-integration-tests")
@Testcontainers
class OrderServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.3");

    private static final String FAKE_BCRYPT_HASH = "$2a$12$abcdefghijklmnopqrstuvABCDEFGHIJKLMNOPQRSTUVWXYZabcde";

    @Autowired
    private UsersRepository usersRepository;

    @Autowired
    private ProductCategoryRepository productCategoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderService orderService;

    @Test
    void findOrdersByUserId_excludesSoftDeletedOrders() {
        Users user = new Users();
        user.setUsername("test-user");
        user.setAccount("test-account-" + System.nanoTime());
        user.setPassword(FAKE_BCRYPT_HASH);
        user = usersRepository.save(user);

        ProductCategory category = new ProductCategory();
        category.setCategoryName("test-category");
        category.setTaxRate(new BigDecimal("0.0500"));
        category = productCategoryRepository.save(category);

        Product product = new Product();
        product.setProductCategory(category);
        product.setUnitPrice(new BigDecimal("100.0000"));
        product = productRepository.save(product);

        Order keep = new Order();
        keep.setUser(user);
        keep.setProduct(product);
        keep.setOrderAmount(new BigDecimal("1"));
        keep.setUnitPriceSnapshot(new BigDecimal("100.0000"));
        keep.setTaxRateSnapshot(new BigDecimal("0.0500"));
        keep.setTotalCost(new BigDecimal("105.0000"));
        keep = orderRepository.save(keep);

        Order deleted = new Order();
        deleted.setUser(user);
        deleted.setProduct(product);
        deleted.setOrderAmount(new BigDecimal("2"));
        deleted.setUnitPriceSnapshot(new BigDecimal("100.0000"));
        deleted.setTaxRateSnapshot(new BigDecimal("0.0500"));
        deleted.setTotalCost(new BigDecimal("210.0000"));
        deleted = orderRepository.save(deleted);
        orderRepository.delete(deleted); // 觸發 @SQLDelete -> 軟刪除

        List<Order> result = orderService.findOrdersByUserId(user.getUserId());

        assertThat(result)
                .extracting(Order::getOrderId)
                .containsExactly(keep.getOrderId());
    }
}
