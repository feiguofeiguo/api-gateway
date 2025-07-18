package com.bank.gateway.router;

import com.bank.gateway.router.entity.GatewayRoute;
import com.bank.gateway.router.entity.ServiceProviderInstance;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class RouterService {

    @Getter
    private static final Map<String, List<ServiceProviderInstance>> serviceProviderInstanceMap = new HashMap<>();

    public void init() {
        log.debug("===Call RouterService init===");
        //TODO 获取数据库中的路由信息
        //List<GatewayRoute> routes = gatewayRouteMapper.selectList(null);

        //构造示例数据
        List<GatewayRoute> routes = new ArrayList<>();
        routes.add(new GatewayRoute(1L, "user-service", "/api/users/**", "user-service", 1));
        routes.add(new GatewayRoute(2L, "order-service", "/api/orders/**", "order-service", 1));
        routes.add(new GatewayRoute(3L, "payment-service", "/api/payments/**", "payment-service", 1));
        routes.add(new GatewayRoute(4L, "payment-service", "/api/payments/**", "payment-service", 1));
        for (GatewayRoute route : routes) {
            String serviceId = route.getServiceId();
            List<ServiceProviderInstance> instances = new ArrayList<>();
            // 这里用假数据，实际可从注册中心或配置获取
            for (int i = 0; i < 1; i++) {
                ServiceProviderInstance instance = new ServiceProviderInstance();
                instance.setHost("127.0.0.1");
                instance.setPort(8080 + i + 1);
                instance.setMetadata(Map.of("weight", String.valueOf(i+1), "zone", "zone" + i, "version", "v1"));
                instances.add(instance);
            }
            serviceProviderInstanceMap.put(serviceId, instances);
        }
    }

    /**
     * 根据请求路径获取微服务名
     * @param uri 请求路径
     * @return 服务名，如果没找到返回null
     */
    public String getServiceId(String uri) {
        log.debug("===Call getServiceId===");
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
        String queryId=pathAndQuery.substring(1).split("/")[0];
        log.debug("queryId: "+queryId);

        //转为从serviceProviderInstanceMap中获取服务名
        for (Map.Entry<String, List<ServiceProviderInstance>> entry : serviceProviderInstanceMap.entrySet()) {
            if (pathMatches(queryId, entry.getKey())) {
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