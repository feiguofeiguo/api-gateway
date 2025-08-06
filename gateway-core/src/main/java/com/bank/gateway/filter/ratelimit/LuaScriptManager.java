package com.bank.gateway.filter.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
public class LuaScriptManager {

    private final StringRedisTemplate redisTemplate;

    private String tokenBucketSha1;
    private String slidingWindowSha1;
    private String fixedWindowSha1;

    public LuaScriptManager(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    public void init() {
        try {
            // 加载 Lua 脚本内容
            String tokenBucketScript = readInputStreamAsString(new ClassPathResource("scripts/token_bucket.lua"));
            String slidingWindowScript = readInputStreamAsString(new ClassPathResource("scripts/sliding_window.lua"));
            String fixedWindowScript = readInputStreamAsString(new ClassPathResource("scripts/fix_window.lua"));

            // Redis 预加载，获取 SHA1
            this.tokenBucketSha1 = redisTemplate.execute(new RedisCallback<String>() {
                @Override
                public String doInRedis(org.springframework.data.redis.connection.RedisConnection connection) {
                    return connection.scriptingCommands().scriptLoad(tokenBucketScript.getBytes(StandardCharsets.UTF_8));
                }
            });

            this.slidingWindowSha1 = redisTemplate.execute(new RedisCallback<String>() {
                @Override
                public String doInRedis(org.springframework.data.redis.connection.RedisConnection connection) {
                    return connection.scriptingCommands().scriptLoad(slidingWindowScript.getBytes(StandardCharsets.UTF_8));
                }
            });

            this.fixedWindowSha1 =redisTemplate.execute(new RedisCallback<String>() {
                @Override
                public String doInRedis(org.springframework.data.redis.connection.RedisConnection connection) {
                    return connection.scriptingCommands().scriptLoad(fixedWindowScript.getBytes(StandardCharsets.UTF_8));
                }
            });



            log.info("Lua 脚本加载成功，SHA1 => tokenBucket: {}, slidingWindow: {}", tokenBucketSha1, slidingWindowSha1);
        } catch (IOException e) {
            log.error("加载 Lua 脚本失败", e);
            throw new RuntimeException("加载 Lua 脚本失败", e);
        }
    }

    private String readInputStreamAsString(ClassPathResource resource) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append("\n");
            }
            return builder.toString();
        }
    }

    public boolean executeTokenBucket(String key, long now, int capacity, int rate, int expireSeconds) {
        List<String> keys = Collections.singletonList(key);
        byte[][] args = new byte[][]{
                String.valueOf(now).getBytes(StandardCharsets.UTF_8),
                String.valueOf(capacity).getBytes(StandardCharsets.UTF_8),
                String.valueOf(rate).getBytes(StandardCharsets.UTF_8),
                String.valueOf(expireSeconds).getBytes(StandardCharsets.UTF_8)
        };

        long start = System.nanoTime();
        Long result = redisTemplate.execute(new RedisCallback<Long>() {
            @Override
            public Long doInRedis(org.springframework.data.redis.connection.RedisConnection connection) {
                return connection.scriptingCommands().evalSha(
                        tokenBucketSha1.getBytes(StandardCharsets.UTF_8),
                        ReturnType.INTEGER,
                        1,
                        key.getBytes(StandardCharsets.UTF_8),
                        args[0], args[1], args[2], args[3]
                );
            }
        });


        long end = System.nanoTime();
        log.debug("【Lua-令牌桶】执行耗时: {} ns ≈ {} µs", (end - start), (end - start) / 1000.0);

        return result != null && result == 1L;
    }

    public boolean executeSlidingWindow(String key, long now, long windowMillis, int threshold, int expireSeconds) {
        List<String> keys = Collections.singletonList(key);
        byte[][] args = new byte[][]{
                String.valueOf(now).getBytes(StandardCharsets.UTF_8),
                String.valueOf(windowMillis).getBytes(StandardCharsets.UTF_8),
                String.valueOf(threshold).getBytes(StandardCharsets.UTF_8),
                String.valueOf(expireSeconds).getBytes(StandardCharsets.UTF_8)
        };

        long start = System.nanoTime();
        Long result = redisTemplate.execute(new RedisCallback<Long>() {
            @Override
            public Long doInRedis(org.springframework.data.redis.connection.RedisConnection connection) {
                return connection.scriptingCommands().evalSha(
                        slidingWindowSha1.getBytes(StandardCharsets.UTF_8),
                        ReturnType.INTEGER,
                        1,
                        key.getBytes(StandardCharsets.UTF_8),
                        args[0], args[1], args[2], args[3]
                );
            }
        });

        long end = System.nanoTime();
        log.debug("【Lua-滑动窗口】执行耗时: {} ns ≈ {} µs", (end - start), (end - start) / 1000.0);

        return result != null && result == 1L;
    }

    public boolean executeFixedWindow(String key, int threshold, int windowSeconds) {
        List<String> keys = Collections.singletonList(key);
        byte[][] args = new byte[][]{
                String.valueOf(threshold).getBytes(StandardCharsets.UTF_8),
                String.valueOf(windowSeconds).getBytes(StandardCharsets.UTF_8)
        };

        long start = System.nanoTime();

        Long result = redisTemplate.execute((RedisCallback<Long>) connection ->
                connection.scriptingCommands().evalSha(
                        fixedWindowSha1.getBytes(StandardCharsets.UTF_8),
                        ReturnType.INTEGER,
                        1,
                        key.getBytes(StandardCharsets.UTF_8),
                        args[0], args[1]
                )
        );

        long end = System.nanoTime();
        log.debug("【Lua-固定窗口】执行耗时: {} ns ≈ {} µs", (end - start), (end - start) / 1000.0);

        return result != null && result == 1L;
    }

}
