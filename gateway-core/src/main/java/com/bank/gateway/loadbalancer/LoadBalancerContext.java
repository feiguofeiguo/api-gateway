package com.bank.gateway.loadbalancer;

import com.bank.gateway.loadbalancer.loadbalancerImpl.*;
import com.bank.gateway.plugin.GatewayPlugin;
import com.bank.gateway.plugin.PluginChain;
import com.bank.gateway.plugin.PluginContext;
import com.bank.gateway.router.RouterService;
import com.bank.gateway.router.entity.ServiceProviderInstance;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/*
 * 策略上下文，用于调用具体的负载均衡算法
 */
@Component
@Slf4j
public class LoadBalancerContext implements GatewayPlugin {
    private LoadBalancer loadBalancer;
    private static Map<LoadBalancerEnum, LoadBalancer> strategyMap;

    @Override
    public String name() { return "LoadBalancerPlugin"; }
    @Override
    public int order() { return 40; }
    @Override
    public boolean enabled() { return true; }

    @Override
    public void execute(PluginContext context, PluginChain chain) {
        String serviceId = context.getServiceId();
        log.debug("serviceId:{}", serviceId);
        ChannelHandlerContext ctx = context.getNettyCtx();
        FullHttpRequest request = context.getRequest();
        String clientIP = ((java.net.InetSocketAddress) ctx.channel().remoteAddress()).getAddress().getHostAddress();
        log.debug("clientIP:{}", clientIP);
        ServiceProviderInstance instance = choose(serviceId, clientIP);
        if (instance == null) {
            FullHttpResponse response = new io.netty.handler.codec.http.DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.SERVICE_UNAVAILABLE,
                    ctx.alloc().buffer().writeBytes("No available service instances".getBytes(CharsetUtil.UTF_8))
            );
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain;charset=UTF-8");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
            ctx.writeAndFlush(response).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
            return;
        }
        context.setInstance(instance);
        log.debug("插件版-负载均衡，选择实例: {}", instance);
        chain.doNext(context);
    }

    @Autowired
    public LoadBalancerContext(LoadBalancerConfig loadBalancerConfig) {
        strategyMap = new HashMap<>();
        strategyMap.put(LoadBalancerEnum.IPHash, new IPHash());
        strategyMap.put(LoadBalancerEnum.LeastConnection, new LeastConnection());
        strategyMap.put(LoadBalancerEnum.Random, new Random());
        strategyMap.put(LoadBalancerEnum.RoundRobin, new RoundRobin());
        strategyMap.put(LoadBalancerEnum.WeightedRandom, new WeightedRandom());
        strategyMap.put(LoadBalancerEnum.WeightedRoundRobin, new WeightedRoundRobin());
        this.setLoadBalancer(loadBalancerConfig.getStrategy());
    }

    public void setLoadBalancer(LoadBalancerEnum loadBalancerEnum) {
        log.debug("loadBalancerEnum:{}", loadBalancerEnum);
        if(loadBalancerEnum==null){
            log.warn("loadBalancerEnum is null, set IPHash as default.");
            loadBalancerEnum=LoadBalancerEnum.IPHash;
        }
        this.loadBalancer = strategyMap.get(loadBalancerEnum);
    }

    public ServiceProviderInstance choose(String serviceId, String clientIP) {
        List<ServiceProviderInstance> instances = RouterService.getServiceProviderInstanceMap().get(serviceId);
        if (instances == null || instances.isEmpty()) {
            return null;
        }
        return loadBalancer.choose(serviceId, instances, clientIP);
    }
}
