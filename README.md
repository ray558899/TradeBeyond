# TradeBeyond Backend API

Spring Boot 3.x 後端 API 專案骨架，範疇為 Users / ProductCategory / Product / Order，
資料庫使用 PostgreSQL，部署目標為 GCP Cloud Run（單一 instance）+ Cloud SQL + Secret Manager。

詳細的架構、安全性與工程規範請見 [CLAUDE.md](./CLAUDE.md)。

> **本機啟動前**：`JWT_SECRET` 環境變數沒有預設值（見 `.env.example`），沒設定會在啟動時被
> `PlaceholderResolutionException` 卡住。可以用這行指令產生一組隨機值：
> ```bash
> openssl rand -base64 32
> ```
> 把結果填進 `.env` 的 `JWT_SECRET`（或啟動指令前手動 `export JWT_SECRET=...`）。

## 技術棧

- Java 17 + Spring Boot 3.5.x（Maven）
- Spring Web / Spring Data JPA / Spring Validation / Spring Security
- PostgreSQL + Flyway（schema migration）
- springdoc-openapi（Swagger UI）

## 專案結構

```
src/main/java/com/tradebeyond/api/
├── controller/   # 路由，僅負責 request/response 轉換
├── service/      # 商業邏輯，@Transactional 邊界
├── repository/   # Spring Data JPA 資料存取
├── dto/          # 對外請求/回應物件
├── entity/       # JPA 實體，不直接回傳給 client
├── exception/    # 例外階層與統一錯誤處理
└── config/       # Security、RateLimiter、OpenAPI 等組態
```

## 如何測試受保護的 API

這個專案的認證機制是 JWT（Access Token + Refresh Token）：`/api/auth/register`、`/api/auth/login`、
`/api/auth/refresh`、`/api/auth/logout` 這四個 endpoint 允許匿名存取，其餘所有 endpoint 都必須帶合法的
`Authorization: Bearer <token>`，未帶或 token 無效會回 401。**刻意不提供任何預設/測試帳號**——
任何人都可以透過 `POST /api/auth/register` 自行建立測試帳號，不需要跟任何人要帳密。

本機/測試環境已經透過 Flyway（`V2__seed_reference_data.sql`）灌入基礎商品資料，不用自己先手動建一筆
才能測試業務流程：

| category_id | category_name | tax_rate | product_id | unit_price |
|---|---|---|---|---|
| 1 | Electronics | 0.0500 | 1 | 100.0000 |
| 1 | Electronics | 0.0500 | 2 | 299.9900 |
| 1 | Electronics | 0.0500 | 3 | 1500.0000 |

以下全程只用 Swagger UI 操作，不需要 terminal：

1. **開啟 Swagger UI**：瀏覽器打開 `http://localhost:8080/swagger-ui.html`。
2. **註冊帳號**：展開 `POST /api/auth/register` → Try it out，帶入 `username`/`account`/`password`（`account` 是唯一值，重複執行要換一個字串），Execute。
3. **登入**：展開 `POST /api/auth/login` → Try it out，帶入剛剛註冊的 `account`/`password`，Execute，從 Response body 複製 `accessToken`（15 分鐘效期）與 `refreshToken`（30 天效期，之後換新 token 會用到）。
4. **設定 Authorize**：點頁面右上角的 **Authorize** 按鈕，貼上 `Bearer <accessToken>`（包含 `Bearer ` 前綴），Authorize 後之後每個請求會自動帶上這個 header，受保護 endpoint 旁邊的鎖頭圖示會從打開變成鎖上。
5. **查詢商品**：展開 `GET /api/product/{productId}` → Try it out，`productId` 帶 `1`（上面表格裡真實存在的資料），Execute，應該回 `200` 並看到 `unitPrice: 100.0000` 的實際商品資料。
6. **建立訂單**：展開 `POST /api/order` → Try it out，帶入：
   - `productId`：`1`
   - `userId`：在乾淨的本機環境上，第一個註冊的帳號就是 `user_id = 1`；不確定的話，把步驟 3 拿到的 `accessToken` 貼到任何 JWT 解碼工具（例如 jwt.io），payload 裡的 `sub` 欄位就是 userId。
   - `orderAmount`：任意正數，例如 `2`

   Execute，記下 Response body 裡的 `orderId`（`totalCost` 會是 `orderAmount * unitPriceSnapshot * (1 + taxRateSnapshot)`，例如 `productId=1`、`orderAmount=2` 會算出 `210.0000`）。
7. **PATCH／DELETE 走一次完整 CRUD**：
   - 展開 `PATCH /api/order/{order_id}` → Try it out，`order_id` 帶上一步拿到的 `orderId`，`orderAmount` 改成另一個數字（例如 `5`），Execute，確認 `totalCost` 有依同一組快照值（`unitPriceSnapshot`/`taxRateSnapshot`，不會重新查 Product/ProductCategory）正確重算。
   - 展開 `DELETE /api/order/{order_id}`，帶同一個 `orderId`，Execute，確認回 `204`。
8. **accessToken 過期後**：不用重新輸入帳密，展開 `POST /api/auth/refresh` → Try it out，帶入步驟 3 拿到的 `refreshToken`，Execute 換回一組全新的 `accessToken`/`refreshToken`（refresh token 會自動輪替，舊的立刻失效），回到步驟 4 重新按 Authorize 貼上新的 `accessToken`。
9. **測完收尾**：展開 `POST /api/auth/logout` → Try it out，帶入目前手上的 `refreshToken`，Execute，撤銷這個 refresh token（access token 本身效期短，撤銷後靠自然過期失效，不會被追蹤攔截）。
