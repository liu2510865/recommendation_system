# Redisson 分布式锁总结：底层原理与面试八股

> 关联代码：
>
> - `pom.xml`
> - `src/main/resources/application.yml`
> - `src/main/java/xiaowu/example/payment/application/port/DistributedLockExecutor.java`
> - `src/main/java/xiaowu/example/payment/infrastructure/redis/RedissonDistributedLockExecutor.java`
> - `src/main/java/xiaowu/example/payment/seckill/application/service/SeckillReservationApplicationService.java`
> - `src/main/java/xiaowu/example/payment/seckill/infrastructure/config/SeckillApplicationConfiguration.java`
> - `src/test/java/xiaowu/example/payment/infrastructure/redis/RedissonDistributedLockExecutorTest.java`

## 1. 全局定位

这次实现的目标不是“学会调用一个 Redisson API”，而是在秒杀预约链路里补上一个跨 JVM、跨实例的互斥边界。

在单机 Java 里，`synchronized` 和 `ReentrantLock` 只能保护当前 JVM 内部的线程。如果应用部署成多个实例，请求可能被负载均衡打到不同 JVM。此时每个 JVM 里的本地锁互相不知道对方存在，不能保护同一份 Redis 库存、同一条数据库记录、同一批订单状态。

Redisson 分布式锁解决的是这个问题：把锁状态放到 Redis 这种外部共享存储里，让多个应用实例争抢同一个 Redis key。

在本项目中，它被用在秒杀预约入口：

```text
同一个 activityId + skuId
        |
        v
lock:payment:seckill:reserve:{activityId}:{skuId}
        |
        v
只有拿到锁的线程可以进入 reserveWithLock()
```

它的定位要说清楚：

- 它保护的是“同一 SKU 的预约临界区”。
- 它不替代 Redis Lua 的库存原子扣减。
- 它不替代数据库唯一约束和幂等控制。
- 它降低并发冲突和重复状态推进风险，但最终一致性仍要靠缓存、数据库、消息等多层防线共同保证。

## 2. 当前项目实现链路

### 2.1 依赖和配置

`pom.xml` 引入：

```xml
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson-spring-boot-starter</artifactId>
    <version>${redisson.version}</version>
</dependency>
```

当前版本：

```xml
<redisson.version>3.52.0</redisson.version>
```

`application.yml` 使用 Spring Boot 3.x 推荐的 Redis 配置：

```yaml
spring:
  data:
    redis:
      host: ${SPRING_DATA_REDIS_HOST:127.0.0.1}
      port: ${SPRING_DATA_REDIS_PORT:6379}
      database: ${SPRING_DATA_REDIS_DATABASE:0}
      timeout: 3s
```

Redisson starter 会基于 Spring 配置创建 `RedissonClient`。因此业务代码不需要手动 new 客户端。

### 2.2 应用层接口

`DistributedLockExecutor` 位于 `payment.application.port`：

```java
public interface DistributedLockExecutor {
  <T> T executeWithLock(
      String lockKey,
      Duration waitTime,
      Duration leaseTime,
      Supplier<T> task);
}
```

这是一层“能力抽象”。

业务层只知道“我需要在锁内执行一段逻辑”，不直接知道 Redisson 的 `RLock`。这样做的价值是：

- 业务层不绑定具体 Redis 客户端。
- 单元测试可以 mock 这个接口。
- 以后如果改成数据库锁、ZooKeeper 锁、MQ 串行化，业务服务不需要大面积改造。

### 2.3 基础设施实现

`RedissonDistributedLockExecutor` 位于 `payment.infrastructure.redis`：

```java
RLock lock = redissonClient.getLock(lockKey);
locked = lock.tryLock(waitTime.toMillis(), leaseTime.toMillis(), TimeUnit.MILLISECONDS);
```

核心模板：

```java
try {
  locked = lock.tryLock(...);
  if (!locked) {
    throw new IllegalStateException(...);
  }
  return task.get();
} finally {
  if (locked && lock.isHeldByCurrentThread()) {
    lock.unlock();
  }
}
```

