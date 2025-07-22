package com.bank.gateway.filter.auth;

import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.util.AttributeKey;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Objects;

@Slf4j
@Component
@ChannelHandler.Sharable
public class AuthFilter extends ChannelInboundHandlerAdapter {

    private final ApiKeyValidator apiKeyValidator = new ApiKeyValidator();
    private final SignatureValidator signatureValidator = new SignatureValidator();
    private final IpWhitelistValidator ipWhitelistValidator = new IpWhitelistValidator();
    private final JwtValidator jwtValidator = new JwtValidator();

    private final AttributeKey<String> NEW_JWT_KEY = AttributeKey.valueOf("jwt");

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof FullHttpRequest)) {
            ctx.fireChannelRead(msg);
            return;
        }
        FullHttpRequest request = (FullHttpRequest) msg;

        try {
            // 1. 校验 API Key
            apiKeyValidator.validate(request);

            // 2. 校验签名
            signatureValidator.validate(request);

            // 3. 校验 IP 白名单
            String clientIp = ((InetSocketAddress) ctx.channel().remoteAddress()).getAddress().getHostAddress();
            ipWhitelistValidator.validate(clientIp);

            // 4. 校验 JWT
            String jwt = jwtValidator.extractJwt(request);
            // 如果客户端还没有获取到JWT，服务器需要签发一个，签发后立即返回
            // TODO-功能 这里好像就没有一个，验证客户端的环节，而是，你没有那我就直接发
            if (Objects.equals(jwt, "") || jwt.isEmpty()) {
                String newJwt = jwtValidator.issueJwt();
                log.debug("newJwt = {}", newJwt);
                ctx.channel().attr(NEW_JWT_KEY).set(newJwt);
                sendJwtResponse(ctx, newJwt);
                return;
            }else{
                jwtValidator.validate(jwt);
            }

            // 5. 权限判断
            String serviceId=getServiceId(request.uri());   // 这里需要获取当前请求的微服务名
            if (!jwtValidator.hasIaPermission(jwt,serviceId)) {
                throw new AuthException("No microService permission");
            }

            // 认证通过，放行
            ctx.fireChannelRead(msg);
        } catch (AuthException e) {
            log.warn("认证失败: {}", e.getMessage());
            sendAuthError(ctx, e.getMessage());
        }
    }

    private void sendAuthError(ChannelHandlerContext ctx, String message) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.UNAUTHORIZED,
                ctx.alloc().buffer().writeBytes(message.getBytes(CharsetUtil.UTF_8))
        );
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain;charset=UTF-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * 生成JWT并下发给客户端
     * @param ctx ChannelHandlerContext
     * @param newJwt 生成的JWT字符串
     */
    private void sendJwtResponse(ChannelHandlerContext ctx, String newJwt) {
        String responseBody = "{\"jwt\":\"" + newJwt + "\"}";
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.OK,
                ctx.alloc().buffer().writeBytes(responseBody.getBytes(CharsetUtil.UTF_8))
        );
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json;charset=UTF-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    //TODO-重构 这是一个常用方法，后期考虑独立出去
    private static String getServiceId(String uri){
        String pathAndQuery = uri;
        try {
            URI uriObj = new URI(uri);
            String path = uriObj.getRawPath();
            String query = uriObj.getRawQuery();
            if (query != null && !query.isEmpty()) {
                pathAndQuery = path + "?" + query;
            } else {
                pathAndQuery = path;
            }
            log.debug("兼容处理后的uri: " + pathAndQuery);
        } catch (Exception e) {
            log.warn("解析uri异常，使用原始uri: " + pathAndQuery, e);
        }
        log.debug("pathAndQuery: "+pathAndQuery);
        String queryId=pathAndQuery.substring(1).split("/")[0];
        log.debug("queryId: "+queryId);
        return queryId;
    }
}
