package com.bank.gateway.filter.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class GlobalRateLimiter {
    
    @Autowired
    private StringRedisTemplate redisTemplate;
    
    @Autowired
    private RateLimitConfigService configService;
    
    // 服务ID到本地计数器的映射
    private final ConcurrentHashMap<String, ServiceRateLimiter> serviceLimiters = new ConcurrentHashMap<>();
    
    // 定时任务执行器
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    
    @PostConstruct
    public void init() {
        // 启动定时任务，每秒与Redis同步一次
        scheduler.scheduleAtFixedRate(this::syncWithRedis, 1, 1, TimeUnit.SECONDS);
        log.info("GlobalRateLimiter initialized and started sync scheduler");
    }
    
    /**
     * 检查请求是否允许通过本地限流器
     * @param serviceId 服务ID
     * @return 是否允许通过
     */
    public boolean allowRequest(String serviceId) {
        log.debug("===call GlobalRateLimiter.allowRequest===");
        // 获取或创建服务限流器
        ServiceRateLimiter limiter = serviceLimiters.computeIfAbsent(serviceId, 
                s -> new ServiceRateLimiter());
        
        return limiter.tryAcquire();
    }
    
    /**
     * 与Redis同步配额
     */
    private void syncWithRedis() {
        try {
            // 遍历所有服务的限流器，进行同步
            for (String serviceId : serviceLimiters.keySet()) {
                ServiceRateLimiter limiter = serviceLimiters.get(serviceId);
                if (limiter != null) {
                    syncServiceWithRedis(serviceId, limiter);
                }
            }
        } catch (Exception e) {
            log.error("Error syncing with Redis", e);
        }
    }
    
    /**
     * 同步单个服务的配额
     * @param serviceId 服务ID
     * @param limiter 服务限流器
     */
    private void syncServiceWithRedis(String serviceId, ServiceRateLimiter limiter) {
        try {
            // 获取服务的限流配置
            RateLimitConfigService.LimitConfig config = configService.getConfig(serviceId);
            
            // 根据不同限流策略获取配额
            int quota = 0;
            if (config.getType() == RateLimitEnum.TOKEN_BUCKET) {
                quota = config.getTkbRate(); // 每秒令牌数
            } else if (config.getType() == RateLimitEnum.SLIDING_WINDOW) {
                // 滑动窗口的阈值除以窗口大小得到每秒配额
                quota = config.getSlwThreshold() / Math.max(1, config.getSlwWindow());
            } else if (config.getType() == RateLimitEnum.FIXED_WINDOW) {
                // 固定窗口的阈值除以窗口大小得到每秒配额
                quota = config.getSlwThreshold() / Math.max(1, config.getSlwWindow());   //此处可以成功获取
            }
            
            // 原子性地更新下一周期配额
            limiter.updateNextPeriodQuota(quota);  //相当于每s都重置一下，每次都是相同的起始值
            
            // 上报本周期使用量到Redis
            String redisKey = "rate_limit:usage:" + serviceId;
            int currentUsage = limiter.getCurrentPeriodUsage();
            redisTemplate.opsForValue().increment(redisKey, currentUsage);
            redisTemplate.expire(redisKey, 10, TimeUnit.SECONDS); // 10秒过期
            
            log.debug("Synced service {} with Redis: quota={}, usage={}", serviceId, quota, currentUsage);
        } catch (Exception e) {
            log.error("Error syncing service {} with Redis", serviceId, e);
        }
    }
    
    /**
     * 服务限流器内部类
     */
    private static class ServiceRateLimiter {
        // 当前周期已接受连接数
        private final AtomicInteger currentPeriodCount = new AtomicInteger(0);
        
        // 下一周期允许连接数
        private final AtomicInteger nextPeriodQuota = new AtomicInteger(0);
        
        // 本地最大连接数阈值（基于下一周期配额和节点处理能力）
        private volatile int localMaxPerSecond = 100; // 默认值
        
        /**
         * 尝试获取许可
         * @return 是否允许通过
         */
        public boolean tryAcquire() {
            // 检查当前周期计数是否超过本地阈值
            int current = currentPeriodCount.incrementAndGet();
            log.debug("Current period count is {}", current);
            if (current <= localMaxPerSecond) {
                return true;
            }
            
            // 超过阈值，拒绝请求
            currentPeriodCount.decrementAndGet(); // 回退计数
            return false;
        }
        
        /**
         * 更新下一周期配额
         * @param quota 配额
         */
        public void updateNextPeriodQuota(int quota) {
            // 更新下一周期配额
            int oldQuota = nextPeriodQuota.getAndSet(quota);
            
            // 根据配额和节点处理能力设置本地最大值
            // 这里假设单节点能处理的请求不超过全局配额的50%
            localMaxPerSecond = Math.max(1, quota / 2);
            
            // 重置当前周期计数
            currentPeriodCount.set(0);
            
            log.debug("Updated quota from {} to {}, localMaxPerSecond: {}", oldQuota, quota, localMaxPerSecond);
        }
        
        /**
         * 获取当前周期使用量
         * @return 使用量
         */
        public int getCurrentPeriodUsage() {
            return currentPeriodCount.get();
        }
    }
}