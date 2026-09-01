package com.tradebeyond.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tradebeyond.api.dto.OrderUpdateRequest;
import com.tradebeyond.api.entity.Order;
import com.tradebeyond.api.entity.Product;
import com.tradebeyond.api.entity.ProductCategory;
import com.tradebeyond.api.entity.Users;
import com.tradebeyond.api.exception.OrderNotFoundException;
import com.tradebeyond.api.repository.OrderRepository;
import com.tradebeyond.api.repository.ProductCategoryRepository;
import com.tradebeyond.api.repository.ProductRepository;
import com.tradebeyond.api.repository.UsersRepository;
import com.tradebeyond.api.testsupport.SecurityContextTestSupport;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 「排除已軟刪除的 Order」是 Order Entity 上 @SQLRestriction("delete_at IS NULL") 這條宣告式規則
 * 實際查詢時的效果，用 Mockito mock repository 驗證不到，所以這裡用 Testcontainers 打真的 PostgreSQL。
 * 同理，OrderRepository.findByOrderIdAndUserUserId 這個衍生查詢方法（Part 2.4 IDOR 歸屬檢查）
 * 是否真的正確對應到 SQL、真的能排除「訂單存在但不是這個 userId」的情況，Mockito mock 只能證明
 * Service 有沒有呼叫這個方法、傳了什麼參數，證明不了查詢本身對真實資料是否正確，一樣要打真的 DB。
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

    @AfterEach
    void tearDown() {
        SecurityContextTestSupport.clear();
    }

    private Users createTestUser() {
        Users user = new Users();
        user.setUsername("test-user");
        user.setAccount("test-account-" + System.nanoTime());
        user.setPassword(FAKE_BCRYPT_HASH);
        return usersRepository.save(user);
    }

    private Product createTestProduct() {
        ProductCategory category = new ProductCategory();
        category.setCategoryName("test-category");
        category.setTaxRate(new BigDecimal("0.0500"));
        category = productCategoryRepository.save(category);

        Product product = new Product();
        product.setProductCategory(category);
        product.setUnitPrice(new BigDecimal("100.0000"));
        return productRepository.save(product);
    }

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

        // Part 2.4 IDOR：findOrdersByUserId 現在會比對目前登入者，這裡查自己的訂單，先模擬登入身分
        SecurityContextTestSupport.authenticateAs(user.getUserId());
        List<Order> result = orderService.findOrdersByUserId(user.getUserId());

        assertThat(result)
                .extracting(Order::getOrderId)
                .containsExactly(keep.getOrderId());
    }

    @Test
    void patchOrderAmount_succeeds_whenCallerOwnsTheOrder() {
        // 驗證 OrderRepository.findByOrderIdAndUserUserId 這個衍生查詢方法對真實資料
        // 真的能正確找到「屬於這個 userId」的訂單，不是只是 Mockito mock 出來的假象
        Users owner = createTestUser();
        Product product = createTestProduct();

        Order order = new Order();
        order.setUser(owner);
        order.setProduct(product);
        order.setOrderAmount(new BigDecimal("2"));
        order.setUnitPriceSnapshot(new BigDecimal("100.0000"));
        order.setTaxRateSnapshot(new BigDecimal("0.0500"));
        order.setTotalCost(new BigDecimal("210.0000"));
        order = orderRepository.save(order);

        SecurityContextTestSupport.authenticateAs(owner.getUserId());
        Order result = orderService.patchOrderAmount(order.getOrderId(), new OrderUpdateRequest(new BigDecimal("5")));

        assertThat(result.getOrderAmount()).isEqualByComparingTo("5");
        assertThat(result.getTotalCost()).isEqualByComparingTo("525.0000");
    }

    @Test
    void patchOrderAmount_throwsOrderNotFoundException_whenOrderBelongsToAnotherUser() {
        // Part 2.4 IDOR：訂單真實存在於 DB，但屬於別人——對真實資料驗證這個情況一樣回 404，
        // 不是 403，且不用先查出來再判斷，衍生查詢方法本身就該直接查不到
        Users owner = createTestUser();
        Users otherUser = createTestUser();
        Product product = createTestProduct();

        Order order = new Order();
        order.setUser(owner);
        order.setProduct(product);
        order.setOrderAmount(new BigDecimal("2"));
        order.setUnitPriceSnapshot(new BigDecimal("100.0000"));
        order.setTaxRateSnapshot(new BigDecimal("0.0500"));
        order.setTotalCost(new BigDecimal("210.0000"));
        order = orderRepository.save(order);
        Long orderId = order.getOrderId();

        SecurityContextTestSupport.authenticateAs(otherUser.getUserId());

        assertThatThrownBy(() -> orderService.patchOrderAmount(orderId, new OrderUpdateRequest(new BigDecimal("5"))))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void concurrentPatch_throwsObjectOptimisticLockingFailureException_whenVersionIsStale() {
        // 用 mock repository 測不出樂觀鎖是否真的生效，必須打真的 DB 才能驗證
        // "第二個帶著過期 version 的寫入" 會不會被 Hibernate 擋下來。
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
        Long orderId = order.getOrderId();

        // 模擬兩個併發 PATCH 請求，各自先讀到同一個 version 的快照
        Order snapshotA = orderRepository.findById(orderId).orElseThrow();
        Order snapshotB = orderRepository.findById(orderId).orElseThrow();

        // 請求 A 先送出並成功提交 -> DB 內的 version 往前推進一格
        snapshotA.setOrderAmount(new BigDecimal("3"));
        orderRepository.saveAndFlush(snapshotA);

        // 請求 B 帶著已經過期的 version 再送出 -> 必須被樂觀鎖擋下來，而不是靜默覆蓋掉 A 剛寫入的結果
        snapshotB.setOrderAmount(new BigDecimal("5"));
        assertThatThrownBy(() -> orderRepository.saveAndFlush(snapshotB))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }
}
