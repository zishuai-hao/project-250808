package com.zd.sdq.protocol;

import lombok.Data;

/**
 * @author hzs
 * @date 2023/12/02
 */
@Data
public class ResponseHeader {
    String id;
    byte frameType;
    Short packetCount;
     Short packetIndex;
    private Byte collectFrequencyHigh; // 采集粒度高字节 以分钟为单位
    private Byte collectFrequencyLow; // 年
    String date; // 7 byte yyMdHms
    Byte dataType;  // 数据类型 0=二进制，适用于固件升级；1=json，适用于下发采集配置
    Short length;
}
