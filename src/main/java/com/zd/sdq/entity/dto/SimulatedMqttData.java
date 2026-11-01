package com.zd.sdq.entity.dto;

import com.company.cbf.starter.data.entity.MqttData;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.util.List;

/** 模拟条目保留显式质量标识，不作为新的实测记录落库。 */
@Getter
public class SimulatedMqttData extends MqttData {
    private final boolean simulated = true;
    /** 最近真实采集时间，UTC毫秒时间戳。 */
    @JsonProperty("source_time")
    private final Long sourceTime;
    @JsonProperty("simulation_method")
    private final String simulationMethod;

    public SimulatedMqttData(String deviceCode, String deviceType, List<List<String>> values, Long sourceTime,
                              String simulationMethod) {
        super(null, deviceCode, deviceType, values);
        this.sourceTime = sourceTime;
        this.simulationMethod = simulationMethod;
    }
}
