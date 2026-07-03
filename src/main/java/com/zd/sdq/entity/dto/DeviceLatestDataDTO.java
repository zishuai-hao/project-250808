package com.zd.sdq.entity.dto;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设备最新数据状态快照.
 */
@Data
public class DeviceLatestDataDTO {
    private String deviceCode;
    private String monitoringContent;
    private String deviceType;
    private String deviceTypeName;
    private String networkGatewayType;
    private String remoteDeviceId;
    private String port;
    private String frequency;
    private Boolean enable;
    private String source;
    private Map<String, Object> latestData = new LinkedHashMap<>();
    private String dataTime;
    private String receivedAt;
    private Long receivedAtMillis;
    private String status;
    private String statusText;
    private String message;
}
