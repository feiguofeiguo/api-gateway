package com.bank.gateway.filter.ratelimit.ratelimitImpl;

import com.bank.gateway.filter.ratelimit.LuaScriptManager;
import com.bank.gateway.filter.ratelimit.RateLimitConfigService;
import com.bank.gateway.filter.ratelimit.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

@Slf4j
@Component("fixedWindowRateLimiter")
public class FixedWindowRateLimiter implements RateLimiter {

    private final LuaScriptManager luaScriptManager;
    private final ScheduledExecutorService timeoutExecutor;

    // Shared thread pool for timeout control
    private static final int TIMEOUT_POOL_SIZE = 4;

    private int timeOut=1;  //ms

    public FixedWindowRateLimiter(LuaScriptManager luaScriptManager) {
        this.luaScriptManager = luaScriptManager;
        this.timeoutExecutor = Executors.newScheduledThreadPool(
                TIMEOUT_POOL_SIZE,
                new ThreadFactory() {
                    private final AtomicInteger counter = new AtomicInteger(0);

                    @Override
                    public Thread newThread(Runnable r) {
                        Thread thread = new Thread(r);
                        thread.setName("rate-limit-timeout-" + counter.incrementAndGet());
                        thread.setDaemon(true);
                        return thread;
                    }
                }
        );
    }

    @Override
    public boolean allowRequest(String key, RateLimitConfigService.LimitConfig config) {
        long startTime = System.nanoTime();
        String redisKey = "fixed_window:" + key;
        int threshold = config.getSlwThreshold();
        int windowSeconds = config.getSlwWindow();
        long timeoutMs = timeOut > 0 ? timeOut : 500; // default 500ms

        // Create a future for the Redis operation
        CompletableFuture<Boolean> redisFuture = CompletableFuture.supplyAsync(() ->
                luaScriptManager.executeFixedWindow(redisKey, threshold, windowSeconds)
        );

        // Create a timeout future
        CompletableFuture<Boolean> timeoutFuture = new CompletableFuture<>();
        ScheduledFuture<?> timeoutTask = timeoutExecutor.schedule(
                () -> timeoutFuture.completeExceptionally(new TimeoutException()),
                timeoutMs,
                TimeUnit.MILLISECONDS
        );

        try {
            // Wait for either Redis operation or timeout
            Boolean result = redisFuture.applyToEither(timeoutFuture, Function.identity())
                    .exceptionally(ex -> {
                        if (ex instanceof TimeoutException) {
                            log.warn("[RateLimit] Timeout while checking limit for key: {}, timeout: {}ms",
                                    key, timeoutMs);
                            return false; // Default to deny when timeout
                        }
                        log.error("[RateLimit] Error checking limit for key: {}", key, ex);
                        return false; // Default to deny on error
                    })
                    .get(); // This will block until either completes

            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
            if (durationMs > timeoutMs / 2) {
                log.debug("[RateLimit] Slow limit check for key: {}, took {}ms", key, durationMs);
            }

            return result;
        } catch (Exception e) {
            log.error("[RateLimit] Unexpected error processing limit check for key: {}", key, e);
            return false;
        } finally {
            timeoutTask.cancel(true); // Cancel the timeout task if not already triggered
        }
    }

    @PreDestroy
    public void shutdown() {
        timeoutExecutor.shutdown();
        try {
            if (!timeoutExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                timeoutExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            timeoutExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}