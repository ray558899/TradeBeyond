-- 初始化 schema：Users / ProductCategory / Product / Order
-- 依 CLAUDE.md Part 4.4，全面採軟刪除：每張表都有 create_at / update_at 與 nullable 的 delete_at，
-- 應用程式不下真正的 DELETE FROM，一律透過 Entity 上的 @SQLDelete 改下
-- UPDATE ... SET delete_at = now()。

-- Order 為 SQL 保留字，資料表命名為 orders 以避免每次查詢都要加引號；
-- 對應的 JPA Entity 仍叫 Order，用 @Table(name = "orders") 對應即可。

CREATE TABLE users (
    user_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username    VARCHAR(100)  NOT NULL,
    account     VARCHAR(100)  NOT NULL,
    password    VARCHAR(100)  NOT NULL, -- BCrypt hash，永不存明碼
    create_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    update_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    delete_at   TIMESTAMPTZ   NULL
);

-- 登入識別碼唯一，供 account + password 登入流程查詢使用
CREATE UNIQUE INDEX uk_users_account ON users (account);

CREATE TABLE product_category (
    category_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    category_name VARCHAR(100)   NOT NULL,
    tax_rate      NUMERIC(5, 4)  NOT NULL, -- 例如 0.0500 = 5%
    create_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    update_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    delete_at     TIMESTAMPTZ    NULL
);

CREATE TABLE product (
    product_id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_category_id  BIGINT          NOT NULL,
    unit_price            NUMERIC(19, 4) NOT NULL,
    create_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    update_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    delete_at              TIMESTAMPTZ   NULL,
    CONSTRAINT fk_product_product_category
        FOREIGN KEY (product_category_id) REFERENCES product_category (category_id)
);

CREATE INDEX idx_product_product_category_id ON product (product_category_id);

CREATE TABLE orders (
    order_id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id            BIGINT          NOT NULL,
    product_id         BIGINT          NOT NULL,
    order_amount        NUMERIC(19, 4) NOT NULL,
    unit_price_snapshot NUMERIC(19, 4) NOT NULL, -- 下單當下的 unit_price 快照，重算 total_cost 一律用這個值，不重查 Product
    tax_rate_snapshot   NUMERIC(5, 4)  NOT NULL, -- 下單當下的 tax_rate 快照，不隨後續調整而變動
    total_cost          NUMERIC(19, 4) NOT NULL,
    version             BIGINT         NOT NULL DEFAULT 0, -- 樂觀鎖版本號，PATCH 併發衝突時由 Hibernate 自動遞增與檢查
    create_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    update_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    delete_at           TIMESTAMPTZ    NULL,
    CONSTRAINT fk_orders_user
        FOREIGN KEY (user_id) REFERENCES users (user_id),
    CONSTRAINT fk_orders_product
        FOREIGN KEY (product_id) REFERENCES product (product_id)
);

CREATE INDEX idx_orders_user_id ON orders (user_id);
CREATE INDEX idx_orders_product_id ON orders (product_id);

-- Refresh Token 存 DB（而非 Users 加欄位），因為一個使用者可能同時有多個裝置/session
-- 各自的 refresh token，一對多關係要用獨立表才能正確表達。
-- 這張表刻意不套用 Part 4.4 的 create_at/update_at/delete_at 軟刪除三件套：
-- token 的生命週期是「過期」或「撤銷」，不是一般資源的軟刪除語意，用 revoked_at 就足以表達。
CREATE TABLE refresh_token (
    refresh_token_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id           BIGINT       NOT NULL,
    token             VARCHAR(255) NOT NULL, -- 存 SHA-256 雜湊值（Base64URL 編碼），不是明碼 token
    expires_at        TIMESTAMPTZ  NOT NULL,
    revoked_at        TIMESTAMPTZ  NULL,
    create_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_refresh_token_user
        FOREIGN KEY (user_id) REFERENCES users (user_id)
);

CREATE UNIQUE INDEX uk_refresh_token_token ON refresh_token (token);
CREATE INDEX idx_refresh_token_user_id ON refresh_token (user_id);
