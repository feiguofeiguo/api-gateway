package com.bank.gateway.filter.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class RedisPerformanceTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private LuaScriptManager luaScriptManager;

    /**
     * 测试Redis基础操作性能
     */
    public void testRedisBasicOperations() {
        log.info("开始Redis基础操作性能测试...");
        
        int testCount = 1000;
        AtomicLong totalTime = new AtomicLong(0);
        AtomicLong minTime = new AtomicLong(Long.MAX_VALUE);
        AtomicLong maxTime = new AtomicLong(0);
        
        for (int i = 0; i < testCount; i++) {
            String key = "test:basic:" + i;
            String value = "value" + i;
            
            long start = System.nanoTime();
            redisTemplate.opsForValue().set(key, value);
            long end = System.nanoTime();
            
            long duration = end - start;
            totalTime.addAndGet(duration);
            minTime.updateAndGet(v -> Math.min(v, duration));
            maxTime.updateAndGet(v -> Math.max(v, duration));
            
            if (i % 100 == 0) {
                log.debug("基础操作测试进度: {}/{}", i, testCount);
            }
        }
        
        long avgTime = totalTime.get() / testCount;
        log.info("Redis基础操作性能测试结果:");
        log.info("测试次数: {}", testCount);
        log.info("平均耗时: {} ns ({} us)", avgTime, avgTime / 1000.0);
        log.info("最小耗时: {} ns ({} us)", minTime.get(), minTime.get() / 1000.0);
        log.info("最大耗时: {} ns ({} us)", maxTime.get(), maxTime.get() / 1000.0);
    }

    /**
     * 测试Lua脚本执行性能
     */
    public void testLuaScriptPerformance() {
        log.info("开始Lua脚本性能测试...");
        
        int testCount = 1000;
        AtomicLong totalTime = new AtomicLong(0);
        AtomicLong minTime = new AtomicLong(Long.MAX_VALUE);
        AtomicLong maxTime = new AtomicLong(0);
        
        for (int i = 0; i < testCount; i++) {
            String key = "test:lua:" + i;
            long now = System.currentTimeMillis();
            
            long start = System.nanoTime();
            boolean result = luaScriptManager.executeTokenBucket(key, now, 100, 10, 120);
            long end = System.nanoTime();
            
            long duration = end - start;
            totalTime.addAndGet(duration);
            minTime.updateAndGet(v -> Math.min(v, duration));
            maxTime.updateAndGet(v -> Math.max(v, duration));
            
            if (i % 100 == 0) {
                log.debug("Lua脚本测试进度: {}/{}", i, testCount);
            }
        }
        
        long avgTime = totalTime.get() / testCount;
        log.info("Lua脚本性能测试结果:");
        log.info("测试次数: {}", testCount);
        log.info("平均耗时: {} ns ({} us)", avgTime, avgTime / 1000.0);
        log.info("最小耗时: {} ns ({} us)", minTime.get(), minTime.get() / 1000.0);
        log.info("最大耗时: {} ns ({} us)", maxTime.get(), maxTime.get() / 1000.0);
    }

    /**
     * 测试并发Lua脚本执行性能
     */
    public void testConcurrentLuaScriptPerformance() {
        log.info("开始并发Lua脚本性能测试...");
        
        int threadCount = 20;
        int requestsPerThread = 50;
        int totalRequests = threadCount * requestsPerThread;
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        
        AtomicLong totalTime = new AtomicLong(0);
        AtomicLong minTime = new AtomicLong(Long.MAX_VALUE);
        AtomicLong maxTime = new AtomicLong(0);
        AtomicLong successCount = new AtomicLong(0);
        AtomicLong failCount = new AtomicLong(0);
        
        long testStart = System.currentTimeMillis();
        
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < requestsPerThread; j++) {
                        String key = "test:concurrent:" + threadId + ":" + j;
                        long now = System.currentTimeMillis();
                        
                        long start = System.nanoTime();
                        try {
                            boolean result = luaScriptManager.executeTokenBucket(key, now, 100, 10, 120);
                            long end = System.nanoTime();
                            
                            long duration = end - start;
                            totalTime.addAndGet(duration);
                            minTime.updateAndGet(v -> Math.min(v, duration));
                            maxTime.updateAndGet(v -> Math.max(v, duration));
                            successCount.incrementAndGet();
                        } catch (Exception e) {
                            failCount.incrementAndGet();
                            log.error("Lua脚本执行失败", e);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        executor.shutdown();
        
        long testEnd = System.currentTimeMillis();
        long totalTestTime = testEnd - testStart;
        
        long avgTime = totalTime.get() / successCount.get();
        double qps = (double) successCount.get() / totalTestTime * 1000;
        
        log.info("并发Lua脚本性能测试结果:");
        log.info("总请求数: {}", totalRequests);
        log.info("成功请求数: {}", successCount.get());
        log.info("失败请求数: {}", failCount.get());
        log.info("总测试时间: {} ms", totalTestTime);
        log.info("平均QPS: {:.2f}", qps);
        log.info("平均耗时: {} ns ({} us)", avgTime, avgTime / 1000.0);
        log.info("最小耗时: {} ns ({} us)", minTime.get(), minTime.get() / 1000.0);
        log.info("最大耗时: {} ns ({} us)", maxTime.get(), maxTime.get() / 1000.0);
    }

    /**
     * 运行完整性能测试
     */
    public void runFullPerformanceTest() {
        log.info("=== 开始完整Redis性能测试 ===");
        
        // 1. 基础操作测试
        testRedisBasicOperations();
        
        // 2. 单线程Lua脚本测试
        testLuaScriptPerformance();
        
        // 3. 并发Lua脚本测试
        testConcurrentLuaScriptPerformance();
        
        log.info("=== Redis性能测试完成 ===");
    }
} 