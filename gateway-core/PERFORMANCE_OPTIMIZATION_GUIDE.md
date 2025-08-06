# 限流性能优化指南

## 当前性能分析

根据测试结果，当前性能表现如下：

### 📊 性能指标
- **Lua脚本执行时间**：400-2100微秒（0.4-2.1ms）
- **限流器总耗时**：435-14358微秒（0.4-14.4ms）
- **性能波动**：响应时间不稳定，差异很大

### 🚨 问题识别

1. **性能未达预期**：应该降到几十微秒，但实际还在毫秒级
2. **响应时间不稳定**：从400微秒到2100微秒，波动很大
3. **并发性能问题**：高并发时性能下降明显

## 优化方案

### 1. Redis连接池优化

已优化Redis连接池配置：

```yaml
spring:
  redis:
    timeout: 2000ms
    lettuce:
      pool:
        max-active: 32      # 最大连接数
        max-idle: 16        # 最大空闲连接
        min-idle: 8         # 最小空闲连接
        max-wait: 1000ms    # 最大等待时间
    connect-timeout: 1000ms # 连接超时
```

### 2. Lua脚本优化

当前Lua脚本可能存在性能问题，建议优化：

#### 令牌桶脚本优化
```lua
-- 优化版本：减少不必要的计算
local key = KEYS[1]
local now = tonumber(ARGV[1])
local capacity = tonumber(ARGV[2])
local rate = tonumber(ARGV[3])
local expire_seconds = tonumber(ARGV[4])

-- 使用更高效的数据结构
local bucket = redis.call('HMGET', key, 'tokens', 'lastRefillTime')
local tokens = capacity
local lastRefillTime = now

if bucket[1] then
    tokens = tonumber(bucket[1])
    lastRefillTime = tonumber(bucket[2])
    
    -- 优化令牌补充计算
    local delta = math.floor((now - lastRefillTime) / 1000)
    if delta > 0 then
        local addTokens = math.floor(delta * rate)
        tokens = math.min(capacity, tokens + addTokens)
        lastRefillTime = now
    end
end

if tokens > 0 then
    tokens = tokens - 1
    redis.call('HMSET', key, 'tokens', tokens, 'lastRefillTime', lastRefillTime)
    redis.call('EXPIRE', key, expire_seconds)
    return 1
else
    redis.call('HMSET', key, 'tokens', tokens, 'lastRefillTime', lastRefillTime)
    redis.call('EXPIRE', key, expire_seconds)
    return 0
end
```

### 3. 网络优化

#### 检查网络延迟
```bash
# 测试Redis网络延迟
redis-cli --latency
redis-cli --latency-history
redis-cli --latency-dist
```

#### 优化网络配置
```bash
# 调整TCP参数
echo 'net.core.rmem_max = 16777216' >> /etc/sysctl.conf
echo 'net.core.wmem_max = 16777216' >> /etc/sysctl.conf
echo 'net.ipv4.tcp_rmem = 4096 87380 16777216' >> /etc/sysctl.conf
echo 'net.ipv4.tcp_wmem = 4096 65536 16777216' >> /etc/sysctl.conf
sysctl -p
```

### 4. Redis服务器优化

#### Redis配置优化
```conf
# redis.conf
maxmemory 2gb
maxmemory-policy allkeys-lru
save ""
appendonly no
tcp-keepalive 300
tcp-backlog 511
```

#### 系统级优化
```bash
# 禁用透明大页
echo never > /sys/kernel/mm/transparent_hugepage/enabled
echo never > /sys/kernel/mm/transparent_hugepage/defrag

# 调整文件描述符限制
echo '* soft nofile 65536' >> /etc/security/limits.conf
echo '* hard nofile 65536' >> /etc/security/limits.conf
```

### 5. 应用级优化

#### 连接池预热
```java
@PostConstruct
public void warmUpConnectionPool() {
    // 预热连接池
    for (int i = 0; i < 10; i++) {
        redisTemplate.opsForValue().get("warmup");
    }
}
```

#### 异步处理
```java
// 考虑使用异步处理非关键路径
@Async
public CompletableFuture<Boolean> allowRequestAsync(String key, LimitConfig config) {
    // 异步限流检查
}
```

## 性能测试工具

### 1. Redis性能测试
```bash
# 访问测试接口
curl http://localhost:8080/api/test/redis-performance
```

### 2. 限流性能测试
```bash
# 访问测试接口
curl http://localhost:8080/api/test/performance
```

### 3. 手动性能测试
```bash
# 使用redis-benchmark测试Redis性能
redis-benchmark -h 127.0.0.1 -p 6379 -n 100000 -c 50
```

## 监控指标

### 1. 关键指标
- **平均响应时间**：目标 < 500微秒
- **95%响应时间**：目标 < 1毫秒
- **99%响应时间**：目标 < 2毫秒
- **错误率**：目标 < 0.1%

### 2. 监控命令
```bash
# 监控Redis性能
redis-cli info stats
redis-cli info memory
redis-cli info clients

# 监控网络延迟
ping redis-server
```

## 故障排查

### 1. 性能突然下降
**排查步骤**：
1. 检查Redis服务器负载
2. 监控网络延迟
3. 检查连接池状态
4. 查看系统资源使用情况

### 2. 响应时间不稳定
**排查步骤**：
1. 检查网络质量
2. 监控Redis服务器性能
3. 调整连接池配置
4. 检查系统负载

### 3. Lua脚本执行失败
**排查步骤**：
1. 检查Redis版本
2. 验证脚本语法
3. 检查参数类型
4. 查看Redis错误日志

## 预期优化效果

### 优化前
- **平均响应时间**：1-2ms
- **性能波动**：很大
- **并发能力**：有限

### 优化后（目标）
- **平均响应时间**：< 500微秒
- **性能稳定性**：< 20%波动
- **并发能力**：提升5倍以上

## 总结

通过以上优化方案，我们期望将限流性能从毫秒级提升到微秒级，同时提高系统的稳定性和并发处理能力。建议按优先级逐步实施这些优化措施。 