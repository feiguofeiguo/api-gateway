package com.bank.gateway.filter.ratelimit;

import com.bank.gateway.filter.auth.JwtValidator;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;

@Slf4j
@Component
@ChannelHandler.Sharable
public class RateLimitFilter extends ChannelInboundHandlerAdapter {

    @Autowired
    private RateLimitConfigService configService;
    @Autowired
    private TokenBucketRateLimiter tokenBucketRateLimiter;
    @Autowired
    private SlidingWindowRateLimiter slidingWindowRateLimiter;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof FullHttpRequest)) {
            log.debug("msg is not full HttpRequest");
            ctx.fireChannelRead(msg);
            return;
        }
        FullHttpRequest request = (FullHttpRequest) msg;

        // userId 从 JWT 中获取，serviceId 从路由获取
        String jwt = JwtValidator.extractJwt(request);
        String userId=JwtValidator.parseUserIdFromJwt(jwt);
        String serviceId = getServiceId(request.uri());
        if (userId == null || serviceId == null) {
            sendError(ctx, "Missing userId or serviceId", HttpResponseStatus.BAD_REQUEST);
            return;
        }
        String key = serviceId + ":" + userId;
        RateLimitConfigService.LimitConfig config = configService.getConfig(serviceId);

        //进行限流
        boolean allowed;   //是否允许通过
        if ("token-bucket".equals(config.getType())) {    //config控制走哪个限流器
            allowed = tokenBucketRateLimiter.allowRequest(key, config);    //限流器执行具体限流工作
        } else if ("sliding-window".equals(config.getType())) {
            allowed = slidingWindowRateLimiter.allowRequest(key, config);
        } else {
            allowed = true;
        }

        if (!allowed) {
            sendError(ctx, "Too Many Requests", HttpResponseStatus.TOO_MANY_REQUESTS);
            return;
        }
        ctx.fireChannelRead(msg);
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

    // TODO-重构 又来了家人们
    // 这里可根据你的路由规则提取 serviceId
    private String getServiceId(String uri){
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
