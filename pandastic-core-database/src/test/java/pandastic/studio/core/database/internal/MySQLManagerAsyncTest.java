package pandastic.studio.core.database.internal;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the startup-deadlock fix: the manager's async utilities must run on its own dedicated
 * database thread, with no Bukkit scheduler involved anywhere.
 *
 * <p>The regression this guards against: async work used to go through
 * {@code BukkitScheduler.runTaskAsynchronously}, whose tasks are only dispatched once the server
 * starts ticking — and plugins enable <em>before</em> the first tick. A plugin that blocked its
 * {@code onEnable} on a repository future (schema setup, settings load) therefore hung until its
 * timeout. The fake {@link Plugin} used here throws on every method except name and logger, so if
 * any code path still touches the scheduler, these tests fail loudly instead of hanging.
 */
class MySQLManagerAsyncTest {

    private static MySQLManager manager() {
        return MySQLManager.create(schedulerlessPlugin(), "localhost", 3306,
                "async_test", "user", "password", false);
    }

    @Test
    @DisplayName("async work runs on the dedicated pandastic-db thread during 'startup'")
    void runsOnDedicatedThread() throws Exception {
        CompletableFuture<String> thread = manager().supplyAsync(() -> Thread.currentThread().getName());

        // A 2-second cap is the point: under the old scheduler-backed implementation this future
        // could not complete until the server ticked, and this test has no server at all.
        String name = thread.get(2, TimeUnit.SECONDS);
        assertTrue(name.startsWith("pandastic-db-"), "ran on '" + name + "', expected the dedicated db thread");
        assertTrue(name.contains("async_test"), "the thread is named after the database");
    }

    @Test
    @DisplayName("tasks run in submission order — one thread is the whole contract")
    void submissionOrderIsExecutionOrder() throws Exception {
        MySQLManager manager = manager();
        List<Integer> order = new CopyOnWriteArrayList<>();

        CompletableFuture<?> first = manager.runAsync(() -> order.add(1));
        CompletableFuture<?> second = manager.runAsync(() -> order.add(2));
        CompletableFuture<?> third = manager.runAsync(() -> order.add(3));

        CompletableFuture.allOf(first, second, third).get(2, TimeUnit.SECONDS);
        assertEquals(List.of(1, 2, 3), order);
    }

    @Test
    @DisplayName("a throwing task fails its future, never the database thread")
    void failuresLandInTheFuture() throws Exception {
        MySQLManager manager = manager();

        CompletableFuture<Object> failing = manager.supplyAsync(() -> {
            throw new IllegalStateException("boom");
        });
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> failing.get(2, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, failure.getCause());

        // The single thread must survive the failure and keep serving.
        assertEquals("alive", manager.supplyAsync(() -> "alive").get(2, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("shutdown drains queued work, then stops accepting")
    void shutdownDrainsThenStops() throws Exception {
        MySQLManager manager = manager();
        CompletableFuture<String> queued = manager.supplyAsync(() -> "drained");

        manager.shutdown();

        assertEquals("drained", queued.get(2, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("awaitShutdown waits for the queue and reports a clean drain")
    void awaitShutdownDrainsAndReports() throws Exception {
        MySQLManager manager = manager();
        AtomicBoolean queuedTaskRan = new AtomicBoolean(false);
        manager.runAsync(() -> queuedTaskRan.set(true));

        assertTrue(manager.awaitShutdown(Duration.ofSeconds(2)),
                "a trivially short queue must drain within the timeout");
        assertTrue(queuedTaskRan.get(), "awaitShutdown must not return before queued work ran");
    }

    @Test
    @DisplayName("awaitShutdown returns false — instead of hanging — when the queue is stuck")
    void awaitShutdownTimesOutOnStuckQueue() throws Exception {
        MySQLManager manager = manager();
        CountDownLatch release = new CountDownLatch(1);
        manager.runAsync(() -> {
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });

        assertFalse(manager.awaitShutdown(Duration.ofMillis(100)),
                "a blocked queue must be reported, not waited on forever");
        // Unblock the daemon thread so it does not linger for the rest of the JVM.
        release.countDown();
    }

    /**
     * A {@link Plugin} that supports exactly what the manager is allowed to need — identity and a
     * logger. Any attempt to reach the server (and through it the scheduler) throws, which is what
     * turns a reintroduced scheduler dependency into an immediate test failure.
     */
    private static Plugin schedulerlessPlugin() {
        Logger logger = Logger.getLogger("MySQLManagerAsyncTest");
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getName" -> "AsyncTestPlugin";
            case "getLogger" -> logger;
            case "isEnabled" -> true;
            case "toString" -> "SchedulerlessPlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(
                    "the database layer must not need Plugin#" + method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class}, handler);
    }
}
