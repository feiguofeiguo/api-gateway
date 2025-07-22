package com.bank.gateway.router.listener;

import com.alibaba.cloud.nacos.NacosServiceManager;
import com.alibaba.nacos.client.naming.event.InstancesChangeEvent;
import com.alibaba.nacos.common.notify.Event;
import com.alibaba.nacos.common.notify.NotifyCenter;
import com.alibaba.nacos.common.notify.listener.Subscriber;
import com.bank.gateway.router.RouterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

/*
 * 监听服务实例的变化
 */
@Component
@Slf4j
public class ServiceInstanceListener extends Subscriber<InstancesChangeEvent> {
    @Resource
    private NacosServiceManager nacosServiceManager;
    @Autowired
    private RouterService routerService;

    @PostConstruct
    public void registerToNotifyCenter(){
        NotifyCenter.registerSubscriber(this);
    }

    @Override
    public void onEvent(InstancesChangeEvent instancesChangeEvent) {
        log.info("nacos监听到{}服务实例变化：{}", instancesChangeEvent.getServiceName(), instancesChangeEvent.getHosts());
        routerService.init();
    }

    @Override
    public Class<? extends Event> subscribeType() {
        return InstancesChangeEvent.class;
    }

}
