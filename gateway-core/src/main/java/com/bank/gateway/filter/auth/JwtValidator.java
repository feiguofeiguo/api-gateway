package com.bank.gateway.filter.auth;

import io.netty.handler.codec.http.FullHttpRequest;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JwtValidator {
    private static final String JWT_HEADER = "Authorization";
    private static final String JWT_SECRET = "JWT_SECRET_1557"; // TODO: 从配置读取

    //新签发jwt时
    private static final AttributeKey<String> NEW_JWT_KEY = AttributeKey.valueOf("jwt");

    public String extractJwt(FullHttpRequest request) throws AuthException {
        String authHeader = request.headers().get(JWT_HEADER);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.debug("Missing JWT");
            return "";
            //throw new AuthException("Missing JWT");
        }
        return authHeader.substring(7);
    }

    public void validate(String jwt) throws AuthException {
        log.debug("clientJWT: {}", jwt);
        try {
            Jwts.parser().setSigningKey(JWT_SECRET.getBytes()).parseClaimsJws(jwt);
        } catch (Exception e) {
            //TODO 如果过期，这里也会抛出异常，那么APIG应该主动生成一个JWT，然后发过去，客户端看到响应体中有JWT字段，也就知道要重传了
            throw new AuthException("Invalid JWT");
        }
    }

    public boolean hasIaPermission(String jwt,String serviceId) {
        try {
            Claims claims = Jwts.parser().setSigningKey(JWT_SECRET.getBytes()).parseClaimsJws(jwt).getBody();
            // 假设权限字段为"permission"，包含"serviceId"
            Object roles = claims.get("permission");
            return roles != null && roles.toString().contains(serviceId);
        } catch (Exception e) {
            return false;
        }
    }



    /**
     * 签发JWT，包含user_id、name、permission（Set类型）、exp（unix时间戳）
     */
    public String issueJwt() {
        // 示例数据，实际应根据业务逻辑获取,例如从用户表中查询APIKEY，获取响应信息，或者这里直接生成一些也可以。
        String userId = "10001";
        String name = "测试用户";
        java.util.Set<String> permission = new java.util.HashSet<>();
        permission.add("order-service");
        permission.add("user-service");
        permission.add("provider-a");
        long exp = System.currentTimeMillis() / 1000 + 60; // 1小时后过期

        return io.jsonwebtoken.Jwts.builder()
                .claim("user_id", userId)
                .claim("name", name)
                .claim("permission", permission)
                .claim("exp", exp)
                .signWith(io.jsonwebtoken.SignatureAlgorithm.HS256, "JWT_SECRET_1557".getBytes())
                .compact();
    }
}