这个模板有三个关键点：

- `tryLock` 控制等待时间，避免请求线程一直卡死。
- `finally` 确保业务异常时也释放锁。
- `isHeldByCurrentThread()` 防止当前线程没有持有锁却误解锁。

### 2.4 秒杀服务接入点

`SeckillReservationApplicationService.reserve()` 现在只做两件事：

```java
String lockKey = buildReserveLockKey(command);
return distributedLockExecutor.executeWithLock(
    lockKey,
    RESERVE_LOCK_WAIT_TIME,
    RESERVE_LOCK_LEASE_TIME,
    () -> reserveWithLock(command));
```

真正的业务逻辑被放进 `reserveWithLock()`。

这样拆分以后，代码含义很清楚：

- `reserve()`：并发控制入口。
- `reserveWithLock()`：预约业务流程。

当前锁参数：

```java
private static final Duration RESERVE_LOCK_WAIT_TIME = Duration.ofMillis(200);
private static final Duration RESERVE_LOCK_LEASE_TIME = Duration.ofSeconds(5);
```

含义：

- 最多等待 200ms 抢锁。
- 抢到锁以后最多持有 5s。
- 如果 5s 内业务还没执行完，锁会自动过期，这是一个必须关注的边界。

### 2.5 当前测试

`RedissonDistributedLockExecutorTest` 使用 mock 的 `RedissonClient` 和 `RLock` 做单元测试：

```text
5 个线程同时抢 lock:test:sku:1
只有第 1 次 tryLock 返回 true
其余 4 次返回 false
断言临界区只进入 1 次
```

验证命令：

```powershell
mvn -Dtest=RedissonDistributedLockExecutorTest test
```

这个测试验证的是封装逻辑，不依赖真实 Redis。后续可以补一个真实 Redis 集成测试。

## 3. 为什么选择 Redisson，而不是自己写 Redis 锁

### 3.1 项目约束

这个项目已经使用 Spring Boot、Redis、Kafka、MySQL，并且秒杀预约流程涉及：

- Redis 缓存库存。
- 数据库预约记录。
- Kafka 预约事件。
- 异常补偿和释放库存。

这不是一个只执行一条 Redis 命令就结束的场景，而是一个多步骤业务临界区。

### 3.2 Redisson 的收益

Redisson 给的是接近 Java `Lock` 的编程模型：

```java
RLock lock = redissonClient.getLock("myLock");
boolean locked = lock.tryLock(100, 10, TimeUnit.SECONDS);
```

它替你处理了很多容易写错的细节：

- 加锁和解锁的 Lua 原子性。
- 可重入。
- 锁过期。
- owner thread 校验。
- 等待线程通知。
- watchdog 自动续期能力。
- Spring Boot 自动配置。

在业务项目里，直接用成熟组件比手写底层锁更稳。

### 3.3 和备选方案对比

| 方案 | 优点 | 缺点 | 适合场景 |
| --- | --- | --- | --- |
| Redisson `RLock` | 成熟、可重入、有 watchdog、接近 Java Lock | 引入依赖，仍依赖 Redis 可用性 | Java/Spring 项目里的通用分布式互斥 |
| 手写 `SET NX PX` + Lua | 依赖少、机制清楚 | 容易漏 owner 校验、续期、可重入、等待通知 | 简单一次性脚本、强学习目的 |
| MySQL `SELECT ... FOR UPDATE` | 持久可靠，事务语义明确 | 热点行会压垮 DB，吞吐低 | 强一致、低并发、以数据库为中心的流程 |
| Redis Lua 原子扣减 | 极快，天然原子 | 只适合把逻辑压进一段脚本，不适合复杂 Java 流程 | 库存扣减、限流、计数 |
| MQ 按 SKU 串行消费 | 削峰好，天然串行 | 异步化，用户不能立刻拿到最终结果 | 高并发削峰、可接受最终一致 |

本项目当前选择 Redisson 的原因：业务已经在 Java service 中编排 Redis、DB、Kafka，锁要保护的是这段 Java 业务临界区，而不是单条 Redis 命令。

