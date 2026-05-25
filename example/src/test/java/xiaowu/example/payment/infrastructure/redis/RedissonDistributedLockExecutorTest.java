package xiaowu.example.payment.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import xiaowu.example.common.lock.Redisson.RedissonDistributedLockExecutor;

class RedissonDistributedLockExecutorTest {

  @Test
  void executeWithLockAllowsOnlyLockOwnerToEnterCriticalSection() throws Exception {
    RedissonClient redissonClient = mock(RedissonClient.class);
    RLock lock = mock(RLock.class);
    when(redissonClient.getLock("lock:test:sku:1")).thenReturn(lock);
    when(lock.tryLock(eq(0L), eq(500L), eq(TimeUnit.MILLISECONDS)))
        .thenReturn(true, false, false, false, false);
    when(lock.isHeldByCurrentThread()).thenReturn(true);

    RedissonDistributedLockExecutor executor = new RedissonDistributedLockExecutor(redissonClient);
    CountDownLatch startGate = new CountDownLatch(1);
    AtomicInteger enteredCount = new AtomicInteger();

    List<Callable<Boolean>> tasks = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      tasks.add(() -> {
        startGate.await();
        try {
          return executor.executeWithLock(
              "lock:test:sku:1",
              Duration.ZERO,
              Duration.ofMillis(500),
              () -> {
                enteredCount.incrementAndGet();
                return true;
              });
        } catch (IllegalStateException ex) {
          return false;
        }
      });
    }

    try (var pool = Executors.newFixedThreadPool(5)) {
      var futures = tasks.stream().map(pool::submit).toList();
      startGate.countDown();

      List<Boolean> results = new ArrayList<>();
      for (var future : futures) {
        results.add(future.get(1, TimeUnit.SECONDS));
      }

      assertThat(results).containsExactlyInAnyOrder(true, false, false, false, false);
      assertThat(enteredCount).hasValue(1);
    }
  }

  @Test
  void executeWithLockRejectsBlankLockKey() {
    RedissonDistributedLockExecutor executor = new RedissonDistributedLockExecutor(mock(RedissonClient.class));

    assertThatThrownBy(() -> executor.executeWithLock(
        " ",
        Duration.ZERO,
        Duration.ofSeconds(1),
        () -> "ok"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("lockKey must not be blank");
  }
}
