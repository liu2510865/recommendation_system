1. [Redis作为一款内存数据库，它的主要数据结构有哪些？请分别介绍它们的应用场景。.md](Redis作为一款内存数据库，它的主要数据结构有哪些？请分别介绍它们的应用场景。.md)  
2.  [Redis的持久化机制有哪些？RDB和AOF各自的优缺点是什么？在你的项目中是如何选择和配置的？.md](Redis的持久化机制有哪些？RDB和AOF各自的优缺点是什么？在你的项目中是如何选择和配置的？.md) 
3. 我觉得你不要按“Redis 命令表”去学，而要按 **Java 后端业务场景** 去学。Redis 在实际开发里不是简单的 `set/get`，而是一个 **高速数据结构服务器 + 缓存层 + 并发协调工具 + 轻量消息/事件流组件**。官方文档里 Redis 支持 String、Hash、List、Set、ZSet、Bitmap、HyperLogLog、Geo、Stream 等结构，也内置 Lua、事务、淘汰策略、持久化、Sentinel、Cluster 等能力。([Redis](https://redis.io/about/ "About Redis: Fast in-memory database"))

## 1. 入门阶段：先知道 Redis 能解决什么问题

Java 项目里 Redis 最常见的用途是：

| 场景                  | Redis 用法                 |
| ------------------- | ------------------------ |
| 缓存热点数据              | 商品详情、用户信息、课程信息、配置项       |
| 分布式 Session / Token | 登录态、JWT 黑名单、验证码          |
| 计数器                 | 浏览量、点赞数、限流计数             |
| 排行榜                 | ZSet 做积分榜、热度榜            |
| 去重集合                | Set 做用户收藏、点赞、抽奖名单        |
| 简单消息通知              | Pub/Sub 或 Stream         |
| 分布式锁                | 秒杀、库存扣减、定时任务互斥           |
| 限流                  | `INCR + EXPIRE` 或 Lua 脚本 |
| 签到/活跃统计             | Bitmap                   |
| UV 近似统计             | HyperLogLog              |
| 附近的人/店铺             | Geo                      |
|                     |                          |

你要先掌握这些基础命令：

```text
String: SET, GET, MGET, INCR, DECR, SETNX, EXPIRE
Hash: HSET, HGET, HMGET, HINCRBY
List: LPUSH, RPUSH, LPOP, RPOP, BLPOP
Set: SADD, SREM, SISMEMBER, SINTER, SUNION
ZSet: ZADD, ZINCRBY, ZRANGE, ZREVRANGE, ZRANK
Stream: XADD, XREADGROUP, XACK, XPENDING
Key: EXISTS, DEL, TTL, SCAN
高级: EVAL, MULTI, EXEC, WATCH, PIPELINE
```

其中 key 的设计非常重要。官方建议 key 最好有结构，例如 `user:1000` 这种 `object-type:id` 风格；过长 key 会浪费内存和增加比较成本，但过短 key 又会降低可读性。([Redis](https://redis.io/docs/latest/develop/using-commands/keyspace/?utm_source=chatgpt.com "Keys and values | Docs"))

---

## 2. Java 开发里必须掌握的接入方式

Java 里你至少要熟悉三套东西：

### 方式一：`StringRedisTemplate`

适合操作字符串、计数器、简单缓存。

```java
stringRedisTemplate.opsForValue().set("user:1:name", "xiaowu");
String name = stringRedisTemplate.opsForValue().get("user:1:name");

stringRedisTemplate.opsForValue().increment("article:100:view");
```

### 方式二：`RedisTemplate`

适合操作对象、Hash、List、Set、ZSet 等复杂结构。Spring Data Redis 文档也说明，`RedisTemplate` 是 Redis 模块的核心类，提供高级抽象、序列化和连接管理，并且提供不同数据结构的操作视图，例如 `ValueOperations`、`HashOperations`、`ZSetOperations` 等。([Home](https://docs.spring.io/spring-data/redis/reference/redis/template.html "Working with Objects through RedisTemplate :: Spring Data Redis"))

推荐你在 Spring Boot 里显式配置序列化：

```java
@Bean
public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
    RedisTemplate<String, Object> template = new RedisTemplate<>();
    template.setConnectionFactory(factory);

    StringRedisSerializer stringSerializer = new StringRedisSerializer();
    GenericJackson2JsonRedisSerializer jsonSerializer =
            new GenericJackson2JsonRedisSerializer();

    template.setKeySerializer(stringSerializer);
    template.setHashKeySerializer(stringSerializer);
    template.setValueSerializer(jsonSerializer);
    template.setHashValueSerializer(jsonSerializer);

    template.afterPropertiesSet();
    return template;
}
```

默认 JDK 序列化在可读性、跨语言、排查问题上都不友好，所以实际项目里通常更推荐 key 用 String 序列化，value 用 JSON 序列化。

### 方式三：Redisson

Redisson 更适合做分布式锁、信号量、读写锁、闭锁、延迟队列等并发组件。Redisson 官方文档说明它提供基于 Redis/Valkey 的分布式可重入锁，并实现了 Java 的 `Lock` 接口；锁还可以通过 pub/sub 通知等待线程。([Redisson](https://redisson.pro/docs/data-and-services/locks-and-synchronizers/ "Locks and synchronizers - Redisson Reference Guide"))

---

## 3. 进阶核心：缓存不是 set/get，重点是缓存架构

### 3.1 Cache Aside 模式

最常用的缓存模式是：

```text
读请求：
1. 先查 Redis
2. Redis 没有，再查 MySQL
3. 查到后写入 Redis，并设置 TTL

写请求：
1. 先更新 MySQL
2. 再删除 Redis 缓存
```

示例：

```java
public UserDTO getUserById(Long userId) {
    String key = "user:" + userId;
    String json = stringRedisTemplate.opsForValue().get(key);

    if (json != null) {
        if ("__NULL__".equals(json)) {
            return null;
        }
        return objectMapper.readValue(json, UserDTO.class);
    }

    User user = userMapper.selectById(userId);

    if (user == null) {
        stringRedisTemplate.opsForValue()
                .set(key, "__NULL__", Duration.ofMinutes(5));
        return null;
    }

    UserDTO dto = convert(user);

    Duration ttl = Duration.ofMinutes(30 + ThreadLocalRandom.current().nextInt(10));
    stringRedisTemplate.opsForValue()
            .set(key, objectMapper.writeValueAsString(dto), ttl);

    return dto;
}
```

这里面已经处理了两个常见问题：

缓存穿透：数据库里也没有的数据，被大量请求反复打到数据库。解决方式是缓存空值，或者使用布隆过滤器。

缓存雪崩：大量 key 同时过期，导致数据库瞬间被打爆。解决方式是 TTL 加随机值，避免同一时间集体失效。

### 3.2 缓存击穿

热点 key 失效时，大量线程同时查数据库，这叫缓存击穿。

常用解决方案：

```text
1. 互斥锁：只有一个线程回源查 DB，其他线程等待
2. 逻辑过期：热点数据不过物理删除，后台异步刷新
3. 热点 key 预热：系统启动或活动开始前提前加载
```

例如使用 Redis 锁保护热点缓存重建：

```java
public ProductDTO getProduct(Long productId) {
    String cacheKey = "product:" + productId;
    String json = stringRedisTemplate.opsForValue().get(cacheKey);

    if (StringUtils.hasText(json)) {
        return objectMapper.readValue(json, ProductDTO.class);
    }

    String lockKey = "lock:product:" + productId;
    Boolean locked = stringRedisTemplate.opsForValue()
            .setIfAbsent(lockKey, "1", Duration.ofSeconds(10));

    if (Boolean.FALSE.equals(locked)) {
        Thread.sleep(100);
        return getProduct(productId);
    }

    try {
        json = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StringUtils.hasText(json)) {
            return objectMapper.readValue(json, ProductDTO.class);
        }

        Product product = productMapper.selectById(productId);
        ProductDTO dto = convert(product);

        stringRedisTemplate.opsForValue()
                .set(cacheKey, objectMapper.writeValueAsString(dto), Duration.ofMinutes(30));

        return dto;
    } finally {
        stringRedisTemplate.delete(lockKey);
    }
}
```

不过这个简单版本还不够严谨，真实项目更推荐用 Redisson，因为它处理了锁续期、线程持有者校验等细节。

---

## 4. 高级特性一：Lua 脚本

Lua 是 Redis 高级特性里最值得学的一个。Redis 官方文档说明，Lua 脚本在 Redis 服务端执行，可以把多条命令组合成原子操作；脚本执行期间具有原子语义，但长脚本会阻塞服务器活动，所以不能写耗时逻辑。([Redis](https://redis.io/docs/latest/develop/programmability/eval-intro/?utm_source=chatgpt.com "Scripting with Lua | Docs"))

适合场景：

```text
1. 限流
2. 秒杀扣库存
3. 分布式锁安全释放
4. 幂等校验
5. 多 key 条件判断
```

固定窗口限流示例：

```java
private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT =
        new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            if current > tonumber(ARGV[2]) then
                return 0
            else
                return 1
            end
            """, Long.class);

public boolean allowRequest(Long userId) {
    String key = "rate:login:" + userId;

    Long result = stringRedisTemplate.execute(
            RATE_LIMIT_SCRIPT,
            List.of(key),
            "60",   // 窗口时间：60 秒
            "10"    // 最多请求：10 次
    );

    return result != null && result == 1;
}
```

这个比 Java 里先 `INCR` 再 `EXPIRE` 更稳，因为多条命令被 Lua 包成了一个原子逻辑。

---

## 5. 高级特性二：分布式锁

Redisson 写法：

```java
public void createOrder(Long userId, Long itemId) throws InterruptedException {
    String lockKey = "lock:order:" + userId + ":" + itemId;
    RLock lock = redissonClient.getLock(lockKey);

    boolean locked = lock.tryLock(2, 10, TimeUnit.SECONDS);

    if (!locked) {
        throw new RuntimeException("请求太频繁，请稍后再试");
    }

    try {
        // 1. 校验是否重复下单
        // 2. 校验库存
        // 3. 扣库存
        // 4. 创建订单
    } finally {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
```

使用分布式锁时要注意：

```text
1. lockKey 必须和业务资源绑定，例如 order:userId:itemId
2. 必须 try-finally 释放锁
3. 释放前判断是否当前线程持有锁
4. 锁时间不能太短，否则业务没执行完锁就过期
5. 不能把 Redis 锁当作强一致性的最终保障
```

Redisson 文档提到，如果没有指定 `leaseTime`，Redisson 会用 watchdog 机制在持锁实例存活时延长锁过期时间；如果指定了 `leaseTime`，锁会在指定时间后自动释放。([Redisson](https://redisson.pro/docs/data-and-services/locks-and-synchronizers/ "Locks and synchronizers - Redisson Reference Guide"))

但是分布式锁要谨慎。Redis 官方提出了 Redlock 这类更复杂的分布式锁算法，目的是比单实例锁更安全；同时 Martin Kleppmann 也指出，Redis 很适合临时、近似、快速变化的数据，但如果业务对一致性和持久性要求非常强，就不能把 Redis 锁当作唯一正确性来源。([Redis](https://redis.io/docs/latest/develop/clients/patterns/distributed-locks/ "Distributed Locks with Redis | Docs")) ([马丁·克莱普曼网站](https://martin.kleppmann.com/2016/02/08/how-to-do-distributed-locking.html "How to do distributed locking — Martin Kleppmann’s blog"))

所以在秒杀、库存、金融类场景里，我建议：

```text
Redis 锁：减少并发冲突
MySQL 乐观锁 / 唯一索引 / 事务：保证最终正确
MQ：削峰和异步处理
```

---

## 6. 高级特性三：ZSet 排行榜

ZSet 是非常适合 Java 项目的高级结构。

例如课程学习积分榜：

```java
public void addScore(Long courseId, Long userId, double score) {
    String key = "rank:course:" + courseId;
    stringRedisTemplate.opsForZSet()
            .incrementScore(key, userId.toString(), score);
}

public Set<ZSetOperations.TypedTuple<String>> topUsers(Long courseId) {
    String key = "rank:course:" + courseId;
    return stringRedisTemplate.opsForZSet()
            .reverseRangeWithScores(key, 0, 9);
}
```

可用于：

```text
1. 商品热度榜
2. 用户积分榜
3. 搜索热词榜
4. 推荐系统候选集排序
5. 延迟队列：score 存执行时间戳
```

如果你做推荐系统，ZSet 会很有用。比如：

```text
recommend:user:1001
member = itemId
score = 推荐分数
```

然后用 `ZREVRANGE` 取 Top N 商品。

---

## 7. 高级特性四：Pipeline 批量操作

如果你要一次查 1000 个 key，不能循环 1000 次请求 Redis。Redis 官方文档说明，Pipeline 可以一次发送多条命令，不等待每条命令单独返回，从而减少网络 RTT 开销。([Redis](https://redis.io/docs/latest/develop/using-commands/pipelining/?utm_source=chatgpt.com "Redis pipelining | Docs"))

示例：

```java
public List<Object> batchGet(List<String> keys) {
    return stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
        for (String key : keys) {
            connection.stringCommands().get(key.getBytes(StandardCharsets.UTF_8));
        }
        return null;
    });
}
```

适合：

```text
1. 批量预热缓存
2. 批量删除缓存
3. 批量写入排行榜
4. 批量统计计数器
```

不适合：

```text
1. 后一条命令依赖前一条命令结果
2. 单次批量太大，导致客户端或 Redis 内存压力过高
3. 希望中途失败就回滚的场景
```

---

## 8. 高级特性五：Redis Stream

Redis Stream 可以理解成 Redis 里的轻量级消息流。官方文档描述 Stream 是一种 append-only log，并且支持 consumer groups。([Redis](https://redis.io/docs/latest/develop/data-types/streams/?utm_source=chatgpt.com "Redis Streams | Docs"))

适合：

```text
1. 异步任务
2. 订单事件
3. 聊天消息投递
4. 通知系统
5. 操作日志流
```

例如写入一条消息：

```java
Map<String, String> body = new HashMap<>();
body.put("userId", "1001");
body.put("event", "COURSE_FINISHED");
body.put("courseId", "88");

RecordId recordId = stringRedisTemplate.opsForStream()
        .add("stream:course:event", body);
```

消费时可以用 consumer group：

```text
XGROUP CREATE stream:course:event group-course $ MKSTREAM
XREADGROUP GROUP group-course consumer-1 COUNT 10 BLOCK 2000 STREAMS stream:course:event >
XACK stream:course:event group-course messageId
```

不过 Stream 不等于 Kafka。Redis Stream 适合中小规模异步事件、轻量消息流。如果是大规模日志、复杂消费者、长期消息保留，Kafka/RocketMQ 更合适。

---

## 9. 高级特性六：事务、WATCH 和 CAS

Redis 事务和 MySQL 事务不是一回事。Redis 官方文档说明，事务围绕 `MULTI`、`EXEC`、`DISCARD`、`WATCH` 展开，事务内命令会被序列化并顺序执行，`WATCH` 可以实现类似 CAS 的乐观锁。([Redis](https://redis.io/docs/latest/develop/using-commands/transactions/?utm_source=chatgpt.com "Transactions | Docs"))

适合：

```text
1. 多个命令需要连续执行
2. 简单 CAS 更新
3. 批量写入并保持执行顺序
```

但要注意：

```text
1. Redis 事务没有 MySQL 那种复杂回滚
2. 事务里不能根据上一条命令结果决定下一条命令
3. 复杂条件逻辑更推荐 Lua
```

所以实际开发里：

```text
简单批量执行：MULTI / EXEC
需要条件判断 + 原子性：Lua
需要强事务一致性：MySQL 事务
```

---

## 10. 运维进阶：持久化、淘汰、Sentinel、Cluster

你还要知道 Redis 怎么在生产环境里活下来。

Redis 官方文档说明，Redis 持久化主要包括 RDB、AOF、无持久化、RDB + AOF 组合：RDB 是按时间点生成快照，AOF 是记录写操作并在启动时重放。([Redis](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/?utm_source=chatgpt.com "Redis persistence | Docs"))

你需要理解：

```text
RDB：适合备份，恢复快，但可能丢失最近一段时间数据
AOF：数据更安全，但文件更大，恢复可能更慢
No persistence：纯缓存场景可以考虑
RDB + AOF：更稳，但成本更高
```

生产里还要了解：

```text
maxmemory：限制 Redis 最大内存
淘汰策略：allkeys-lru、volatile-lru、allkeys-random、volatile-ttl 等
Sentinel：主从高可用和自动故障转移
Cluster：数据分片和水平扩展
SlowLog：排查慢命令
SCAN：替代 KEYS，避免阻塞 Redis
big key：大 value、大 list、大 hash 都要小心
hot key：热点 key 可能打爆单节点
```

---

## 11. 结合你的 Java 项目，可以这样用

你做过 Spring Boot、JWT、WebSocket、MySQL 优化、推荐系统这些项目，所以 Redis 可以这样落地：

### 在线通讯平台

```text
JWT 黑名单：blacklist:token:{token}，TTL = token 剩余有效期
在线用户：online:course:{courseId}，Set 存 userId
未读消息数：unread:{userId}，Hash 存会话 ID -> 未读数
WebSocket 多节点广播：Pub/Sub 或 Stream
文件下载限流：Lua + INCR + EXPIRE
```

### 电商/推荐系统

```text
商品详情缓存：product:{id}
用户收藏：user:{id}:favorites，Set
商品热度榜：rank:product:hot，ZSet
推荐候选集：recommend:user:{id}，ZSet
UV 统计：HyperLogLog
秒杀库存：Lua + Redis 预扣减 + MQ + MySQL 最终落库
```

### 金融数据中台

```text
接口幂等：idempotent:{requestId}，SET NX EX
任务互斥：Redisson Lock
热点数据缓存：Cache Aside
数据同步进度：sync:task:{taskId}:offset
异常重试队列：Stream
```

---

## 12. 我建议你的 Redis 学习路线

你可以按这个顺序学：

```text
第一阶段：基础命令
String、Hash、List、Set、ZSet、TTL、SCAN

第二阶段：Java 接入
StringRedisTemplate、RedisTemplate、序列化、Spring Cache

第三阶段：缓存实战
Cache Aside、穿透、击穿、雪崩、热点 key、大 key、缓存一致性

第四阶段：并发控制
SET NX EX、Lua、Redisson、幂等、限流、分布式锁

第五阶段：高级结构
Bitmap、HyperLogLog、Geo、ZSet 排行榜、Stream 消息流

第六阶段：生产运维
RDB、AOF、主从、Sentinel、Cluster、慢命令、内存淘汰、监控告警
```

真正进阶的标志不是“会多少命令”，而是你能看到一个业务需求后，马上判断：

```text
这个需求该用 String、Hash、Set 还是 ZSet？
需不需要 TTL？
缓存一致性怎么保证？
有没有热点 key？
Redis 挂了系统怎么降级？
是否需要 Lua 保证原子性？
是否需要 Redisson？
最终一致性由 Redis 保证，还是由 MySQL/MQ 保证？
```

你把这些问题想清楚，Redis 在 Java 后端里基本就算真正会用了。