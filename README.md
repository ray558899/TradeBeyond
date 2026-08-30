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

## 本機啟動

### 1. 啟動資料庫

```bash
docker-compose up -d
```

會啟動一個 PostgreSQL 容器（預設 DB `tradebeyond`、帳號 `admin`、密碼 `123456`，
對應 `application.yml` 的預設環境變數，本機開發不需要另外設定）。

### 2.（可選）設定環境變數

```bash
cp .env.example .env
```

`.env` 目前不會被應用程式自動讀取（尚未整合 dotenv 機制），僅作為本機開發時
手動 `export` 環境變數或供 IDE Run Configuration 參考的範本。若不設定任何環境變數，
`DB_URL` / `DB_USERNAME` / `DB_PASSWORD` 會使用與 `docker-compose.yml` 一致的預設值；
`JWT_SECRET` 沒有預設值，執行前需自行提供（目前程式碼尚未實際使用此設定）。

### 3. 啟動應用程式

```bash
./mvnw spring-boot:run
```

啟動後可存取：

- API 文件（Swagger UI）：http://localhost:8080/swagger-ui.html
- OpenAPI JSON：http://localhost:8080/v3/api-docs

### 執行測試

```bash
./mvnw test
```

## CI

GitHub Actions（`.github/workflows/ci.yml`）會在 push 到任一分支，或對 `main`
開 PR 時執行 `mvn test`。目前階段不包含建置映像檔或部署步驟。
