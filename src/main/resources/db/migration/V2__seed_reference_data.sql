-- 灌入基礎參考資料（ProductCategory / Product），讓本機與測試環境有可用的商品資料，
-- 完整的訂單流程（建立 → 查詢 → PATCH → DELETE）才能真的被走過一次，
-- 不會每次都因為 productId 查不到而卡在第一步。
--
-- 這是商品目錄這種參考資料，不是帳密/token 這類機密憑證，可以安心進 Git——
-- 跟「不預埋測試帳號」的決定並不衝突，帳號一律透過 POST /api/auth/register 自行建立。

INSERT INTO product_category (category_name, tax_rate) VALUES
    ('Electronics', 0.0500);

-- unit_price 給幾個不同的合理數值，方便手動測試時觀察 totalCost = order_amount * unit_price * (1 + tax_rate) 是否正確
INSERT INTO product (product_category_id, unit_price)
SELECT category_id, unit_price
FROM product_category
CROSS JOIN (VALUES (100.0000), (299.9900), (1500.0000)) AS seed_prices (unit_price)
WHERE category_name = 'Electronics';
