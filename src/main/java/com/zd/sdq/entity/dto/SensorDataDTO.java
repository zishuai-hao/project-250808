package com.zd.sdq.entity.dto;

import com.zd.sdq.entity.SensorData;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * @author hzs
 * @date 2023/12/21
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class SensorDataDTO extends SensorData {
    private String remotePointCode;
    private Integer channelNo;
    private String codeNo;



}
