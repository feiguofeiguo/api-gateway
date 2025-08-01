package com.bank.gateway.handler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.util.AttributeKey;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import io.netty.channel.ChannelHandler;

/**
 * 动态响应处理器（短连接模式）
 * 每次请求新建连接，响应后关闭
 */
@Component
@ChannelHandler.Sharable
@Slf4j
public class DynamicResponseHandler extends SimpleChannelInboundHandler<FullHttpResponse> {
    private final RequestResponseMapper requestResponseMapper;

    public DynamicResponseHandler(RequestResponseMapper requestResponseMapper) {
        this.requestResponseMapper = requestResponseMapper;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse response) {
        String requestId = (String) ctx.channel().attr(AttributeKey.valueOf("requestId")).get();
        ChannelHandlerContext frontendCtx = (ChannelHandlerContext) ctx.channel().attr(AttributeKey.valueOf("frontendCtx")).get();

        log.info("response: requestId={}, backendChannelHash={}, frontendCtxHash={}", requestId, ctx.channel().hashCode(), frontendCtx != null ? frontendCtx.hashCode() : null);
        log.debug("收到后端响应: requestId={}, responseStatus={}", requestId, response.status());
        log.debug("具体响应为："+response.content().toString(CharsetUtil.UTF_8));

        if (requestId != null && frontendCtx != null) {
            frontendCtx.writeAndFlush(response.retain()).addListener(future -> {
                if (future.isSuccess()) {
                    log.debug("响应转发成功: {}，关闭前端channel", requestId);
                } else {
                    log.warn("响应转发失败: {} -> {}", requestId, future.cause().getMessage());
                }
                frontendCtx.close();
            });
            requestResponseMapper.removeRequest(requestId);
        } else {
            log.warn("无法找到前端上下文或requestId: {}", requestId);
        }
        ctx.close(); // 关闭后端channel
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("动态响应处理器异常: {}", cause.getMessage());
        ctx.close();
    }
} 