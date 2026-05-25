# executeWithWatchdogLock(String, Duration, Supplier<T>) <T> - watchdogLock

### Current goal

我们分 4 步把 watchdog 模式接进去：

1. 先在锁接口里新增一个“watchdog 锁方法”。
2. 再让 `RedissonDistributedLockExecutor` 实现它。
3. 再把秒杀预约从固定 `leaseTime` 切到 watchdog 方法。
4. 最后补测试，确认调用的是无 `leaseTime` 的 Redisson API。

当前只做第 1 步：**扩展接口，不写 Redisson 实现。**

### Why this step exists

现在你的接口是固定租约模式：

```java
executeWithLock(lockKey, waitTime, leaseTime, task)
```

这里一旦传了 `leaseTime`，语义就是：锁最多持有这么久，到点自动释放。

watchdog 的核心语义正好相反：**业务没有显式指定锁租约时间，由 Redisson 在持锁线程还活着时自动续期。**

所以 senior 写法不建议把 `leaseTime` 传 `null`，而是单独定义一个语义清楚的方法。

### Under the hood

Redisson 的 watchdog 不是一个你手动调用的 API，而是由“加锁方式”触发的。

代码层面：

- 固定租约：`tryLock(waitTime, leaseTime, unit)`
- watchdog 模式：`tryLock(waitTime, unit)`

运行时：

- 固定租约模式：到 `leaseTime` 就释放。
- watchdog 模式：Redisson 定时帮锁续期，默认 watchdog timeout 通常是 30 秒，前提是 Redisson 客户端和持锁线程所在进程还活着。

### Now edit this

打开文件：

`src\main\java\xiaowu\example\payment\application\port\DistributedLockExecutor.java`

在现有方法下面追加这个方法：

```java
  <T> T executeWithWatchdogLock(
      String lockKey,
      Duration waitTime,
      Supplier<T> task);
```

改完后接口应该类似这样：

```java
public interface DistributedLockExecutor {
  <T> T executeWithLock(
      String lockKey,
      Duration waitTime,
      Duration leaseTime,
      Supplier<T> task);

  <T> T executeWithWatchdogLock(
      String lockKey,
      Duration waitTime,
      Supplier<T> task);
}
```

注意这里没有 `leaseTime` 参数，这是故意的。

### Checkpoint

这一步改完后，如果你直接编译，可能会看到类似错误：

```text
RedissonDistributedLockExecutor is not abstract and does not override abstract method executeWithWatchdogLock(...)
```

这是预期的，说明接口已经生效，下一步我们要去实现它。

你也可以先只用 IDE 看一下接口有没有语法错误。

### Common mistake

1. 不要给 watchdog 方法加 `leaseTime`，否则语义又回到固定租约。
2. 不要把方法命名成 `executeWithLock2` 这种临时名字，接口名要表达业务语义。
3. 不要删除原来的 `executeWithLock`，固定租约模式以后仍然可能有用。

### Next step preview

下一步我们会去 `RedissonDistributedLockExecutor` 里实现这个方法，关键是调用：

```java
lock.tryLock(waitTime.toMillis(), TimeUnit.MILLISECONDS)
```
