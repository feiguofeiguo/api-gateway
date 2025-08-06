package com.bank.gateway.filter.ratelimit;

import com.bank.gateway.filter.ratelimit.ratelimitImpl.TokenBucketRateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class PerformanceTest {

    @Autowired
    private TokenBucketRateLimiter tokenBucketRateLimiter;

    @Autowired
    private RateLimitConfigService configService;

    /**
     * 性能测试：对比优化前后的性能
     */
    public void runPerformanceTest() {
        log.info("开始性能测试...");
        
        // 创建测试配置
        RateLimitConfigService.LimitConfig config = new RateLimitConfigService.LimitConfig();
        config.setType(RateLimitEnum.TOKEN_BUCKET);
        config.setTkbCapacity(100);
        config.setTkbRate(10);
        
        // 测试参数
        int threadCount = 50;
        int requestsPerThread = 100;
        String testKey = "test_user_1";
        
        // 执行测试
        long startTime = System.currentTimeMillis();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < requestsPerThread; j++) {
                        long requestStart = System.nanoTime();
                        boolean allowed = tokenBucketRateLimiter.allowRequest(testKey + "_" + threadId, config);
                        long requestEnd = System.nanoTime();
                        
                        if (allowed) {
                            successCount.incrementAndGet();
                        } else {
                            failCount.incrementAndGet();
                        }
                        
                        // 记录单次请求耗时
                        if (j % 20 == 0) {
                            long duration = requestEnd - requestStart;
                            log.debug("线程{} 请求{} 耗时: {} ns ({} us)", 
                                    threadId, j, duration, duration / 1000.0);
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
        
        long endTime = System.currentTimeMillis();
        long totalTime = endTime - startTime;
        int totalRequests = threadCount * requestsPerThread;
        
        log.info("性能测试结果:");
        log.info("总请求数: {}", totalRequests);
        log.info("成功请求数: {}", successCount.get());
        log.info("失败请求数: {}", failCount.get());
        log.info("总耗时: {} ms", totalTime);
        log.info("平均QPS: {:.2f}", (double) totalRequests / totalTime * 1000);
        log.info("平均响应时间: {:.2f} ms", (double) totalTime / totalRequests);
    }
} 