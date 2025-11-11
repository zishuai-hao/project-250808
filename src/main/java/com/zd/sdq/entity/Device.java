package com.zd.sdq.entity;

import lombok.Data;

/**
 * @author hzs
 * @date 2023/12/08
 */
@Data
public class Device {

    private Integer id;
    private Integer deviceKey;
    private String deviceName;
    private String deviceType;
    private Integer deviceTypeId;
    private String ip;
    private String port;
    private String cjyNo;
    private String factorName;
    /**
     * 远程点号
     */
    private String remotePointCode;
    private Integer remoteChannelNo;
    private String remoteCodeNo;
    private String ext;
    private String remark;
    /**
     * 数据源: 文件/HTTP
     */
    private String dataSource;
}
