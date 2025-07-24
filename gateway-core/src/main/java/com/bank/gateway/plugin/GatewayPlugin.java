package com.bank.gateway.plugin;

public interface GatewayPlugin {
    String name();
    int order(); // 插件优先级
    boolean enabled();
    void execute(PluginContext context, PluginChain chain);
}