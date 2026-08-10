-- 实时推荐结果表：由 Kafka Consumer 消费 recommendations Topic 后写入
-- 存储每个用户最新一轮 Spark 流式计算的推荐结果
CREATE TABLE IF NOT EXISTS realtime_recommendation (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT       NOT NULL,
    item_id      BIGINT       NOT NULL,
    rank_pos     INT          NOT NULL,
    score        DOUBLE       NOT NULL,
    window_start DATETIME(3),
    window_end   DATETIME(3),
    generated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_user_rank (user_id, rank_pos),
    INDEX idx_user (user_id),
    INDEX idx_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
