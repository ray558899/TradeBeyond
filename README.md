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

目前僅為骨架，上述 package 內尚未有任何實作內容。