## 4. 底层原理：从 Redis 命令到 Redisson RLock

### 4.1 最朴素的 Redis 锁

Redis 锁最基本的思想是：

```text
SET lock:key uniqueValue NX PX 5000
```

含义：

- `NX`：key 不存在时才设置，保证互斥。
- `PX 5000`：设置 5 秒过期，避免服务宕机导致死锁。
- `uniqueValue`：标识当前锁的持有者，解锁时要校验。

不能这样解锁：

```text
DEL lock:key
```

原因是锁可能已经过期，又被其他线程拿到了。此时旧线程执行 `DEL` 会删除别人的锁。

正确解锁必须是“判断 owner + 删除”原子执行：

```lua
if redis.call('get', KEYS[1]) == ARGV[1] then
  return redis.call('del', KEYS[1])
else
  return 0
end
```

这就是为什么 Redis 分布式锁通常离不开 Lua。

### 4.2 Redisson 如何实现可重入

Redisson 的 `RLock` 不是简单的 String key。常见实现思路是用 Redis Hash 保存锁 owner 和重入次数。

可以把它理解成：

```text
key: lock:payment:seckill:reserve:1:1001
type: hash
field: {clientId}:{threadId}
value: reentrantCount
ttl: leaseTime
```

第一次加锁：

```text
锁不存在 -> 设置 field = 当前线程标识，value = 1，设置 TTL
```

同一线程重入：

```text
field 已经是当前线程 -> value + 1，刷新 TTL
```

其他线程加锁：

```text
field 不是当前线程 -> 加锁失败，返回剩余 TTL 或等待通知
```

解锁：

```text
校验 field 是当前线程
value - 1
如果 value == 0，删除锁并发布解锁通知
```

这就是“分布式可重入锁”的核心。

### 4.3 Redisson 为什么要用 Pub/Sub

如果一个线程没抢到锁，最笨的方式是循环睡眠重试：

```text
try lock -> fail -> sleep -> try lock -> fail -> sleep
```

这种方式会制造大量无意义请求。

Redisson 会使用 Redis Pub/Sub 通知等待线程：锁释放时发布消息，等待者收到消息后再尝试抢锁。

所以 Redisson 锁不是单纯 `SET NX`，它还包含等待、通知、超时这些调度逻辑。

### 4.4 watchdog 是什么

Redisson 官方文档说明，`RLock` 有 lock watchdog 机制：当锁持有者 Redisson 实例还活着时，它可以延长锁过期时间，避免业务执行时间长于默认过期时间导致锁提前释放。

默认 watchdog timeout 是 30 秒，可通过 `Config.lockWatchdogTimeout` 调整。

但要注意当前项目调用的是：

```java
lock.tryLock(waitTime, leaseTime, TimeUnit.MILLISECONDS)
```

这里传入了明确的 `leaseTime`。工程上应该按这个规则理解：

- 显式传 `leaseTime`：锁会在指定时间后自动释放。
- 不显式传 `leaseTime`，使用普通 `lock()` 等方式：才主要依赖 watchdog 自动续期。

当前项目的 `leaseTime = 5s`，所以业务临界区必须稳定小于 5 秒。否则可能出现：

```text
线程 A 拿到锁
线程 A 业务卡住超过 5s
锁自动过期
线程 B 拿到锁并进入临界区
线程 A 继续执行
```

这会破坏互斥。

### 4.5 `waitTime` 和 `leaseTime` 的区别

面试里经常混淆这两个参数。

```java
tryLock(waitTime, leaseTime, unit)
```

| 参数 | 含义 | 本项目值 |
| --- | --- | --- |
| `waitTime` | 没拿到锁时最多等多久 | 200ms |
| `leaseTime` | 拿到锁后最多持有多久 | 5s |

一句话：

- `waitTime` 控制“我愿意排队多久”。
- `leaseTime` 控制“我最多占用锁多久”。

### 4.6 为什么解锁前要判断 `isHeldByCurrentThread()`

