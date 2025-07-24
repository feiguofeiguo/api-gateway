package com.bank.gateway.filter.auth;

import com.bank.gateway.plugin.GatewayPlugin;
import com.bank.gateway.plugin.PluginChain;
import com.bank.gateway.plugin.PluginContext;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.AttributeKey;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AuthFilter implements GatewayPlugin {
    @Autowired
    private ApiKeyValidator apiKeyValidator;
    @Autowired
    private SignatureValidator signatureValidator;
    @Autowired
    private IpWhitelistValidator ipWhitelistValidator;
    @Autowired
    private JwtValidator jwtValidator;
    private final AttributeKey<String> NEW_JWT_KEY = AttributeKey.valueOf("jwt");

    @Override
    public String name() { return "AuthPlugin"; }
    @Override
    public int order() { return 10; }
    @Override
    public boolean enabled() { return true; }

    @Override
    public void execute(PluginContext context, PluginChain chain) {
        FullHttpRequest request = context.getRequest();
        ChannelHandlerContext ctx = context.getNettyCtx();
        try {
            // 1. 校验 API Key
            apiKeyValidator.validate(request);
            // 2. 校验签名
            signatureValidator.validate(request);
            // 3. 校验 IP 白名单
            String clientIp = ((InetSocketAddress) ctx.channel().remoteAddress()).getAddress().getHostAddress();
            ipWhitelistValidator.validate(clientIp);
            // 4. 校验 JWT
            String jwt = JwtValidator.extractJwt(request);
            if (Objects.equals(jwt, "") || jwt.isEmpty()) {
                String newJwt = jwtValidator.issueJwt();
                log.debug("newJwt = {}", newJwt);
                ctx.channel().attr(NEW_JWT_KEY).set(newJwt);
                sendJwtResponse(ctx, newJwt);
                return;
            } else {
                jwtValidator.validate(jwt);
            }
            // 5. 权限判断
            String serviceId = getServiceId(request.uri());
            context.setServiceId(serviceId);   //设置微服务名，后续可以直接使用
            if (!jwtValidator.hasIaPermission(jwt, serviceId)) {
                throw new AuthException("No microService permission");
            }
            log.debug("插件版-安全认证，通过！");
            chain.doNext(context);   // 认证通过，进入下一个插件
        } catch (AuthException e) {
            log.warn("认证失败: {}", e.getMessage());
            sendAuthError(ctx, e.getMessage());
        }
    }

    private void sendAuthError(ChannelHandlerContext ctx, String message) {
        FullHttpResponse response = new io.netty.handler.codec.http.DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.UNAUTHORIZED,
                ctx.alloc().buffer().writeBytes(message.getBytes(CharsetUtil.UTF_8))
        );
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain;charset=UTF-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        ctx.writeAndFlush(response).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
    }

    /**
     * 生成JWT并下发给客户端
     * @param ctx ChannelHandlerContext
     * @param newJwt 生成的JWT字符串
     */
    private void sendJwtResponse(ChannelHandlerContext ctx, String newJwt) {
        String responseBody = "{\"jwt\":\"" + newJwt + "\"}";
        FullHttpResponse response = new io.netty.handler.codec.http.DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.OK,
                ctx.alloc().buffer().writeBytes(responseBody.getBytes(CharsetUtil.UTF_8))
        );
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json;charset=UTF-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        ctx.writeAndFlush(response).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
    }

    /**
     * 根据请求路径获取微服务名
     * @param uri 请求路径
     * @return 服务名，如果没找到返回null
     * 一个常用方法，在这里获取结果后存入plugin_context,后续直接使用即可
     */
    private static String getServiceId(String uri) {
        String pathAndQuery = uri;
        // 兼容处理：有些客户端（如SpringBoot内嵌Netty）发起的请求uri会包含完整的url（如：http://127.0.0.1:9261/order-service/test1），
        // 而postman等工具只会带相对路径（如：/order-service/test1）。
        // 这里做兼容处理，始终只取path和query部分，去除host和端口
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
        String queryId = pathAndQuery.substring(1).split("/")[0];
        log.debug("queryId: "+queryId);
        return queryId;
    }
} 