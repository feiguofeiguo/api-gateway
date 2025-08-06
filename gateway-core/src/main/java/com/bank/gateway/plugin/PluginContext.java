package com.bank.gateway.plugin;

import com.bank.gateway.router.entity.ServiceProviderInstance;
import io.jsonwebtoken.Claims;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import lombok.Data;

@Data
public class PluginContext {
    private FullHttpRequest request;
    private ChannelHandlerContext nettyCtx;
    private String serviceId;
    private ServiceProviderInstance instance;
    // 其他需要传递的数据
    private String requestId;
    private Claims requestJwtClaims;   //每个请求的jwt应该都是不同的，将jwt作为plugin的ctx，合理吗

    public PluginContext(FullHttpRequest request, ChannelHandlerContext nettyCtx) {
        this.request = request;
        this.nettyCtx = nettyCtx;
    }
}