Redisson 遵守 Java `Lock` 语义：只有锁 owner thread 能解锁。不是当前线程持有锁却调用 `unlock()`，会抛 `IllegalMonitorStateException`。

当前实现：

```java
if (locked && lock.isHeldByCurrentThread()) {
  lock.unlock();
}
```

这避免两类问题：

- `tryLock` 失败时不应该解锁。
- 锁已经过期或当前线程不再是 owner 时，不应该误解锁。

但也要知道：如果锁因为 `leaseTime` 到期已经自动释放，而业务还在执行，`isHeldByCurrentThread()` 很可能返回 false。此时不解锁是正确的，但它也暴露了更大的问题：临界区执行时间超过了锁租约。

## 5. 本项目的落地路径

### 5.1 调用流程

```text
外部请求 / 内部调用
        |
        v
SeckillReservationApplicationService.reserve(command)
        |
        v
buildReserveLockKey(command)
        |
        v
DistributedLockExecutor.executeWithLock(...)
        |
        v
RedissonDistributedLockExecutor
        |
        v
RedissonClient.getLock(lockKey)
        |
        v
RLock.tryLock(waitTime, leaseTime)
        |
        +-- 成功 -> reserveWithLock(command)
        |
        +-- 失败 -> IllegalStateException
        |
        v
finally unlock
```

### 5.2 锁 key 设计

当前 key：

```text
lock:payment:seckill:reserve:{activityId}:{skuId}
```

为什么不用 `userId`？

因为要保护的是库存维度，而不是用户维度。

如果用 `userId`：

```text
用户 1 -> lock:user:1
用户 2 -> lock:user:2
```

两个用户还是能同时扣同一个 SKU 的库存，锁没有保护到真正的共享资源。

正确的锁粒度应该对应“共享资源”：

```text
activityId + skuId
```

### 5.3 为什么业务逻辑放进 `reserveWithLock`

拆成：

```java
reserve()
reserveWithLock()
```

是为了让代码读起来有边界：

- 外层负责并发控制。
- 内层负责业务状态变化。

如果把锁代码散落在业务逻辑中，后续补偿、事件发布、缓存释放都会和锁细节缠在一起，不利于维护。

### 5.4 它和 Redis Lua 库存扣减的关系

秒杀系统里常见两层保护：

```text
Redisson 锁：保护 Java 业务临界区，减少并发状态冲突
Redis Lua：保护库存扣减原子性，保证扣库存不会超卖
DB 唯一约束：保护最终数据不重复
MQ：异步推进后续流程
```

Redisson 锁不是唯一防线。成熟系统通常是多层防御。

## 6. 风险与边界

### 6.1 业务执行时间超过 `leaseTime`

这是当前实现最需要关注的风险。

当前租约是 5 秒。如果 `reserveWithLock()` 因为 DB 慢、Kafka 慢、GC pause、网络抖动超过 5 秒，锁会提前释放。

缓解策略：

- 统计临界区 P95/P99 耗时。
- 把 `leaseTime` 配成业务耗时上界的安全倍数。
- 临界区只放必须互斥的短逻辑。
- 如果确实存在长任务，考虑不显式指定 leaseTime，让 watchdog 续期，或改成异步串行模型。

### 6.2 Redis 不可用

Redis 不可用时，Redisson 无法加锁。

业务上要明确：

- 是直接失败？
- 是降级成排队？
- 是走本地限流？
- 是返回“系统繁忙，请稍后重试”？

秒杀场景通常应该快速失败，而不是长时间阻塞请求线程。

### 6.3 Redis 主从切换窗口

单 Redis master 上的锁在主从切换时存在理论风险：

```text
线程 A 在 master 获取锁
锁还没复制到 slave
master 宕机，slave 升主
线程 B 在新 master 获取同名锁
```

这会导致两个线程都认为自己拿到了锁。

缓解方式：

