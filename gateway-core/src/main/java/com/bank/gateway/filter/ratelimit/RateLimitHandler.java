package com.bank.gateway.filter.ratelimit;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ChannelHandler.Sharable
public class RateLimitHandler extends ChannelInboundHandlerAdapter {
    
    @Autowired
    private GlobalRateLimiter globalRateLimiter;
    
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof FullHttpRequest)) {
            super.channelRead(ctx, msg);
            return;
        }

        FullHttpRequest request = (FullHttpRequest) msg;

        // 从请求路径中提取服务ID
        // 路径格式: /{serviceId}/...
        String uri = request.uri();
        String serviceId = extractServiceId(uri);

        if (serviceId != null) {
            // 检查是否允许通过本地限流
            if (!globalRateLimiter.allowRequest(serviceId)) {
                // 拒绝请求，返回429状态码
                sendRateLimitResponse(ctx);
                ReferenceCountUtil.release(request); // 显式释放请求
                return;
            }
        }

        // 允许请求继续处理
        super.channelRead(ctx, msg);
    }
    
    /**
     * 从URI中提取服务ID
     * @param uri 请求URI
     * @return 服务ID
     */
    private String extractServiceId(String uri) {
        try {
            String path = uri;
            // 如果是完整URL，只取路径部分
            if (uri.startsWith("http")) {
                java.net.URI uriObj = new java.net.URI(uri);
                path = uriObj.getPath();
            }
            
            // 移除开头的斜杠并按斜杠分割
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            
            // 第一个路径段就是服务ID
            String[] segments = path.split("/");
            if (segments.length > 0) {
                return segments[0];
            }
        } catch (Exception e) {
            log.warn("Failed to extract serviceId from URI: {}", uri, e);
        }
        return null;
    }
    
    /**
     * 发送限流响应
     * @param ctx ChannelHandlerContext
     */
    private void sendRateLimitResponse(ChannelHandlerContext ctx) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.TOO_MANY_REQUESTS,
                ctx.alloc().buffer().writeBytes("Too Many Requests".getBytes(CharsetUtil.UTF_8))
        );
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain;charset=UTF-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        ctx.writeAndFlush(response).addListener(io.netty.channel.ChannelFutureListener.CLOSE);

        log.debug("Request rejected by rate limiter");
    }
}