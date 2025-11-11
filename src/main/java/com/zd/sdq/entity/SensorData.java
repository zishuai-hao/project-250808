package com.zd.sdq.entity;

import lombok.Data;

import java.util.Date;

/**
 * @author hzs
 * @date 2023/12/18
 */
@Data
public class SensorData {
    private Integer id;
    private String deviceKey;
    private String dataKey;
    private Double dataValue;
    private Long dataTime;
}
