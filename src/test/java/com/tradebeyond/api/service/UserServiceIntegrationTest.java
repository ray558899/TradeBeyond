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
import java.sql.Timestamp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * @SQLDelete / @SQLRestriction 是否真的把 repository.delete(...) 轉成軟刪除 UPDATE，
 * 是實際 DB 行為，Mockito mock repository 驗證不到，所以這裡用 Testcontainers 打真的 PostgreSQL
 * （依 CLAUDE.md Part 9.3，僅供測試時使用，不代表正式環境用 Docker 部署）。
 */
@SpringBootTest(properties = "JWT_SECRET=test-jwt-secret-for-integration-tests")
@Testcontainers
class UserServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.3");

    // 純測試 fixture 用的假 BCrypt hash 格式字串，不對應任何真實明碼，避免在測試裡出現明碼密碼字面值
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
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void deleteUser_softDeletesUserAndAllTheirOrders_withoutRemovingRows() {
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

        Order order = new Order();
        order.setUser(user);
        order.setProduct(product);
        order.setOrderAmount(new BigDecimal("1"));
        order.setUnitPriceSnapshot(new BigDecimal("100.0000"));
        order.setTaxRateSnapshot(new BigDecimal("0.0500"));
        order.setTotalCost(new BigDecimal("105.0000"));
        order = orderRepository.save(order);

        Long userId = user.getUserId();
        Long orderId = order.getOrderId();

        userService.deleteUser(userId);

        // 繞過 @SQLRestriction（不透過 Entity 查，直接查 raw 欄位），確認資料「還在」且 delete_at 已被設定，
        // 證明 repository.delete(...) 被 @SQLDelete 轉成了 UPDATE，而不是真的 DELETE FROM
        Timestamp userDeleteAt = jdbcTemplate.queryForObject(
                "SELECT delete_at FROM users WHERE user_id = ?", Timestamp.class, userId);
        Timestamp orderDeleteAt = jdbcTemplate.queryForObject(
                "SELECT delete_at FROM orders WHERE order_id = ?", Timestamp.class, orderId);

        assertThat(userDeleteAt).isNotNull();
        assertThat(orderDeleteAt).isNotNull();

        Integer userRowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE user_id = ?", Integer.class, userId);
        Integer orderRowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE order_id = ?", Integer.class, orderId);

        assertThat(userRowCount).isEqualTo(1);
        assertThat(orderRowCount).isEqualTo(1);
    }
}
