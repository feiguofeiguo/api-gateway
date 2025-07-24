package com.bank.gateway.plugin;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.FullHttpRequest;
import java.util.List;

public class PluginDispatcherHandler extends ChannelInboundHandlerAdapter {
    private final PluginManager pluginManager;

    public PluginDispatcherHandler(PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof FullHttpRequest)) {
            ctx.fireChannelRead(msg);
            return;
        }
        FullHttpRequest request = (FullHttpRequest) msg;
        PluginContext pluginContext = new PluginContext(request, ctx);
        List<GatewayPlugin> plugins = pluginManager.getPlugins();
        PluginChain chain = new PluginChainImpl(plugins, 0);
        chain.doNext(pluginContext);
    }

    // 插件链实现
    private static class PluginChainImpl implements PluginChain {
        private final List<GatewayPlugin> plugins;
        private int index;

        public PluginChainImpl(List<GatewayPlugin> plugins, int index) {
            this.plugins = plugins;
            this.index = index;
        }

        @Override
        public void doNext(PluginContext context) {
            if (index < plugins.size()) {
                GatewayPlugin plugin = plugins.get(index++);
                if (plugin.enabled()) {
                    plugin.execute(context, this);
                } else {
                    doNext(context);
                }
            } else {
                // 插件链结束，继续 Netty pipeline
                context.getNettyCtx().fireChannelRead(context.getRequest());
            }
        }
    }
} 