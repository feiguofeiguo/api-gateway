# 两级限流架构设计与实现

## 概述

本文档详细说明了API网关中两级限流架构的设计和实现。该架构旨在解决高并发场景下传统限流方案性能瓶颈问题，通过本地快速限流和全局周期性同步的方式，显著提升限流性能。

## 架构设计

### 总体架构

```
┌─────────────────┐    ┌──────────────────┐    ┌──────────────────┐
│   客户端请求     │───▶│  本地限流器(快速) │───▶│  全局限流器(精确) │
│                │    │ (RateLimitHandler)│    │ (RateLimitFilter)│
└─────────────────┘    └──────────────────┘    └──────────────────┘
                              │                         │
                              ▼                         ▼
                       ┌─────────────┐          ┌─────────────┐
                       │   内存计数   │◀─┐      │    Redis     │
                       └─────────────┘  │      └─────────────┘
                                        │            ▲
                              ┌─────────────┐        │
                              │ 定时同步任务 │────────┘
                              └─────────────┘
```

### 核心组件

#### 1. RateLimitHandler（本地限流器）
- **位置**：Netty Pipeline中，位于HttpObjectAggregator之后，PluginDispatcherHandler之前
- **功能**：
  - 快速本地计数检查
  - 立即拒绝明显超出阈值的请求
  - 不访问Redis，避免网络延迟

#### 2. GlobalRateLimiter（全局限流控制器）
- **功能**：
  - 管理各服务的本地限流器
  - 定期与Redis同步配额和使用量
  - 维护本地计数器和阈值

#### 3. RateLimitFilter（全局限流器）
- **位置**：作为插件链中的一环
- **功能**：
  - 处理边缘情况和特殊需求
  - 在必要时进行精确的全局限流检查
  - 使用Lua脚本优化的Redis操作

## 工作流程

### 1. 初始化阶段
1. GlobalRateLimiter启动定时任务（每秒执行一次）
2. 从Redis获取各服务的初始配额
3. 初始化本地计数器

### 2. 请求处理流程
1. 请求到达网关
2. RateLimitHandler进行本地快速检查：
   - 从URI中提取服务ID
   - 检查本地计数器是否超过阈值
   - 如果超过，立即返回429状态码
   - 如果未超过，递增计数器并继续处理
3. 请求进入插件链，由RateLimitFilter根据策略决定是否进行全局精确限流检查

### 3. 定期同步任务
1. 每秒执行一次同步任务
2. 上报各服务本周期使用量到Redis
3. 从Redis获取下一周期配额
4. 更新本地限流器阈值
5. 重置当前周期计数器

## 技术实现细节

### 本地限流算法
```java
public boolean tryAcquire() {
    // 原子性递增并检查是否超过阈值
    int current = currentPeriodCount.incrementAndGet();
    if (current <= localMaxPerSecond) {
        return true;
    }
    
    // 超过阈值，回退计数并拒绝
    currentPeriodCount.decrementAndGet();
    return false;
}
```

### 全局同步机制
```java
private void syncServiceWithRedis(String serviceId, ServiceRateLimiter limiter) {
    // 获取服务配置
    RateLimitConfigService.LimitConfig config = configService.getConfig(serviceId);
    
    // 根据配置计算配额
    int quota = calculateQuota(config);
    
    // 更新下一周期配额
    limiter.updateNextPeriodQuota(quota);
    
    // 上报使用量
    String redisKey = "rate_limit:usage:" + serviceId;
    int currentUsage = limiter.getCurrentPeriodUsage();
    redisTemplate.opsForValue().increment(redisKey, currentUsage);
}
    
### 全局限流策略决策
```java
private boolean shouldPerformGlobalCheck(String serviceId, RateLimitConfigService.LimitConfig config) {
    // 只在特殊情况下进行全局检查，避免每个请求都访问Redis
    // 例如对于关键服务或低阈值服务进行精确控制
    return false; // 默认不进行全局检查
}
```

## 性能优化

### 1. 减少Redis访问
- 本地限流器完全在内存中操作，不访问Redis
- 全局限流器每秒仅与Redis交互一次
- RateLimitFilter只在必要时访问Redis
- 使用Lua脚本减少网络请求次数

### 2. 非阻塞设计
- 本地限流检查不阻塞Netty线程
- Redis同步操作在独立线程中执行
- 保持Netty的高性能异步特性

### 3. 内存优化
- 使用原子计数器避免锁竞争
- 及时重置周期计数器
- 合理设置本地阈值避免内存溢出

## 配置说明

### 本地限流配置
```yaml
# 本地限流无需特殊配置，自动根据全局配额调整
```

### 全局限流配置
```yaml
ratelimit:
  strategy:
    order-service:
      type: TOKEN_BUCKET
      TkbRate: 10
      TknCapacity: 20
    user-service:
      type: SLIDING_WINDOW
      SlwWindow: 60
      SlwThreshold: 100
```

## 监控与日志

### 关键指标
1. **本地限流拒绝率**：反映本地限流效果
2. **Redis同步成功率**：反映全局同步状态
3. **全局限流拒绝率**：反映精确限流效果

### 日志级别
```yaml
logging:
  level:
    com.bank.gateway.filter.ratelimit.GlobalRateLimiter: info
    com.bank.gateway.filter.ratelimit: debug
```

## 扩展性考虑

### 1. 动态配置
- 支持Nacos配置中心动态更新限流策略
- 实时调整各服务的限流参数

### 2. 集群部署
- 多节点共享Redis进行全局协调
- 使用节点标识避免计数冲突

### 3. 算法扩展
- 支持多种限流算法（令牌桶、滑动窗口等）
- 可插拔的限流策略实现

## 总结

两级限流架构通过将快速本地限流和精确全局限流相结合，有效解决了高并发场景下的性能瓶颈问题。本地限流器能够快速拒绝大部分明显超出阈值的请求，而全局限流器仅在必要时进行精确控制，避免了每个请求都访问Redis的问题。

这种设计既保证了限流的准确性，又大幅提升了系统的处理能力，同时减少了对后端存储系统的压力。