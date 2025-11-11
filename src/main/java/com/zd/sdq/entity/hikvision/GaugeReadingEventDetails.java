package com.zd.sdq.entity.hikvision;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 水尺水位检测事件的具体内容
 */
@Data
public class GaugeReadingEventDetails {

    /**
     * 水尺事件触发类型: waterLevelTiming - 水位定时报, waterLevel - 水位加报 (必需)
     */
    @JsonProperty("gaugeReadingTriggerType")
    private String gaugeReadingTriggerType;

    /**
     * 观测时间（报警触发时间） (必需)
     */
    @JsonProperty("observationTime")
    private OffsetDateTime observationTime;

    /**
     * 水文遥测站地址编码 (必需)
     */
    @JsonProperty("telemetryStationAddressCode")
    private String telemetryStationAddressCode;

    /**
     * 水文(遥测站)分类编码, 例如: 48H#河道 (必需)
     */
    @JsonProperty("classificationCode")
    private String classificationCode;

    /**
     * 当前水位信息，单位：米 (m)，保留三位小数 (可选)
     */
    @JsonProperty("waterLevel")
    private Double waterLevel;

    /**
     * 当前降雨量信息，单位：毫米 (mm)，保留一位小数 (可选)
     */
    @JsonProperty("rainfall")
    private Double rainfall;

    /**
     * 当前电压信息，单位：伏 (v)，保留两位小数 (可选)
     */
    @JsonProperty("voltage")
    private Double voltage;

    /**
     * 该报文上传的中心站的地址列表 (可选)
     */
    @JsonProperty("centralStationAddr")
    private List<Integer> centralStationAddr;

    /**
     * 水位检测抓图 (可选)
     */
    @JsonProperty("image")
    private ImageDetails image;

    /**
     * 是否超出高水位线，只有配置了水位检测线参数才会上报 (可选)
     */
    @JsonProperty("isExceedHighWaterLevelLine")
    private Boolean isExceedHighWaterLevelLine;

    /**
     * 视频复核场景图 (可选)
     */
    @JsonProperty("videoRecheckImage")
    private ImageDetails videoRecheckImage;

}