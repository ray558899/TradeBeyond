package com.tradebeyond.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tradebeyond.api.dto.OrderCreateRequest;
import com.tradebeyond.api.dto.OrderUpdateRequest;
import com.tradebeyond.api.entity.Order;
import com.tradebeyond.api.entity.Product;
import com.tradebeyond.api.entity.ProductCategory;
import com.tradebeyond.api.entity.Users;
import com.tradebeyond.api.exception.ForbiddenAccessException;
import com.tradebeyond.api.exception.OrderNotFoundException;
import com.tradebeyond.api.exception.ProductNotFoundException;
import com.tradebeyond.api.exception.UserNotFoundException;
import com.tradebeyond.api.repository.OrderRepository;
import com.tradebeyond.api.testsupport.SecurityContextTestSupport;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ProductService productService;

    @Mock
    private UserService userService;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, productService, userService);
    }

    @AfterEach
    void tearDown() {
        // 每個測試各自模擬不同的「目前登入者」，測完清掉避免汙染下一個測試方法
        SecurityContextTestSupport.clear();
    }

    private Product productWith(BigDecimal unitPrice, BigDecimal taxRate) {
        ProductCategory category = new ProductCategory();
        category.setTaxRate(taxRate);
        Product product = new Product();
        product.setProductCategory(category);
        product.setUnitPrice(unitPrice);
        return product;
    }

    @Test
    void createOrder_throwsUserNotFoundException_whenUserDoesNotExist() {
        // userId 不存在時，建單必須直接失敗（4xx），不能建出一筆沒有合法 user 關聯的 Order
        when(userService.getById(1L)).thenThrow(new UserNotFoundException(1L));

        assertThatThrownBy(() -> orderService.createOrder(new OrderCreateRequest(1L, 2L, BigDecimal.ONE)))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void createOrder_throwsProductNotFoundException_whenProductDoesNotExist() {
        // productId 不存在時，建單必須直接失敗（4xx），不能建出一筆沒有合法 product 關聯的 Order
        when(userService.getById(1L)).thenReturn(new Users());
        when(productService.getById(2L)).thenThrow(new ProductNotFoundException(2L));

        assertThatThrownBy(() -> orderService.createOrder(new OrderCreateRequest(1L, 2L, BigDecimal.ONE)))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void createOrder_calculatesTotalCost_forNormalOrderAmount() {
        // 訂購 2 個、單價 100、稅率 5% → totalCost = 2 * 100 * 1.05 = 210.0000，
        // 且 unitPriceSnapshot / taxRateSnapshot 必須鎖住下單當下的值
        Product product = productWith(new BigDecimal("100.0000"), new BigDecimal("0.0500"));
        when(userService.getById(1L)).thenReturn(new Users());
        when(productService.getById(2L)).thenReturn(product);
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Order result = orderService.createOrder(new OrderCreateRequest(1L, 2L, new BigDecimal("2")));

        assertThat(result.getTotalCost()).isEqualByComparingTo("210.0000");
        assertThat(result.getUnitPriceSnapshot()).isEqualByComparingTo("100.0000");
        assertThat(result.getTaxRateSnapshot()).isEqualByComparingTo("0.0500");
    }

    @Test
    void createOrder_totalCostIsZero_whenOrderAmountIsZero() {
        // orderAmount = 0 時，即使單價/稅率都不是 0，totalCost 也必須精確為 0
        Product product = productWith(new BigDecimal("100.0000"), new BigDecimal("0.0500"));
        when(userService.getById(1L)).thenReturn(new Users());
        when(productService.getById(2L)).thenReturn(product);
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Order result = orderService.createOrder(new OrderCreateRequest(1L, 2L, BigDecimal.ZERO));

        assertThat(result.getTotalCost()).isEqualByComparingTo("0.0000");
    }

    @Test
    void createOrder_totalCostIsNegative_whenOrderAmountIsNegative() {
        // 這裡只驗證純計算邏輯本身對負數輸入的正確性（正負號要對）；
        // 擋掉「不該接受負數訂購量」是 DTO 層 @Positive 的責任，不是這個計算方法的責任
        Product product = productWith(new BigDecimal("100.0000"), new BigDecimal("0.0500"));
        when(userService.getById(1L)).thenReturn(new Users());
        when(productService.getById(2L)).thenReturn(product);
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Order result = orderService.createOrder(new OrderCreateRequest(1L, 2L, new BigDecimal("-1")));

        assertThat(result.getTotalCost()).isEqualByComparingTo("-105.0000");
    }

    @Test
    void createOrder_totalCostEqualsAmountTimesPrice_whenTaxRateIsZero() {
        // 稅率 0% 時，totalCost 應該就是 orderAmount * unitPrice，不含任何額外稅金
        Product product = productWith(new BigDecimal("50.0000"), new BigDecimal("0.0000"));
        when(userService.getById(1L)).thenReturn(new Users());
        when(productService.getById(2L)).thenReturn(product);
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Order result = orderService.createOrder(new OrderCreateRequest(1L, 2L, new BigDecimal("3")));

        assertThat(result.getTotalCost()).isEqualByComparingTo("150.0000");
    }

    @Test
    void patchOrderAmount_throwsOrderNotFoundException_whenOrderDoesNotExist() {
        // 訂單不存在（含已軟刪除）時 PATCH 必須回 404，不能靜默成功或建出新資料
        SecurityContextTestSupport.authenticateAs(1L);
        when(orderRepository.findByOrderIdAndUserUserId(99L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.patchOrderAmount(99L, new OrderUpdateRequest(BigDecimal.TEN)))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void patchOrderAmount_throwsOrderNotFoundException_whenOrderBelongsToAnotherUser() {
        // Part 2.4 IDOR：訂單存在，但不是目前登入者的——回應要跟「根本不存在」一模一樣（404，
        // 不是 403），不能讓呼叫方從回應差異反推出這個 orderId 是否真實存在。查詢條件本身
        // 就帶入目前登入者 userId（1L），別人的訂單一律查不到，不用查出來後才額外判斷。
        SecurityContextTestSupport.authenticateAs(1L);
        when(orderRepository.findByOrderIdAndUserUserId(1L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.patchOrderAmount(1L, new OrderUpdateRequest(BigDecimal.TEN)))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void patchOrderAmount_recalculatesTotalCost_fromSnapshotValues_withoutRequeryingProduct() {
        // PATCH 只能改 orderAmount，totalCost 要用下單當下鎖住的 unitPriceSnapshot/taxRateSnapshot 重算
        // （5 * 100 * 1.05 = 525.0000），且完全不能重新查 Product/ProductCategory，
        // 否則歷史訂單金額會因為之後調價/調稅率而跑掉
        SecurityContextTestSupport.authenticateAs(1L);
        Order existing = new Order();
        existing.setOrderAmount(new BigDecimal("2"));
        existing.setUnitPriceSnapshot(new BigDecimal("100.0000"));
        existing.setTaxRateSnapshot(new BigDecimal("0.0500"));
        existing.setTotalCost(new BigDecimal("210.0000"));
        when(orderRepository.findByOrderIdAndUserUserId(1L, 1L)).thenReturn(Optional.of(existing));

        Order result = orderService.patchOrderAmount(1L, new OrderUpdateRequest(new BigDecimal("5")));

        assertThat(result.getOrderAmount()).isEqualByComparingTo("5");
        assertThat(result.getTotalCost()).isEqualByComparingTo("525.0000");
        verifyNoInteractions(productService);
    }

    @Test
    void deleteOrder_throwsOrderNotFoundException_whenOrderDoesNotExist() {
        // 訂單不存在（含已軟刪除）時 DELETE 必須回 404，不能靜默成功
        SecurityContextTestSupport.authenticateAs(1L);
        when(orderRepository.findByOrderIdAndUserUserId(99L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.deleteOrder(99L))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void deleteOrder_throwsOrderNotFoundException_whenOrderBelongsToAnotherUser() {
        // Part 2.4 IDOR：跟 PATCH 同一個道理，B 想刪 A 的訂單一律回 404，不是 403
        SecurityContextTestSupport.authenticateAs(2L);
        when(orderRepository.findByOrderIdAndUserUserId(1L, 2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.deleteOrder(1L))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void deleteOrder_delegatesToRepositoryDelete_whenOrderExists() {
        // Service 只需呼叫 repository.delete(entity)；Entity 上的 @SQLDelete 會把它轉成
        // UPDATE ... SET delete_at = now()，Service 不用也不應該手動組 UPDATE 或設定 delete_at
        SecurityContextTestSupport.authenticateAs(1L);
        Order existing = new Order();
        when(orderRepository.findByOrderIdAndUserUserId(1L, 1L)).thenReturn(Optional.of(existing));

        orderService.deleteOrder(1L);

        verify(orderRepository).delete(existing);
    }

    @Test
    void findOrdersByUserId_returnsOrders_whenCallerRequestsOwnUserId() {
        SecurityContextTestSupport.authenticateAs(1L);
        when(userService.getById(1L)).thenReturn(new Users());
        Order order = new Order();
        when(orderRepository.findByUserUserId(1L)).thenReturn(List.of(order));

        var result = orderService.findOrdersByUserId(1L);

        assertThat(result).containsExactly(order);
    }

    @Test
    void findOrdersByUserId_throwsForbiddenAccessException_whenCallerRequestsAnotherUsersOrders() {
        // Part 2.4 IDOR：跟 PATCH/DELETE 不同，這裡刻意回 403 而不是 404——列出「別人的訂單」
        // 這個動作本身就該被明確拒絕，不是「找不到」的語意。比對在查 DB 之前就先做，
        // 不該為了一個註定會被拒絕的請求還多打一次不會用到結果的查詢。
        SecurityContextTestSupport.authenticateAs(1L);

        assertThatThrownBy(() -> orderService.findOrdersByUserId(2L))
                .isInstanceOf(ForbiddenAccessException.class);
        verifyNoInteractions(userService);
        verifyNoInteractions(orderRepository);
    }

    @Test
    void findOrdersByUserId_throwsUserNotFoundException_whenUserDoesNotExist() {
        // 查詢不存在的 userId 必須回 404（USER_NOT_FOUND），不能默默回傳空陣列，
        // 且要在查 Order 之前就先失敗，不必浪費一次不會用到結果的 Order 查詢。
        // 這裡查詢自己的 userId（歸屬檢查通過），才會走到「這個 userId 到底存不存在」這一步
        // ——理論上不太會發生（自己的 token 對應的帳號通常存在），但涵蓋帳號在 token 簽發後
        // 被刪除的邊界情況。
        SecurityContextTestSupport.authenticateAs(99L);
        when(userService.getById(99L)).thenThrow(new UserNotFoundException(99L));

        assertThatThrownBy(() -> orderService.findOrdersByUserId(99L))
                .isInstanceOf(UserNotFoundException.class);
        verifyNoInteractions(orderRepository);
    }
}
