package com.bank.gateway.plugin;

public interface PluginChain {
    void doNext(PluginContext context);
}
