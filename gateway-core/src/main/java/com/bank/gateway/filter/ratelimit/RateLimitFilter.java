package com.bank.gateway.filter.ratelimit;

import com.bank.gateway.filter.auth.JwtValidator;
import com.bank.gateway.filter.auth.AuthException;
import com.bank.gateway.filter.ratelimit.ratelimitImpl.FixedWindowRateLimiter;
import com.bank.gateway.filter.ratelimit.ratelimitImpl.SlidingWindowRateLimiter;
import com.bank.gateway.filter.ratelimit.ratelimitImpl.TokenBucketRateLimiter;
import com.bank.gateway.plugin.GatewayPlugin;
import com.bank.gateway.plugin.PluginChain;
import com.bank.gateway.plugin.PluginContext;
import io.jsonwebtoken.Claims;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
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
    @Autowired
    private FixedWindowRateLimiter fixedWindowRateLimiter;
    @Autowired
    private GlobalRateLimiter globalRateLimiter;

    @Override
    public String name() { return "RateLimitPlugin"; }
    @Override
    public int order() { return 20; }
    @Override
    public boolean enabled() { return true; }

    @Override
    public void execute(PluginContext context, PluginChain chain) {
        long start = System.nanoTime();
        FullHttpRequest request = context.getRequest();
        ChannelHandlerContext ctx = context.getNettyCtx();
        try {
            // 计时：JWT提取和解析
            long jwtStart = System.nanoTime();
            Claims jwtClaims = context.getRequestJwtClaims();
            String userId = jwtClaims.get("user_id", String.class);     //JwtValidator.parseUserIdFromJwt(jwtClaims);
            String serviceId = context.getServiceId();
            long jwtEnd = System.nanoTime();
            log.debug("{}-【JWT处理】耗时: {} ns", context.getRequestId(), jwtEnd - jwtStart);
            
            if (userId == null || serviceId == null) {
                sendError(ctx, request, "Missing userId or serviceId", HttpResponseStatus.BAD_REQUEST);
                return;
            }
            
            // 计时：配置获取
            long configStart = System.nanoTime();
            String key = serviceId;  // + ":" + userId;
            RateLimitConfigService.LimitConfig config = configService.getConfig(serviceId);
            long configEnd = System.nanoTime();
            log.debug("{}-【配置获取】耗时: {} ns", context.getRequestId(), configEnd - configStart);
            
            // 检查请求是否已经通过本地限流检查
            // 如果本地限流检查通过了，我们信任它，除非配置要求强制全局检查
            // 这里我们可以根据需要添加一些边缘情况的检查
            // 例如：对于某些关键服务，即使本地限流通过了，也进行全局精确检查
            
            // 计时：限流器执行
            long rateLimitStart = System.nanoTime();
            boolean allowed = true; // 默认允许，因为我们已经有了本地限流检查
            
            // 只有在特殊情况下才进行全局精确限流检查
            // 例如：当本地限流器刚刚初始化时，或者需要精确计数时
            // 这里可以添加一些判断逻辑，决定是否需要进行全局精确检查
            boolean needGlobalCheck = shouldPerformGlobalCheck(serviceId, config);
            
            if (needGlobalCheck) {
                // 是否允许通过
                if (config.getType() == RateLimitEnum.TOKEN_BUCKET) {   //config控制走哪个限流器
                    allowed = tokenBucketRateLimiter.allowRequest(key, config);   //限流器执行具体限流工作
                } else if (config.getType() == RateLimitEnum.SLIDING_WINDOW) {
                    allowed = slidingWindowRateLimiter.allowRequest(key, config);
                } else if (config.getType() == RateLimitEnum.FIXED_WINDOW) {
                    allowed = fixedWindowRateLimiter.allowRequest(key, config);
                }
            }
            
            long rateLimitEnd = System.nanoTime();
            if (needGlobalCheck) {
                log.warn("{}-【限流器执行】耗时: {} ns, 约 {} us", context.getRequestId(),
                         rateLimitEnd - rateLimitStart, (rateLimitEnd - rateLimitStart) / 1000.0);
            } else {
                log.debug("{}-【限流器执行】跳过全局检查", context.getRequestId());
            }
            
            if (!allowed) {
                sendError(ctx, request, "Too Many Requests", HttpResponseStatus.TOO_MANY_REQUESTS);
                return;
            }
            log.debug("插件版-流量控制，通过！");
            long end = System.nanoTime();
            log.warn("{}-【RateLimitFilter】总耗时: {} ns, 约 {} us, {} ms", context.getRequestId(), 
                     end - start, (end - start)/1000.0, (end - start)/1000000.0);
            chain.doNext(context);
        } catch (RateLimitException e) {
            long end = System.nanoTime();
            log.warn("{}-【RateLimitFilter】异常耗时: {} ns, 约 {} us, {} ms", context.getRequestId(), 
                     end - start, (end - start)/1000.0, (end - start)/1000000.0);
            sendError(ctx, request, e.getMessage(), HttpResponseStatus.BAD_REQUEST);
        }
    }

    /**
     * 判断是否需要进行全局精确限流检查
     * @param serviceId 服务ID
     * @param config 限流配置
     * @return 是否需要全局检查
     */
    private boolean shouldPerformGlobalCheck(String serviceId, RateLimitConfigService.LimitConfig config) {
        // 默认情况下，我们信任本地限流器
        return false;
        // 只有在特殊情况下才需要进行全局精确检查
        // 例如：
        // 1. 对于某些关键服务，可能需要更精确的控制
        // 2. 在系统启动初期，本地限流器尚未稳定时
        // 3. 当配置明确要求进行全局检查时
        
        // 示例：对于关键服务总是进行全局检查
//        if ("critical-service".equals(serviceId)) {
//            return true;
//        }
//
//        // 示例：当限流阈值很小时，进行全局检查以确保精确性
//        if (config.getType() == RateLimitEnum.TOKEN_BUCKET && config.getTkbRate() < 5) {
//            return true;
//        }
//
//        if ((config.getType() == RateLimitEnum.SLIDING_WINDOW ||
//             config.getType() == RateLimitEnum.FIXED_WINDOW) &&
//            config.getSlwThreshold() < 10) {
//            return true;
//        }
//
//        // 默认情况下不进行全局检查，信任本地限流器
//        return false;
    }

    private void sendError(ChannelHandlerContext ctx, FullHttpRequest request, String message, HttpResponseStatus status) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                ctx.alloc().buffer().writeBytes(message.getBytes(CharsetUtil.UTF_8))
        );
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain;charset=UTF-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        
        // 释放原始请求
        //ReferenceCountUtil.release(request);
    }
}