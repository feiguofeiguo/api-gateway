package com.bank.gateway.filter.auth;


import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@Slf4j
@Component
public class IpWhitelistValidator {
    private static final Set<String> WHITELIST = Collections.unmodifiableSet(
            // TODO-数据 后期从数据库中读取
            new HashSet<>(Arrays.asList("127.0.0.1", "192.168.1.100","0.0.0.0.0.0.0.1"))
    );

    public void validate(String ip) throws AuthException {
        log.debug("clientIp: {}", ip);
        if (!WHITELIST.contains(ip)) {
            throw new AuthException("IP not allowed: " + ip);
        }
    }
}