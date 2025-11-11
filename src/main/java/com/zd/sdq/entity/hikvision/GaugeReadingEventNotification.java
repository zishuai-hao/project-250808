package com.zd.sdq.entity.hikvision;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 水尺读数事件的完整通知体
 */
@Data
public class GaugeReadingEventNotification {

    /**
     * 报警设备IPv4地址 (必需)
     */
    @JsonProperty("ipAddress")
    private String ipAddress;

    /**
     * 报警设备IPv6地址 (可选)
     */
    @JsonProperty("ipv6Address")
    private String ipv6Address;

    /**
     * 报警设备端口号 (可选)
     */
    @JsonProperty("portNo")
    private Integer portNo;

    /**
     * 协议类型, 例如: HTTP, HTTPS, EHome等 (可选)
     */
    @JsonProperty("protocol")
    private String protocol;

    /**
     * MAC地址 (可选)
     */
    @JsonProperty("macAddress")
    private String macAddress;

    /**
     * 触发报警的设备通道号 (可选)
     */
    @JsonProperty("channelID")
    private Integer channelID;

    /**
     * 通道名称（监控点名称） (可选)
     */
    @JsonProperty("channelName")
    private String channelName;

    /**
     * 报警触发时间 (必需)
     */
    @JsonProperty("dateTime")
    private OffsetDateTime dateTime;

    /**
     * 同一个报警已经上传的次数 (可选)
     */
    @JsonProperty("activePostCount")
    private Integer activePostCount;

    /**
     * 事件类型, 例如: "GaugeReadingEvent" (必需)
     */
    @JsonProperty("eventType")
    private String eventType;

    /**
     * 事件状态: active - 有效事件, inactive - 无效事件 (必需)
     */
    @JsonProperty("eventState")
    private String eventState;

    /**
     * 事件描述 (必需)
     */
    @JsonProperty("eventDescription")
    private String eventDescription;

    /**
     * 设备ID (可选)
     */
    @JsonProperty("deviceID")
    private String deviceID;

    /**
     * 设备编号，设备唯一标识 (可选)
     */
    @JsonProperty("deviceUUID")
    private String deviceUUID;

    /**
     * 重传数据标记, true表示该数据为网络恢复后重新上传的 (可选)
     */
    @JsonProperty("isDataRetransmission")
    private Boolean isDataRetransmission;

    /**
     * 上传事件唯一标识 (可选)
     */
    @JsonProperty("UUID")
    private String uuid;

    /**
     * 事件内容 (必需)
     */
    @JsonProperty("GaugeReadingEvent")
    private GaugeReadingEventDetails gaugeReadingEvent;

    // Getters and Setters
}