- 接受这个风险，但用 DB 唯一约束、状态机、幂等兜底。
- 提升 Redis 部署可靠性。
- 使用更强的一致性协调系统，例如 ZooKeeper、etcd。
- 对极高价值资源使用 fencing token 方案。

### 6.4 锁不能替代幂等

锁解决的是“同一时刻不要并发进入”。

幂等解决的是“同一个请求重复来了，结果不能重复产生”。

两者不是一个东西。

例如客户端超时重试：

```text
第 1 次请求拿锁成功并创建预约
客户端没收到响应
第 2 次请求稍后又来了
```

第二次请求可能已经不和第一次并发，但仍然需要幂等判断。锁不能解决这个问题。

### 6.5 锁粒度过粗或过细

过粗：

```text
lock:payment:seckill:reserve
```

所有 SKU 都串行，吞吐量低。

过细：

```text
lock:payment:seckill:reserve:{activityId}:{skuId}:{userId}
```

同一个 SKU 的不同用户可以并发进入，保护不了库存。

当前粒度：

```text
activityId + skuId
```

是更合理的折中。

## 7. 优化方向

### 7.1 短期低风险优化

把锁时间做成配置：

```yaml
payment:
  seckill:
    lock:
      wait-time-ms: 200
      lease-time-seconds: 5
```

好处是线上可以根据延迟数据调整，不需要改代码。

### 7.2 异常语义优化

当前 `RedissonDistributedLockExecutor` 在捕获 `InterruptedException` 后抛的是 `IllegalArgumentException`。

更合适的语义通常是：

```java
throw new IllegalStateException("Interrupted while acquiring distributed lock: " + lockKey, ex);
```

原因：

- 参数没有错。
- 是线程等待锁的过程中被中断。
- 属于运行时状态问题。

### 7.3 增加真实 Redis 集成测试

当前测试是 mock 单元测试。它能验证封装逻辑，但不能验证 Redisson 和 Redis 的真实交互。

可以增加一个集成测试：

```text
启动本地 Redis
创建真实 RedissonClient
多个线程抢同一个 RLock
断言同一时刻临界区最大并发数为 1
```

更完整的做法是 Testcontainers，但这会引入 Docker 依赖。

### 7.4 增加可观测性

建议记录：

- lockKey。
- 是否抢锁成功。
- 等待耗时。
- 临界区执行耗时。
- 抢锁失败次数。

指标示例：

```text
payment.seckill.lock.acquire.success
payment.seckill.lock.acquire.failed
payment.seckill.lock.wait.duration
payment.seckill.lock.hold.duration
```

有了这些指标，才能判断 `waitTime = 200ms` 和 `leaseTime = 5s` 是否合理。

### 7.5 高价值资源考虑 fencing token

Redisson 锁只能说明“某个时刻我拿到了锁”。如果锁因为超时释放，而旧线程后来继续写 DB，就可能覆盖新线程结果。

更强的方案是 fencing token：

```text
每次拿锁得到一个递增 token
写数据库时带 token
数据库只接受比当前 token 更新的写入
```

这样即使旧线程复活，也会因为 token 过旧而被拒绝。

## 8. 面试八股：高频问答

### Q1：什么是分布式锁？

分布式锁是跨进程、跨机器的互斥机制。它把锁状态放到 Redis、ZooKeeper、etcd、数据库等外部共享系统里，让多个服务实例对同一份资源进行互斥访问。

本项目里，锁状态放在 Redis，多个 Java 实例通过 Redisson 竞争同一个 key。

### Q2：为什么不用 `synchronized` 或 `ReentrantLock`？

它们只能锁住当前 JVM 内部线程。

如果服务部署成 3 个实例：

```text
JVM-1: synchronized
JVM-2: synchronized
JVM-3: synchronized
```

这三把锁互相独立，无法保护同一份 Redis 库存或数据库记录。

### Q3：一个合格的 Redis 分布式锁要满足什么？

至少要满足：

- 互斥：同一资源同一时刻只有一个持有者。
- 防死锁：持有者宕机后锁能过期释放。
- owner 解锁：不能删除别人的锁。
- 原子解锁：校验 owner 和删除必须一起完成。
- 可重入：同一线程重复进入不会把自己锁死，视业务需要。
- 超时等待：抢不到锁不能无限阻塞。

