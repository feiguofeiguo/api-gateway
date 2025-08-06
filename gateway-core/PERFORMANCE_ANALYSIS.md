# 限流性能分析指南

## 概述

本文档说明如何通过细粒度计时来分析限流器的性能瓶颈，找出最耗时的操作。

## 计时点说明

### 1. RateLimitFilter 计时点

在 `RateLimitFilter.execute()` 方法中添加了以下计时点：

- **JWT处理**：`JwtValidator.extractJwt()` 和 `JwtValidator.parseUserIdFromJwt()` 的耗时
- **配置获取**：`configService.getConfig()` 的耗时
- **限流器执行**：`tokenBucketRateLimiter.allowRequest()` 或 `slidingWindowRateLimiter.allowRequest()` 的耗时
- **总耗时**：整个RateLimitFilter的总耗时

### 2. 限流器计时点

在 `TokenBucketRateLimiter` 和 `SlidingWindowRateLimiter` 中添加了：

- **限流器总耗时**：包括参数准备和Lua脚本执行的完整耗时

### 3. Lua脚本计时点

在 `LuaScriptManager` 中添加了：

- **Lua脚本执行耗时**：Redis Lua脚本的实际执行时间

## 日志输出格式

### DEBUG级别日志（细粒度计时）

```
req_xxx-【JWT处理】耗时: xxx ns
req_xxx-【配置获取】耗时: xxx ns
req_xxx-【限流器执行】耗时: xxx ns, 约 xxx us
【TokenBucketRateLimiter】总耗时: xxx ns, 约 xxx us
【Lua脚本-令牌桶】耗时: xxx ns, 约 xxx us
```

### WARN级别日志（总体计时）

```
req_xxx-【RateLimitFilter】总耗时: xxx ns, 约 xxx us, xxx ms
```

## 性能分析步骤

### 1. 启动应用

确保日志级别设置为DEBUG：

```yaml
logging:
  level:
    com.bank.gateway.filter.ratelimit: debug
```

### 2. 发送测试请求

向网关发送多个请求，观察日志输出。

### 3. 分析耗时分布

根据日志输出，分析各个阶段的耗时：

- **JWT处理**：通常很快（微秒级）
- **配置获取**：通常很快（微秒级）
- **限流器执行**：这是主要的性能瓶颈
- **Lua脚本执行**：这是Redis网络I/O的耗时

### 4. 性能瓶颈识别

#### 如果Lua脚本执行耗时很高（>1000us）
- **问题**：Redis网络延迟或Redis服务器性能问题
- **解决**：
  - 检查Redis服务器性能
  - 优化网络连接
  - 考虑Redis集群

#### 如果限流器总耗时很高但Lua脚本耗时正常
- **问题**：参数准备或序列化开销
- **解决**：
  - 优化参数准备逻辑
  - 减少不必要的对象创建

#### 如果JWT处理耗时很高
- **问题**：JWT解析性能问题
- **解决**：
  - 优化JWT解析逻辑
  - 考虑缓存JWT解析结果

## 预期性能指标

### 优化前（多次Redis操作）
- **总耗时**：1-2ms
- **Lua脚本执行**：N/A（未使用Lua脚本）
- **网络请求次数**：3次

### 优化后（单次Lua脚本）
- **总耗时**：几十微秒到几百微秒
- **Lua脚本执行**：几十微秒
- **网络请求次数**：1次

## 性能监控建议

### 1. 关键指标监控

- **平均响应时间**：目标 < 500微秒
- **95%响应时间**：目标 < 1毫秒
- **99%响应时间**：目标 < 2毫秒
- **错误率**：目标 < 0.1%

### 2. 告警设置

- **响应时间超过1ms**：警告
- **响应时间超过2ms**：严重
- **Lua脚本执行失败**：严重

### 3. 日志分析

定期分析日志，识别性能趋势：

```bash
# 统计平均响应时间
grep "【RateLimitFilter】总耗时" application.log | awk '{sum+=$NF} END {print "平均响应时间: " sum/NR " ms"}'

# 统计Lua脚本执行时间
grep "【Lua脚本" application.log | awk '{sum+=$NF} END {print "平均Lua执行时间: " sum/NR " us"}'
```

## 故障排查

### 1. 性能突然下降

**可能原因**：
- Redis服务器负载过高
- 网络延迟增加
- 内存不足

**排查步骤**：
1. 检查Redis服务器状态
2. 监控网络延迟
3. 检查系统资源使用情况

### 2. Lua脚本执行失败

**可能原因**：
- Redis版本不支持Lua脚本
- 脚本语法错误
- 参数类型错误

**排查步骤**：
1. 检查Redis版本（需要2.6+）
2. 验证Lua脚本语法
3. 检查参数类型转换

### 3. 响应时间不稳定

**可能原因**：
- 网络抖动
- Redis连接池配置不当
- 系统负载波动

**排查步骤**：
1. 监控网络质量
2. 调整Redis连接池配置
3. 检查系统负载

## 总结

通过细粒度计时，我们可以：

1. **精确定位性能瓶颈**：找出最耗时的操作
2. **验证优化效果**：对比优化前后的性能差异
3. **持续监控性能**：及时发现性能问题
4. **指导进一步优化**：根据耗时分布确定优化方向

这种分析方法帮助我们实现了从毫秒级到微秒级的性能提升。 