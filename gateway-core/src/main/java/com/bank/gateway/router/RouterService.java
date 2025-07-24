package com.bank.gateway.router;

import com.bank.gateway.plugin.GatewayPlugin;
import com.bank.gateway.plugin.PluginChain;
import com.bank.gateway.plugin.PluginContext;
import com.bank.gateway.router.entity.GatewayRoute;
import com.bank.gateway.router.entity.ServiceProviderInstance;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class RouterService implements GatewayPlugin {
    private final DiscoveryClient discoveryClient;

    @Getter
    private static final Map<String, List<ServiceProviderInstance>> serviceProviderInstanceMap = new HashMap<>();

    @Override
    public String name() { return "RouterPlugin"; }
    @Override
    public int order() { return 30; }
    @Override
    public boolean enabled() { return true; }

    @Override
    public void execute(PluginContext context, PluginChain chain) {
        String uri = context.getRequest().uri();
        String serviceId = context.getServiceId();
        log.debug("serviceId: " + serviceId);
        //serviceId=getMicroServiceId(serviceId);
        if (serviceId == null) {
            // 404 响应
            ChannelHandlerContext ctx = context.getNettyCtx();
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.NOT_FOUND,
                    ctx.alloc().buffer().writeBytes("ServiceID not found".getBytes())
            );
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain;charset=UTF-8");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
            return;
        }
        log.debug("插件版-路由-获取微服务名，成功！");
        chain.doNext(context);
    }

    public void init() {
        log.debug("===Call RouterService init===");
        //构造示例数据
        List<GatewayRoute> routes = new ArrayList<>();
        routes.add(new GatewayRoute(1L, "user-service", "/api/users/**", "user-service", 1));
        routes.add(new GatewayRoute(2L, "order-service", "/api/orders/**", "order-service", 1));
        routes.add(new GatewayRoute(3L, "payment-service", "/api/payments/**", "payment-service", 1));
        routes.add(new GatewayRoute(4L, "provider-a", "/api/provider-a/**", "provider-a", 1));
        routes.add(new GatewayRoute(5L, "provider-b", "/api/provider-b/**", "provider-b", 1));
        for (GatewayRoute route : routes) {
            String serviceId = route.getServiceId();
            List<ServiceProviderInstance> instances = new ArrayList<>();
            //从注册中心上拉取实例
            List<ServiceInstance> tmpInstances = discoveryClient.getInstances(serviceId);
            for (int i = 0; i < tmpInstances.size(); i++) {
                ServiceProviderInstance instance = new ServiceProviderInstance();
                ServiceInstance tmpInstance = tmpInstances.get(i);
                instance.setHost(tmpInstance.getHost());
                instance.setPort(tmpInstance.getPort());
                instance.setMetadata(tmpInstance.getMetadata());
                instances.add(instance);
            }
            serviceProviderInstanceMap.put(serviceId, instances);
        }
    }

    /**
     * 应对模糊路径的逻辑，但是在约定：url的第一段是微服务名时就不需要
     * @param serviceId 请求路径
     * @return 服务名，如果没找到返回null
     */
    public String getMicroServiceId(String serviceId) {
        for (Map.Entry<String, List<ServiceProviderInstance>> entry : serviceProviderInstanceMap.entrySet()) {
            if (pathMatches(serviceId, entry.getKey())) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 简单的路径匹配逻辑
     * 支持通配符 * 和 **
     * @param requestPath 请求路径
     * @param pattern 路径模式
     * @return 是否匹配
     */
    private boolean pathMatches(String requestPath, String pattern) {
        //log.debug("pathMatches: "+requestPath+"  pattern: "+pattern);
        if (pattern == null || requestPath == null) {
            return false;
        }

        //requestPath="/api/v1/user"

        // 精确匹配
        if (pattern.equals(requestPath)) {  //pattern="/api/v1/user"
            return true;
        }

        // 通配符匹配
        if (pattern.contains("*")) {  //pattern="/api/*/user"
            String regex = pattern.replace("*", ".*");
            return requestPath.matches(regex);
        }

        // 前缀匹配
        if (pattern.endsWith("/**")) {  //pattern="/api/v1/**"
            String prefix = pattern.substring(0, pattern.length() - 2);
            return requestPath.startsWith(prefix);
        }

        return false;
    }
}