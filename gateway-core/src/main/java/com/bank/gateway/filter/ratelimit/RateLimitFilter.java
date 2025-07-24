package com.bank.gateway.filter.ratelimit;

import com.bank.gateway.filter.auth.JwtValidator;
import com.bank.gateway.filter.auth.AuthException;
import com.bank.gateway.filter.ratelimit.ratelimitImpl.SlidingWindowRateLimiter;
import com.bank.gateway.filter.ratelimit.ratelimitImpl.TokenBucketRateLimiter;
import com.bank.gateway.plugin.GatewayPlugin;
import com.bank.gateway.plugin.PluginChain;
import com.bank.gateway.plugin.PluginContext;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;

@Slf4j
@Component
public class RateLimitFilter implements GatewayPlugin {
    @Autowired
    private RateLimitConfigService configService;
    @Autowired
    private TokenBucketRateLimiter tokenBucketRateLimiter;
    @Autowired
    private SlidingWindowRateLimiter slidingWindowRateLimiter;

    @Override
    public String name() { return "RateLimitPlugin"; }
    @Override
    public int order() { return 20; }
    @Override
    public boolean enabled() { return true; }

    @Override
    public void execute(PluginContext context, PluginChain chain) {
        FullHttpRequest request = context.getRequest();
        ChannelHandlerContext ctx = context.getNettyCtx();
        try {
            // userId 从 JWT 中获取，serviceId 从插进context获取
            String jwt = JwtValidator.extractJwt(request);
            String userId = JwtValidator.parseUserIdFromJwt(jwt);
            String serviceId = context.getServiceId();
            if (userId == null || serviceId == null) {
                sendError(ctx, "Missing userId or serviceId", HttpResponseStatus.BAD_REQUEST);
                return;
            }
            String key = serviceId + ":" + userId;
            RateLimitConfigService.LimitConfig config = configService.getConfig(serviceId);
            log.debug("service_id: {} with {}", serviceId, config);
            //进行限流
            boolean allowed;  //是否允许通过
            if (config.getType() == RateLimitEnum.TOKEN_BUCKET) {   //config控制走哪个限流器
                allowed = tokenBucketRateLimiter.allowRequest(key, config);   //限流器执行具体限流工作
            } else if (config.getType() == RateLimitEnum.SLIDING_WINDOW) {
                allowed = slidingWindowRateLimiter.allowRequest(key, config);
            } else {
                allowed = true;
            }
            if (!allowed) {
                sendError(ctx, "Too Many Requests", HttpResponseStatus.TOO_MANY_REQUESTS);
                return;
            }
            log.debug("插件版-流量控制，通过！");
            chain.doNext(context);
        } catch (AuthException e) {
            sendError(ctx, e.getMessage(), HttpResponseStatus.BAD_REQUEST);
        }
    }

    private void sendError(ChannelHandlerContext ctx, String message, HttpResponseStatus status) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                ctx.alloc().buffer().writeBytes(message.getBytes(CharsetUtil.UTF_8))
        );
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain;charset=UTF-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
