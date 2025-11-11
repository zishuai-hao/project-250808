package com.zd.sdq.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 雷达水位仪配置
 * @author hzs
 * @date 2024/01/01
 */
@Data
@Component
@ConfigurationProperties(prefix = "radar.water.level")
public class RadarWaterLevelConfig {
    
    /**
     * 监控间隔 (毫秒)
     */
    private long monitorInterval = 1000;
    
    /**
     * 监控路径
     */
    private String monitorPath = "./data/radar";
    
    /**
     * 数据文件扩展名
     */
    private String fileExtension = ".dat";
    
    /**
     * 水位值范围 (mm)
     */
    private double minWaterLevel = -10000;
    private double maxWaterLevel = 10000;
    
    /**
     * 温度值范围 (°C)
     */
    private double minTemperature = -50;
    private double maxTemperature = 100;
    
    /**
     * 信号强度范围 (dB)
     */
    private double minSignalStrength = -100;
    private double maxSignalStrength = 0;
    
    /**
     * TCP客户端配置
     */
    private String tcpHost = "localhost";
    private int tcpPort = 4023;
    private int tcpTimeout = 5000;
    private long tcpQueryInterval = 60000;
    
    /**
     * TCP服务器配置
     */
    private int tcpServerPort = 4024;
    private int tcpServerMaxConnections = 10;
} 