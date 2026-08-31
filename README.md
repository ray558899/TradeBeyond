# TradeBeyond Backend API

Spring Boot 3.x 後端 API 專案骨架，範疇為 Users / ProductCategory / Product / Order，
資料庫使用 PostgreSQL，部署目標為 GCP Cloud Run（單一 instance）+ Cloud SQL + Secret Manager。

詳細的架構、安全性與工程規範請見 [CLAUDE.md](./CLAUDE.md)。

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

這個專案的認證機制是 JWT（Access Token + Refresh Token），**刻意不提供任何預設/測試帳號**——
任何人都可以透過 `POST /api/auth/register` 自行建立測試帳號，不需要跟任何人要帳密。

完整測試流程（Swagger UI：`/swagger-ui.html`，或用 curl/Postman 皆可）：

1. **註冊帳號**：`POST /api/auth/register`，帶 `username`/`account`/`password`。這個 endpoint 允許匿名存取，Swagger UI 可以直接操作，不需要帶任何 token。
2. **登入**：`POST /api/auth/login`，帶 `account`/`password`，成功會拿到 `accessToken`（15 分鐘效期）與 `refreshToken`（30 天效期）。
3. **呼叫受保護的業務 endpoint**（`GET /api/product/{id}`、`POST /api/order`、`PATCH /api/order/{order_id}`、`DELETE /api/order/{order_id}`、`DELETE /api/user/{userId}`、`GET /api/order/{userId}`）：在 `Authorization` header 帶 `Bearer <accessToken>`。Swagger UI 可以按右上角「Authorize」按鈕，貼上 `Bearer <accessToken>` 一次性設定，之後每個請求會自動帶上。
4. **accessToken 過期後**：不用重新輸入帳密，呼叫 `POST /api/auth/refresh`，帶 `{"refreshToken": "<上一次拿到的 refreshToken>"}`，換回一組全新的 `accessToken` + `refreshToken`（refresh token 會自動輪替，舊的立刻失效，記得改用新的那組）。
5. **不再需要存取時**：呼叫 `POST /api/auth/logout`，帶 `{"refreshToken": "<refreshToken>"}`，撤銷這個 refresh token（access token 本身效期短，撤銷後靠自然過期失效，不會被追蹤攔截）。

`/api/auth/register`、`/api/auth/login`、`/api/auth/refresh`、`/api/auth/logout` 這四個 endpoint 都允許匿名存取（`refresh`/`logout` 本來就是設計給「沒有合法 access token 可用」的情境使用，不能反過來要求先帶 token）；其餘所有 endpoint 都必須帶合法的 `Authorization: Bearer <token>`，未帶或 token 無效會回 401。
