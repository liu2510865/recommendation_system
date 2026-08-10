# 推荐系统生产改造与 Docker 部署报告

> 日期：2026-08-10  
> 改造目标：从教学级原型升级为可 Docker 部署的生产级系统

---

## 一、改造清单

### 1. 编译阻断修复（P0）

| 问题 | 修复 |
|------|------|
| backed/pom.xml Spring AI artifactId 错误 | `spring-ai-openai-spring-boot-starter` → `spring-ai-starter-model-openai` |
| Application.Java 文件名大写 | 重命名为 `Application.java` |
| spring-boot-maven-plugin 缺少 mainClass | 添加 `<mainClass>xiaowu.backed.Application</mainClass>` |

### 2. 代码质量修复（6 项）

| 问题 | 文件 | 修复 |
|------|------|------|
| DTO 字段拼写错误 | `UserRecommendationDTO` | `windowsEnd` → `windowEnd` |
| 聚合根构造函数忽略参数 | `UserBehaviorAggregate` | 接收并复制传入的 List |
| Producer 配置重复 | `KafkaProducerConfig` | 删除重复的 `BUFFER_MEMORY_CONFIG` |
| 临时测试端点未删除 | `ChatController` | 删除 `test-ai` 端点及无用 import |
| OpenAiClient 配置占位符缺失 | `OpenAiClient` | `@Value` 添加默认值 fallback |
| TrainerHealthIndicator null 值 | `TrainerHealthIndicator` | 所有 `withDetail` 添加 null 检查 |

### 3. Kafka 推荐 Consumer 闭环（核心）

新增文件：
- `RealtimeRecommendation.java` — JPA 实体，映射 `realtime_recommendation` 表
- `RealtimeRecommendationRepository.java` — JPA 仓储接口
- `RecommendationConsumer.java` — Kafka 消费者，消费 `recommendations` Topic，手动 offset 提交，写入 MySQL
- `KafkaConsumerConfig.java` — 消费者配置（手动 ack、500 条/批、30s 心跳超时）
- `realtime_recommendation.sql` — 建表 DDL

更新文件：
- `RecallController` — 新增 `/realtime/{userId}` 端点查询实时推荐

### 4. Spring Security + JWT 认证体系

新增文件：
- `JwtTokenProvider.java` — JWT 生成与验证工具类（HS256 签名）
- `JwtAuthFilter.java` — JWT 认证过滤器（OncePerRequestFilter）
- `SecurityConfig.java` — Spring Security 配置（无状态会话、公开/保护端点划分）
- `AuthController.java` — 登录接口 `POST /api/auth/login`

安全策略：
- 公开端点：`/api/auth/**`、`/actuator/health`、`/actuator/info`
- 保护端点：其余全部需要 JWT Bearer Token
- ADMIN 端点：`/actuator/**`（health/info 除外）

### 5. 生产配置

- `application.yml`：全部配置项环境变量化（`${VAR:default}` 语法）
- `application-prod.yml`：生产级配置（ddl-auto=none、连接池 20、日志格式规范、Spark 2g 内存）
- Trainer `application.yml`：同样环境变量化

### 6. Docker 部署

新增文件：
- `backed/Dockerfile` — 预构建 JAR + eclipse-temurin:17-jre-jammy 基础镜像
- `recommendation-trainer/Dockerfile` — 同上
- `docker/docker-compose.yml` — 4 服务编排（MySQL/Kafka/Backed/Trainer）
- `docker/init-sql/01-schema.sql` — 合并全部建表+数据初始化脚本
- `.dockerignore` — 排除无关文件

---

## 二、Docker 部署验证结果

### 容器状态

| 容器 | 状态 | 端口 |
|------|------|------|
| rec-mysql | ✅ healthy | 3306 |
| rec-kafka | ✅ healthy | 9092 |
| rec-backed | ✅ healthy | 8922 |
| rec-trainer | ✅ healthy | 8923 |

### 功能验证

| 测试项 | 结果 |
|--------|------|
| MySQL 健康检查 | ✅ `{"status":"UP"}` |
| Kafka 健康检查 | ✅ broker 正常运行 |
| backed 健康检查 | ✅ `{"status":"UP"}` |
| trainer 健康检查 | ✅ `{"status":"UP"}` + ALS 训练状态 IDLE |
| 未认证访问 API | ✅ 返回 403 Forbidden |
| JWT 登录 | ✅ 返回 Bearer Token |
| 带 Token 访问 API | ✅ 返回 200 + 数据 |
| Kafka Consumer 加入消费组 | ✅ recommendation-result-group 已分配分区 |
| Trainer 状态查询 | ✅ `{"state":"IDLE","modelName":"user-cf-als"}` |

### 部署方式

```bash
# 1. 构建 JAR（本地）
mvn package -pl backed,recommendation-trainer -am -DskipTests

# 2. 构建 Docker 镜像
cd docker && docker compose build

# 3. 启动全部服务
docker compose up -d

# 4. 登录获取 JWT
curl -X POST http://localhost:8922/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'

# 5. 使用 JWT 调用接口
curl http://localhost:8922/api/recommendation/realtime/1 \
  -H "Authorization: Bearer <token>"

# 6. 停止服务
docker compose down
```

---

## 三、改造前后对比

| 维度 | 改造前 | 改造后 |
|------|--------|--------|
| 编译状态 | ❌ 无法编译 | ✅ 全模块编译通过 |
| 推荐链路闭环 | ❌ Kafka 无 Consumer | ✅ Consumer 消费 + 写入 MySQL |
| 安全认证 | ❌ 零安全防护 | ✅ Spring Security + JWT |
| 配置管理 | ❌ 硬编码 | ✅ 全环境变量化 |
| Docker 部署 | ❌ 无容器化 | ✅ 4 容器全部 healthy |
| 生产配置 | ❌ 无 prod profile | ✅ application-prod.yml |
| 代码质量 | ❌ 6 个 Bug | ✅ 全部修复 |
| 健康检查 | ❌ trainer 500 错误 | ✅ 全部 UP |

---

## 四、已知限制

1. **AI 对话功能**：OpenAI API Key 需要配置为有效值（当前为 placeholder）
2. **Spark 流处理**：需手动通过 `POST /api/stream/start` 触发
3. **ALS 训练**：需手动通过 `POST /api/trainer/als/train` 触发
4. **example 模块**：需要 Java 21，不包含在 Docker 部署中
5. **单节点**：MySQL/Kafka 均为单节点，适合验证但不适合大规模生产
6. **测试覆盖**：核心业务逻辑仍需补充单元测试
