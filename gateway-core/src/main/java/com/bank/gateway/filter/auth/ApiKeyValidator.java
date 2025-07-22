package com.bank.gateway.filter.auth;

import io.netty.handler.codec.http.FullHttpRequest;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ApiKeyValidator {
    private static final String API_KEY_HEADER = "X-API-KEY";
    private static final String VALID_API_KEY = "API_KEY_"; // TODO: 从配置读取

    public void validate(FullHttpRequest request) throws AuthException {
        String apiKey = request.headers().get(API_KEY_HEADER);
        log.debug("apiKey: {}", apiKey);
        if (apiKey == null || !apiKey.startsWith(VALID_API_KEY)) {   //暂时：只要满足这个模式，都算合法
            throw new AuthException("Invalid API Key");
        }
    }
}
