# RedissonDistributedLockExecutor - 封装Redisson类

### Current Goal

这一步实现 Redisson 版本的锁执行器：`RedissonDistributedLockExecutor`。

它会实现上一 步的 `DistributedLockExecutor` 接口，真正调用 `RedissonClient` 获取 `RLock`，并保证业务执行结束后释放锁。

### Why This Step Exists

业务层不应该到处写 Redisson 细节，否则以后代码会变成：

```java
getLock -> tryLock -> try/finally -> unlock
```

重复出现在很多 service 里。

我们把它集中到一个 infrastructure adapter 里，业务层只依赖接口，底层才依赖 Redisson。这是典型的“应用层定义能力，基础设施层实现能力”。

### Under The Hood

`RLock.tryLock(waitTime, leaseTime, unit)` 有两个关键时间：

- `waitTime`：最多等待多久去抢锁。
- `leaseTime`：抢到锁以后，锁多久自动释放。

注意：这里传了明确的 `leaseTime`，Redisson 不会无限续期。这样适合教学和大部分短业务场景，因为锁一定会自动过期，避免服务宕机后永久死锁。

### Now Edit This

新建文件：

`C:\Users\admin\Desktop\recommendation_system\example\src\main\java\xiaowu\example\payment\seckill\infrastructure\redis\RedissonDistributedLockExecutor.java`

写入：

```java
package xiaowu.example.payment.seckill.infrastructure.redis;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import xiaowu.example.payment.seckill.application.port.DistributedLockExecutor;

@Component
public class RedissonDistributedLockExecutor implements DistributedLockExecutor {

  private final RedissonClient redissonClient;

  public RedissonDistributedLockExecutor(RedissonClient redissonClient) {
    this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient");
  }

  @Override
  public <T> T executeWithLock(
      String lockKey,
      Duration waitTime,
      Duration leaseTime,
      Supplier<T> action) {

    validate(lockKey, waitTime, leaseTime, action);

    RLock lock = redissonClient.getLock(lockKey);
    boolean locked = false;

    try {
      locked = lock.tryLock(waitTime.toMillis(), leaseTime.toMillis(), TimeUnit.MILLISECONDS);
      if (!locked) {
        throw new IllegalStateException("Failed to acquire distributed lock: " + lockKey);
      }
      return action.get();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while acquiring distributed lock: " + lockKey, ex);
    } finally {
      if (locked && lock.isHeldByCurrentThread()) {
        lock.unlock();
      }
    }
  }

  private static void validate(
      String lockKey,
      Duration waitTime,
      Duration leaseTime,
      Supplier<?> action) {

    if (lockKey == null || lockKey.isBlank()) {
      throw new IllegalArgumentException("lockKey must not be blank");
    }
    if (waitTime == null || waitTime.isNegative()) {
      throw new IllegalArgumentException("waitTime must not be negative");
    }
    if (leaseTime == null || leaseTime.isZero() || leaseTime.isNegative()) {
      throw new IllegalArgumentException("leaseTime must be positive");
    }
    Objects.requireNonNull(action, "action");
  }
}
```

### Checkpoint

执行：

```powershell
mvn -pl example -DskipTests compile
```

期望：

```text
BUILD SUCCESS
```

### Common Mistake

1. `InterruptedException` 不能直接吞掉，要调用 `Thread.currentThread().interrupt()` 恢复中断标记。
2. `unlock()` 必须放在 `finally` 里，否则业务异常会导致锁不释放。
3. 解锁前用 `lock.isHeldByCurrentThread()`，避免当前线程没有持有锁却误调用 `unlock()`。

### Next Step Preview

下一步我们会写一个很小的使用点，把锁接入到秒杀预约流程里，保护同一个 `skuId` 的并发预约入口。

你先完成这一步，把结果或报错贴给我。
