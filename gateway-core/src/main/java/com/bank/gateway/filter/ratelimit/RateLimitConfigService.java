package com.bank.gateway.filter.ratelimit;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.ConfigChangeEvent;
import com.alibaba.nacos.api.config.ConfigChangeItem;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.client.config.listener.impl.AbstractConfigChangeListener;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Map;

@Service
@Slf4j
public class RateLimitConfigService {

    private Map<String, RateLimitConfigService.LimitConfig> rateLimitStrategy;

    @Autowired
    public RateLimitConfigService(RateLimitConfig rateLimitConfig) {
        log.debug("Initial rateLimitStrategy: {}", rateLimitConfig.getStrategy());
        this.rateLimitStrategy = rateLimitConfig.getStrategy();
    }
   
    // 因为不同微服务的策略/参数不一致，所以这里需要根据微服务名获取对应策略/参数
    public LimitConfig getConfig(String serviceId) {
        return rateLimitStrategy.getOrDefault(serviceId, new LimitConfig(RateLimitEnum.TOKEN_BUCKET, 5, 10, 0, 0));
    }

    //监听Nacos上的配置修改
    //如果nacos中删除了配置，本地也还可以通过getOrDefault来苟住
    @Resource
    public void nacosListen(NacosConfigManager nacosConfigManager) {
        //获取配置中心服务
        ConfigService configService = nacosConfigManager.getConfigService();
        try {
            //对配置中心添加监听(配置文件的dataId,group)
            configService.addListener("ratelimit.yaml", "DEFAULT_GROUP", new AbstractConfigChangeListener() {
                //监听后的处理逻辑
                @Override
                public void receiveConfigChange(ConfigChangeEvent configChangeEvent) {
                    for (ConfigChangeItem changeItem : configChangeEvent.getChangeItems()) {
                        String changeItemKey = changeItem.getKey();
                        log.info("nacos监听到{}的变化：{} -> {}", changeItemKey, changeItem.getOldValue(), changeItem.getNewValue());
                        updateStrategy(changeItemKey,changeItem.getNewValue());
                    }
                }
            });
        } catch (NacosException e) {
            throw new RuntimeException(e);
        }
    }

    //监听到Nacos的更新后，对本地rateLimitStrategy进行更新
    private void updateStrategy(String changeItemKey,String changeItemNewValue){
        // 解析changeItemKey，格式为ratelimit.strategy.{serviceId}.{field}
        String[] keyParts = changeItemKey.split("\\.");
        if (keyParts.length == 4) {
            String serviceId = keyParts[2];
            String field = keyParts[3];
            LimitConfig config = rateLimitStrategy.get(serviceId);
            if(config == null){
                config = new LimitConfig();
                log.debug("新增serviceId={} 的流量控制策略",serviceId);
            }
            log.debug("修改前config: {}",config);

            switch (field) {
                case "type":
                    try {
                        config.setType(RateLimitEnum.valueOf(changeItemNewValue));
                    } catch (Exception e) {
                        log.warn("无法将type字段值{}转换为RateLimitEnum", changeItemNewValue);
                    }
                    break;
                case "TkbRate":
                    try {
                        config.setTkbRate(Integer.parseInt(changeItemNewValue));
                    } catch (Exception e) {
                        log.warn("无法将TkbRate字段值{}转换为int", changeItemNewValue);
                    }
                    break;
                case "TkbCapacity":
                    try {
                        config.setTkbCapacity(Integer.parseInt(changeItemNewValue));
                    } catch (Exception e) {
                        log.warn("无法将TkbCapacity字段值{}转换为int", changeItemNewValue);
                    }
                    break;
                case "SlwWindow":
                    try {
                        config.setSlwWindow(Integer.parseInt(changeItemNewValue));
                    } catch (Exception e) {
                        log.warn("无法将SlwWindow字段值{}转换为int", changeItemNewValue);
                    }
                    break;
                case "SlwThreshold":
                    try {
                        config.setSlwThreshold(Integer.parseInt(changeItemNewValue));
                    } catch (Exception e) {
                        log.warn("无法将SlwThreshold字段值{}转换为int", changeItemNewValue);
                    }
                    break;
                default:
                    log.warn("未知的限流配置字段: {}", field);
            }

            // 更新回rateLimitStrategy
            rateLimitStrategy.put(serviceId, config);
            log.debug("修改后config: {}",config);
        } else {
            log.warn("changeItemKey格式不正确: {}", changeItemKey);
        }
    }


    @Data
    public static class LimitConfig {
        private RateLimitEnum type; // token-bucket or sliding-window
        private int TkbRate;    // 令牌桶每秒填充速率
        private int TkbCapacity;// 令牌桶容量
        private int SlwWindow;  // 滑动窗口秒数
        private int SlwThreshold;// 滑动窗口最大请求数

        public LimitConfig() {} // 必须有无参构造,否则获取不到Nacos的配置文件

        public LimitConfig(RateLimitEnum type, int TkbRate, int TkbCapacity, int SlwWindow, int SlwThreshold) {
            this.type = type;
            this.TkbRate = TkbRate;
            this.TkbCapacity = TkbCapacity;
            this.SlwWindow = SlwWindow;
            this.SlwThreshold = SlwThreshold;
        }
    }
}
