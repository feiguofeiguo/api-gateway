-- 固定窗口限流 Lua 脚本
-- KEYS[1]: key
-- ARGV[1]: 阈值
-- ARGV[2]: 窗口大小（秒）

local key = KEYS[1]
local threshold = tonumber(ARGV[1])
local window_seconds = tonumber(ARGV[2])

-- 当前计数
local current = redis.call('INCR', key)

if current == 1 then
    -- 第一次触发，设置过期时间
    redis.call('EXPIRE', key, window_seconds)
end

if current > threshold then
    return 0  -- 超过限流阈值
else
    return 1  -- 允许请求
end
