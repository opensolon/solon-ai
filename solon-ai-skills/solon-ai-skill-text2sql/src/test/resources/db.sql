CREATE TABLE users (
                       user_id INT PRIMARY KEY COMMENT '用户唯一标识',
                       name VARCHAR(50) NOT NULL COMMENT '用户姓名',
                       is_vip TINYINT DEFAULT 0 COMMENT '是否为VIP用户: 0-普通, 1-VIP',
                       created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间'
);

CREATE TABLE orders (
                        order_id VARCHAR(32) PRIMARY KEY COMMENT '订单流水号',
                        user_id INT COMMENT '关联用户ID',
                        total_amount DECIMAL(10, 2) COMMENT '订单总金额（元）',
                        status INT COMMENT '状态: 0-未支付, 1-已支付, 2-已退款',
                        order_date DATE COMMENT '下单日期'
);

INSERT INTO users (user_id, name, is_vip, created_at) VALUES (1, '张三', 1, '2025-01-01 10:00:00');
INSERT INTO users (user_id, name, is_vip, created_at) VALUES (2, '李四', 0, '2025-02-15 12:00:00');

INSERT INTO orders (order_id, user_id, total_amount, status, order_date) VALUES ('ORD001', 1, 5000.00, 1, '2026-01-20');
INSERT INTO orders (order_id, user_id, total_amount, status, order_date) VALUES ('ORD002', 1, 300.00, 1, '2026-01-25');
INSERT INTO orders (order_id, user_id, total_amount, status, order_date) VALUES ('ORD003', 2, 1200.00, 0, '2026-01-29');