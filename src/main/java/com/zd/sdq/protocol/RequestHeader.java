package com.zd.sdq.protocol;

import lombok.Data;

import static com.zd.sdq.util.ByteUtil.getInt;
import static com.zd.sdq.util.ByteUtil.getString;
import static java.lang.Byte.toUnsignedInt;

@Data
public class RequestHeader {
    private String deviceId;
    private Byte deviceType;
    private Integer packetCount;
    private Integer packetIndex;
    private Byte power;
    private Byte version;
    private Integer firmwareVersion;
    private Byte collectFrequencyHigh; // 采集粒度高字节 年
    private Byte collectFrequencyLow; // 年
    private Byte stackCountHigh; // 积压数据高字节 月
    private Byte stackCountLow; // 日
    private Byte workCountHigh; // 工作次数高字节 时
    private Byte workCountLow; // 分
    private Byte firmwareLocation; //固件存储位置 秒
    private Byte dataType; // 0 二进制，1 json, 2 混合
    private Integer length;

    public static RequestHeader decode(byte[] bytes) {
        final RequestHeader requestHeader = new RequestHeader();
        int index = 0;
        requestHeader.deviceId = getString(bytes, index, index += 14);
        requestHeader.deviceType = bytes[index++];
        requestHeader.packetCount = getInt(bytes, index++, index++);
        requestHeader.packetIndex = getInt(bytes, index++, index++);
        requestHeader.length = getInt(bytes, 29, 30);
        return requestHeader;
    }


}