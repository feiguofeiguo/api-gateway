package com.bank.gateway.filter.auth;

import io.netty.handler.codec.http.FullHttpRequest;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

@Slf4j
public class SignatureValidator {
    private static final String SIGN_HEADER = "X-SIGNATURE";
    private static final String SECRET = "SECRET_1530"; // TODO: 从配置读取

    public void validate(FullHttpRequest request) throws AuthException {
        String sign = request.headers().get(SIGN_HEADER);
        log.debug("sign: {}", sign);
        String body = request.content().toString(io.netty.util.CharsetUtil.UTF_8);
        log.debug("body: {}", body);
        String expectedSign = hmacSha256(body, SECRET);
        log.debug("expectedSign: {}", expectedSign);
        if (sign == null || !sign.equals(expectedSign)) {
            throw new AuthException("Invalid Signature");
        }
    }

    private String hmacSha256(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes());
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("HMAC error", e);
        }
    }
}
