-- 1. 用户表 (增加字段：用于测试更复杂的 BI 聚合)
DROP TABLE IF EXISTS order_refunds;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS users;

CREATE TABLE users (
                       user_id INT PRIMARY KEY,
                       name VARCHAR(50) NOT NULL,
                       is_vip TINYINT DEFAULT 0, -- 0-普通, 1-VIP
                       created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE users IS '用户基础信息表';
COMMENT ON COLUMN users.user_id IS '用户唯一标识';
COMMENT ON COLUMN users.is_vip IS '是否为VIP用户: 0-普通, 1-VIP';

-- 2. 订单表 (显式关联 user_id)
CREATE TABLE orders (
                        order_id VARCHAR(32) PRIMARY KEY,
                        user_id INT,
                        total_amount DECIMAL(10, 2),
                        status INT, -- 状态: 0-未支付, 1-已支付, 2-已退款
                        order_date DATE,
                        CONSTRAINT fk_user FOREIGN KEY (user_id) REFERENCES users(user_id)
);
COMMENT ON COLUMN orders.status IS '状态: 0-未支付, 1-已支付, 2-已退款';

-- 3. 退款明细表 (显式关联 order_id)
CREATE TABLE order_refunds (
                               refund_id VARCHAR(32) PRIMARY KEY,
                               order_id VARCHAR(32),
                               refund_amount DECIMAL(10, 2),
                               refund_reason VARCHAR(200),
                               CONSTRAINT fk_order FOREIGN KEY (order_id) REFERENCES orders(order_id)
);

-- 4. 初始化数据 (增加干扰项)
-- 用户数据
INSERT INTO users (user_id, name, is_vip, created_at) VALUES (1, '张三', 1, '2025-01-01 10:00:00');
INSERT INTO users (user_id, name, is_vip, created_at) VALUES (2, '李四', 0, '2025-02-15 12:00:00');
INSERT INTO users (user_id, name, is_vip, created_at) VALUES (3, '王五', 1, '2025-03-10 09:00:00');

-- 订单数据：增加跨年和不同状态
INSERT INTO orders (order_id, user_id, total_amount, status, order_date) VALUES ('ORD001', 1, 5000.00, 1, '2026-01-20');
INSERT INTO orders (order_id, user_id, total_amount, status, order_date) VALUES ('ORD002', 1, 300.00, 1, '2026-01-25');
INSERT INTO orders (order_id, user_id, total_amount, status, order_date) VALUES ('ORD003', 2, 1200.00, 0, '2026-01-29'); -- 未支付
INSERT INTO orders (order_id, user_id, total_amount, status, order_date) VALUES ('ORD004', 3, 2000.00, 2, '2026-01-30'); -- 已退款
INSERT INTO orders (order_id, user_id, total_amount, status, order_date) VALUES ('ORD005', 1, 100.00, 1, '2025-12-30');  -- 去年数据（干扰项）

-- 退款数据
INSERT INTO order_refunds (refund_id, order_id, refund_amount, refund_reason) VALUES ('REF001', 'ORD004', 1800.00, '用户不满意');