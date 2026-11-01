package com.zd.sdq.entity.dto;

import lombok.Data;

/** 从现有留存表选出的测点最新有效数据。 */
@Data
public class DeviceReportSample {
    private String deviceCode;
    private String deviceType;
    private String remoteDeviceId;
    private Long mqttGatewayId;
    /** 真实源记录采集时间，不使用payload中的历史上报时间或sample_time替代。 */
    private String dataTime;
    private String payload;
}
