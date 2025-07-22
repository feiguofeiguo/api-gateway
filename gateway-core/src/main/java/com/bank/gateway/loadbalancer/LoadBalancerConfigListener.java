package com.bank.gateway.loadbalancer;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.ConfigChangeEvent;
import com.alibaba.nacos.api.config.ConfigChangeItem;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.client.config.listener.impl.AbstractConfigChangeListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/*
 * 监听nacos上配置的负载均衡策略的变更
 */
@Component
@Slf4j
public class LoadBalancerConfigListener {
    @Autowired
    private LoadBalancerContext loadBalancerContext;

    @Resource
    public void nacosListen(NacosConfigManager nacosConfigManager) {
        //获取配置中心服务
        ConfigService configService = nacosConfigManager.getConfigService();
        try {
            //对配置中心添加监听(配置文件的dataId,group)
            configService.addListener("loadbalancer.yaml", "DEFAULT_GROUP", new AbstractConfigChangeListener() {
                //监听后的处理逻辑
                @Override
                public void receiveConfigChange(ConfigChangeEvent configChangeEvent) {
                    for (ConfigChangeItem changeItem : configChangeEvent.getChangeItems()) {
                        log.info("nacos监听到{}的变化：{} -> {}", changeItem.getKey(), changeItem.getOldValue(), changeItem.getNewValue());
                        loadBalancerContext.setLoadBalancer(Enum.valueOf(LoadBalancerEnum.class, changeItem.getNewValue()));
                    }
                }
            });
        } catch (NacosException e) {
            throw new RuntimeException(e);
        }
    }

}
