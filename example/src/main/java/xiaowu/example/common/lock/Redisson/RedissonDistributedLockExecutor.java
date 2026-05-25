package xiaowu.example.common.lock.Redisson;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import xiaowu.example.common.lock.DistributedLockExecutor;

@Component
public class RedissonDistributedLockExecutor implements DistributedLockExecutor {
  private final RedissonClient redissonClient;

  public RedissonDistributedLockExecutor(RedissonClient redissonClient) {
    this.redissonClient = Objects.requireNonNull(redissonClient);
  }

  @Override
  public <T> T executeWithLock(String lockKey, Duration waitTime, Duration leaseTime, Supplier<T> task) {
    validate(lockKey, waitTime, leaseTime, task);
    RLock lock = redissonClient.getLock(lockKey);
    boolean locked = false;
    try {
      locked = lock.tryLock(waitTime.toMillis(), leaseTime.toMillis(), TimeUnit.MILLISECONDS);
      if (!locked) {
        throw new IllegalStateException("Failed to acquire lock for key: " + lockKey);
      }
      return task.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalArgumentException("Thread was interrupted while waiting for lock: " + lockKey, e);

    } finally {
      if (locked && lock.isHeldByCurrentThread()) {
        lock.unlock();
      }
    }
  }

  @Override
  public <T> T executeWithWatchdogLock(String lockKey, Duration waitTime, Supplier<T> task) {
    validateWatchdog(lockKey, waitTime, task);
    RLock lock = redissonClient.getLock(lockKey);
    boolean locked = false;
    try {
      locked = lock.tryLock(waitTime.toMillis(), TimeUnit.MILLISECONDS);
      if (!locked) {
        throw new IllegalStateException("Failed to acquire lock for key: " + lockKey);
      }
      return task.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalArgumentException("Thread was interrupted while waiting for lock: " + lockKey, e);
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

  private static void validateWatchdog(
      String lockKey,
      Duration waitTIme,
      Supplier<?> action) {
    if (lockKey == null || lockKey.isBlank()) {
      throw new IllegalArgumentException("lockKey must not be blank");
    }
    if (waitTIme == null || waitTIme.isNegative()) {
      throw new IllegalArgumentException("waitTime must not be negative");
    }
    Objects.requireNonNull(action, "action");
  }

}
