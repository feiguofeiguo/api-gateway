package com.bank.gateway.controller;

import com.bank.gateway.filter.ratelimit.PerformanceTest;
import com.bank.gateway.filter.ratelimit.RedisPerformanceTest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/test")
public class PerformanceTestController {

    @Autowired
    private PerformanceTest performanceTest;

    @Autowired
    private RedisPerformanceTest redisPerformanceTest;

    @GetMapping("/performance")
    public String runPerformanceTest() {
        log.info("开始执行性能测试...");
        performanceTest.runPerformanceTest();
        return "性能测试完成，请查看日志";
    }

    @GetMapping("/redis-performance")
    public String runRedisPerformanceTest() {
        log.info("开始执行Redis性能测试...");
        redisPerformanceTest.runFullPerformanceTest();
        return "Redis性能测试完成，请查看日志";
    }
} 