### Q4：为什么不能 `SETNX` 加锁、`DEL` 解锁？

因为可能删掉别人的锁。

典型时序：

```text
线程 A 加锁，TTL 5s
线程 A 执行超过 5s，锁过期
线程 B 加锁成功
线程 A 执行 DEL
线程 B 的锁被删掉
```

所以解锁必须校验 owner：

```text
只有 value 是自己的 token，才能删除
```

而且校验和删除必须用 Lua 保证原子性。

### Q5：Redisson 的可重入锁底层怎么做？

可以理解为 Redis Hash：

```text
key = lock name
field = clientId + threadId
value = reentrant count
ttl = lease time
```

同一线程重入时，计数加 1；解锁时计数减 1；减到 0 才真正删除锁。

### Q6：watchdog 是什么？

watchdog 是 Redisson 的自动续期机制。

如果没有显式指定 `leaseTime`，Redisson 可以在锁持有者还活着时定期延长锁 TTL，避免业务还没执行完锁就过期。

默认 watchdog timeout 是 30 秒，可以通过 `lockWatchdogTimeout` 调整。

但本项目显式传入了 `leaseTime = 5s`，所以要重点关注业务是否可能超过 5 秒。

### Q7：`tryLock(waitTime, leaseTime, unit)` 三个参数怎么解释？

```java
tryLock(200, 5000, TimeUnit.MILLISECONDS)
```

含义：

- 最多等 200ms 抢锁。
- 抢到后最多持有 5000ms。
- 超过 5000ms 自动释放。

`waitTime` 是排队等待时间，`leaseTime` 是锁租约时间。

### Q8：为什么解锁要放在 `finally`？

因为业务逻辑可能抛异常。

如果不放在 `finally`：

```text
加锁成功
业务异常
方法提前退出
锁没有释放
```

这会导致其他线程一直抢不到锁，直到 TTL 到期。

### Q9：为什么要判断 `isHeldByCurrentThread()`？

因为 Redisson 只允许锁 owner thread 解锁。

如果当前线程不是 owner，调用 `unlock()` 会抛异常。判断后再解锁可以避免误解锁和无意义异常。

### Q10：锁 key 怎么设计？

锁 key 要对应真正的共享资源。

本项目共享资源是某个活动下的某个 SKU：

```text
lock:payment:seckill:reserve:{activityId}:{skuId}
```

不要用用户维度，因为用户不是库存竞争的核心资源。

### Q11：分布式锁能防超卖吗？

不能只靠分布式锁防超卖。

防超卖一般需要：

- Redis Lua 原子扣减库存。
- 数据库库存或预约记录的最终约束。
- 幂等 key 防重复请求。
- 必要时使用消息队列串行化。

锁只能减少并发进入临界区的概率和冲突面，不应该作为唯一防线。

### Q12：锁过期但业务没执行完怎么办？

这是分布式锁最经典的坑。

可能发生：

```text
A 锁过期后还在执行
B 拿到锁开始执行
A 和 B 同时写共享资源
```

解决方式：

- 增大 `leaseTime`。
- 缩短临界区。
- 使用 watchdog。
- 使用 fencing token。
- 最终写入时用 DB 状态机和版本号兜底。

### Q13：RedLock 是什么？一定要用吗？

RedLock 是 Redis 作者提出的多 Redis 节点加锁算法，试图避免单 master 故障导致锁安全问题。

但它更复杂，也有争议。很多业务系统不一定需要 RedLock，而是选择：

- 单 Redis 锁提升工程简单性。
- 数据库唯一约束和状态机兜底。
- 对极高一致性场景使用 ZooKeeper 或 etcd。

面试回答可以说：普通业务可以用 Redisson 单 Redis 锁加幂等兜底；金融级强一致资源要评估 fencing token、数据库事务或 CP 协调系统。

### Q14：公平锁和普通锁有什么区别？

