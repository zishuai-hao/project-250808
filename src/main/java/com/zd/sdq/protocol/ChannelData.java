package com.zd.sdq.protocol;

import lombok.Data;

@Data
public class ChannelData {
    String monitoring_point_code; // 测点编号
    Integer channel_no; // 通道编号
    Long sample_time; // 采集时间
    Double value;
    String code_no; // 数据类型
}
