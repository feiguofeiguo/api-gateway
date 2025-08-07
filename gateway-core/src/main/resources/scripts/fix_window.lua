-- 优化版本：减少命令调用次数
local current = redis.call('INCR', KEYS[1])
if current == 1 or redis.call('TTL', KEYS[1]) == -1 then
    redis.call('EXPIRE', KEYS[1], ARGV[2])
end
return current <= tonumber(ARGV[1]) and 1 or 0