package com.zd.sdq.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 倾角接口数据在进入转发缓冲区前的快照。 */
@Data
@TableName("incline_forward_data")
public class InclineForwardData {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String deviceCode;
    private String remoteDeviceId;
    private String deviceType;
    /** 接口返回的采集时间。 */
    private String dataTime;
    /** 转发数据构建时的时间戳。 */
    private Long sampleTime;
    /** 与 push 的 value 参数一致，包含发送时间、换算及舍入后的值。 */
    private String payload;
    /** 接口单条记录的 JSON，保留原始角度、温度、加速度及状态等。 */
    private String rawData;
    private String createTime;
}