普通锁不保证等待顺序。

公平锁保证先等待的线程优先获得锁，但代价是调度更复杂、吞吐可能下降。

Redisson 提供 `getFairLock()`。秒杀场景一般更关注吞吐和快速失败，不一定需要公平锁。

### Q15：分布式锁和数据库事务是什么关系？

锁控制的是“谁能进入临界区”。

事务控制的是“进入以后，对数据库的修改要么一起成功，要么一起失败”。

两者解决的问题不同。即使有分布式锁，数据库写入仍然应该有事务、唯一约束、状态机校验。

## 9. 面试回答模板

如果面试官问：“你项目里怎么用 Redisson 分布式锁？”

可以这样回答：

```text
我们在秒杀预约入口用了 Redisson 分布式锁，锁粒度是 activityId + skuId，
因为真正竞争的共享资源是某个活动下某个 SKU 的库存，而不是用户。

代码上没有让业务服务直接依赖 Redisson，而是在 application port 定义了
DistributedLockExecutor，基础设施层用 RedissonClient 和 RLock 实现。
业务层调用 executeWithLock，把预约逻辑放进临界区。

加锁使用 tryLock(waitTime, leaseTime)，waitTime 控制抢不到锁最多等多久，
leaseTime 控制拿到锁后多久自动释放。释放锁放在 finally 里，并且用
isHeldByCurrentThread 防止误解锁。

底层上，Redisson RLock 可以理解为基于 Redis Hash + TTL + Lua 实现的可重入锁。
同一线程重入时计数加一，解锁时计数减一，减到零才删除锁，并通过 Pub/Sub
通知等待者。

但我们没有把锁当作唯一防线。秒杀链路还需要 Redis Lua 保证库存扣减原子性，
数据库唯一约束保证最终幂等，消息失败时还要做补偿。
```

如果继续追问风险，可以补充：

```text
当前实现显式设置了 leaseTime，所以要确保临界区耗时小于租约。
如果业务可能超过租约，需要改成更长租约、缩短临界区、使用 watchdog，
或者引入 fencing token 防止旧线程在锁过期后继续写入。
```

## 10. 学习者自测题

1. 为什么 `activityId + skuId` 比 `userId` 更适合作为秒杀锁 key？
2. `waitTime` 和 `leaseTime` 分别控制什么？
3. 为什么解锁不能直接 `DEL key`？
4. Redisson 可重入锁为什么需要记录 `threadId`？
5. 如果业务执行超过 `leaseTime`，会发生什么？
6. 分布式锁和幂等分别解决什么问题？
7. 为什么秒杀系统不能只靠分布式锁防超卖？
8. Redis 主从切换时，单 Redis 锁有什么理论风险？
9. watchdog 适合解决什么问题？什么时候不会按预期续期？
10. 如果要把这个锁能力复用到支付回调，你会怎么设计 key？

## 11. Open Questions / Uncertainties

- 当前项目还没有真实 Redis 集成测试，只有 `RedissonDistributedLockExecutorTest` 的 mock 单元测试。
- 当前 `RESERVE_LOCK_WAIT_TIME` 和 `RESERVE_LOCK_LEASE_TIME` 是硬编码，需要后续根据压测数据或线上指标调整。
- 当前文档按 Redisson 官方 RLock 行为和常见实现机制解释底层，具体 Redis Lua 脚本细节以当前 Redisson 版本源码为准。
- 当前秒杀预约链路中，锁只是并发控制层；最终是否完全防超卖，还要看 Redis Lua、DB 约束、消息补偿等完整链路是否严密。

## 12. 参考资料

- Redisson Reference Guide - Locks and synchronizers: https://redisson.pro/docs/data-and-services/locks-and-synchronizers/index.html
- Redisson Reference Guide - Configuration: https://redisson.pro/docs/configuration
- Redisson Reference Guide - Spring integration: https://redisson.pro/docs/integration-with-spring/
- Redisson glossary - Redis lock: https://redisson.pro/glossary/redis-lock.html
