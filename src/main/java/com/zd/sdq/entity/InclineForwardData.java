package com.zd.sdq.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** INC/VIB采集处理快照，供定时上报查询，表名沿用旧版本。 */
@Data
@lombok.EqualsAndHashCode(callSuper = true)
@TableName("incline_forward_data")
public class InclineForwardData extends DeviceReportState {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String deviceCode;
    private String remoteDeviceId;
    private String deviceType;
    /** 接口返回的采集时间。 */
    private String dataTime;
    /** 新记录为原始采集时间戳；旧记录可能为构建时间，不用于有效期查询。 */
    private Long sampleTime;
    /** 原始采集时间、基准扣减/换算及舍入后的值；定时发送时仅替换报文副本的时间。 */
    private String payload;
    /** 接口单条记录的 JSON，保留原始角度、温度、加速度及状态等。 */
    private String rawData;
    private String createTime;
}
