package com.zd.sdq.service.device;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

/**
 * @author hzs
 * @date 2023/12/10
 */
@Data
public class DeviceExcelEntity {
    @ExcelProperty("监测内容")
    private String content;

    @ExcelProperty("设备编号")
    private String deviceNumber;

    @ExcelProperty("安装位置")
    private String installationLocation;

    @ExcelProperty("截面")
    private String section;

    @ExcelProperty("传感器是否接入通道")
    private boolean sensorConnected;

    @ExcelProperty("设备SN号")
    private String deviceSN;

    @ExcelProperty("地址")
    private String address;

    @ExcelProperty("采集通道")
    private String collectionChannel;

    @ExcelProperty("子通道")
    private String subChannel;

    @ExcelProperty("IP")
    private String ip;

    @ExcelProperty("采集仪号")
    private String collectionInstrumentNumber;

    @ExcelProperty("机柜号")
    private String cabinetNumber;
}