-- 滑动窗口算法 Lua 脚本
-- KEYS[1]: Redis key
-- ARGV[1]: 当前时间戳 (毫秒)
-- ARGV[2]: 窗口大小 (毫秒)
-- ARGV[3]: 阈值 (最大请求数)
-- ARGV[4]: 过期时间 (秒)

local key = KEYS[1]
local now = tonumber(ARGV[1])
local window_millis = tonumber(ARGV[2])
local threshold = tonumber(ARGV[3])
local expire_seconds = tonumber(ARGV[4])

-- 计算窗口开始时间
local min_time = now - window_millis

-- 移除窗口外的请求
redis.call('ZREMRANGEBYSCORE', key, 0, min_time)

-- 统计窗口内请求数
local count = redis.call('ZCARD', key)

-- 检查是否超过阈值
if count < threshold then
    -- 允许请求，记录本次请求
    redis.call('ZADD', key, now, tostring(now))
    redis.call('EXPIRE', key, expire_seconds)
    return 1  -- 允许请求
else
    return 0  -- 拒绝请求
end 