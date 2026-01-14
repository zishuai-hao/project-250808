package com.zd.sdq.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 倾角传感器 API 配置
 *
 * @author hzs
 * @date 2025/11/13
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "radar.water")
public class WaterLevelConfig {

    private boolean enable = false;
    /**
     * CSV文件路径
     */
    private String csvFilePath;
    /**
     * 设备编码
     */
    private String deviceCode = "YDXH-WLV-P01-001-02";

    private String sourceDeviceCode = "CBXH-WLV-P01-001-02";
}

