package com.zd.sdq.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.company.cbf.starter.data.entity.DeviceInfo;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 数据实体类
 * @author hzs
 * @date 2023/12/08
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "s_device_info_ext")
public class DeviceInfoExt extends DeviceInfo {
    
    @TableField(value = "mqtt_gateway_id")
    private Long mqttGatewayId;

    // 设备端口, 用于 接入网关为TCP Server 类型设备
    @TableField(value = "port")
    private String port;

    // 远程设备 ID , 用于 接入网关为 HTTP 类型设备
    @TableField(value = "remote_device_id")
    private String remoteDeviceId;

    // 基线值
    @TableField(value = "baseline")
    private Double baseline;
}
