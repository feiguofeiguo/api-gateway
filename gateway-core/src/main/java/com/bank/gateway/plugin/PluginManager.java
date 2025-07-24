package com.bank.gateway.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.bank.gateway.filter.auth.AuthFilter;
import com.bank.gateway.filter.ratelimit.RateLimitFilter;
import com.bank.gateway.handler.Forwarder;
import com.bank.gateway.loadbalancer.LoadBalancerContext;
import com.bank.gateway.router.RouterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Component
public class PluginManager {
    private final List<GatewayPlugin> plugins = new ArrayList<>();

    @Autowired
    private RateLimitFilter rateLimitFilter;
    @Autowired
    private AuthFilter authFilter; // 或 AuthPlugin
    @Autowired
    private RouterService routerService;
    @Autowired
    private LoadBalancerContext loadBalancerContext;
    @Autowired
    private Forwarder forwarder;

    @PostConstruct
    public void initPlugins() {
        plugins.add(authFilter);
        plugins.add(rateLimitFilter);
        plugins.add(routerService);
        plugins.add(loadBalancerContext);
        plugins.add(forwarder);
        // 还可以自动注入更多插件
        plugins.sort(Comparator.comparingInt(GatewayPlugin::order));
    }

    public void addPlugin(GatewayPlugin plugin) {
        plugins.add(plugin);
        // 按 order 排序，order 越小优先级越高
        plugins.sort(Comparator.comparingInt(GatewayPlugin::order));
    }

    public List<GatewayPlugin> getPlugins() {
        return Collections.unmodifiableList(plugins);
    }
}