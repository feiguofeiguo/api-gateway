package com.bank.gateway.router.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.util.Date;

@TableName("gateway_route")
@Data
public class GatewayRoute {
    @TableId
    private Long id;
    private String routeId;
    private String pathPattern;
    private String serviceId;
    private Integer enabled;
    private String metadata;
    private Date createdAt;
    private Date updatedAt;

    //补充构造函数
    public GatewayRoute(long id, String routeId, String pathPattern, String serviceId, int enabled) {
        this.id = id;
        this.routeId = routeId;
        this.pathPattern = pathPattern;
        this.serviceId = serviceId;
        this.enabled = enabled;
        this.metadata = "";
        this.createdAt = new Date();
        this.updatedAt = new Date();
    }
}