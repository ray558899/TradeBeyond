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
import com.tradebeyond.api.exception.OrderNotFoundException;
import com.tradebeyond.api.exception.ProductNotFoundException;
import com.tradebeyond.api.exception.UserNotFoundException;
import com.tradebeyond.api.repository.OrderRepository;
import java.math.BigDecimal;
import java.util.Optional;
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
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.patchOrderAmount(99L, new OrderUpdateRequest(BigDecimal.TEN)))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void patchOrderAmount_recalculatesTotalCost_fromSnapshotValues_withoutRequeryingProduct() {
        // PATCH 只能改 orderAmount，totalCost 要用下單當下鎖住的 unitPriceSnapshot/taxRateSnapshot 重算
        // （5 * 100 * 1.05 = 525.0000），且完全不能重新查 Product/ProductCategory，
        // 否則歷史訂單金額會因為之後調價/調稅率而跑掉
        Order existing = new Order();
        existing.setOrderAmount(new BigDecimal("2"));
        existing.setUnitPriceSnapshot(new BigDecimal("100.0000"));
        existing.setTaxRateSnapshot(new BigDecimal("0.0500"));
        existing.setTotalCost(new BigDecimal("210.0000"));
        when(orderRepository.findById(1L)).thenReturn(Optional.of(existing));

        Order result = orderService.patchOrderAmount(1L, new OrderUpdateRequest(new BigDecimal("5")));

        assertThat(result.getOrderAmount()).isEqualByComparingTo("5");
        assertThat(result.getTotalCost()).isEqualByComparingTo("525.0000");
        verifyNoInteractions(productService);
    }

    @Test
    void deleteOrder_throwsOrderNotFoundException_whenOrderDoesNotExist() {
        // 訂單不存在（含已軟刪除）時 DELETE 必須回 404，不能靜默成功
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.deleteOrder(99L))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void deleteOrder_delegatesToRepositoryDelete_whenOrderExists() {
        // Service 只需呼叫 repository.delete(entity)；Entity 上的 @SQLDelete 會把它轉成
        // UPDATE ... SET delete_at = now()，Service 不用也不應該手動組 UPDATE 或設定 delete_at
        Order existing = new Order();
        when(orderRepository.findById(1L)).thenReturn(Optional.of(existing));

        orderService.deleteOrder(1L);

        verify(orderRepository).delete(existing);
    }
}
