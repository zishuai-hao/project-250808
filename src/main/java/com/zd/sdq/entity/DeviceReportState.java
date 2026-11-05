package com.zd.sdq.entity;

import lombok.Data;

/** 采集记录的发送状态，SENT 表示 MQTT 提交成功，不代表平台业务入库确认。 */
@Data
public class DeviceReportState {
    private String reportStatus;
    /** 数据所属整点（北京时间）。 */
    private String reportTime;
    private Integer reportAttempts;
    private Long reportAttemptedAt;
    private Long reportSentAt;
    private String reportError;
    private String reportToken;
}
