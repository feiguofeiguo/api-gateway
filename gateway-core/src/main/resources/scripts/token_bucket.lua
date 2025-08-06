-- token_bucket.lua
local key = KEYS[1]
local now = tonumber(ARGV[1])
local capacity = tonumber(ARGV[2])
local rate = tonumber(ARGV[3])
local expire_seconds = tonumber(ARGV[4])

local bucket = redis.call('HMGET', key, 'tokens', 'lastRefillTime')
local tokens = capacity
local lastRefillTime = now

if bucket[1] and bucket[2] then
    tokens = tonumber(bucket[1])
    lastRefillTime = tonumber(bucket[2])
    local delta = now - lastRefillTime
    if delta > 0 then
        local addTokens = (delta / 1000) * rate
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
