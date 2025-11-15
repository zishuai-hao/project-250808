package com.zd.sdq.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 倾角传感器 API 配置
 * @author hzs
 * @date 2025/11/13
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "incline.api")
public class InclineApiConfig {
    /**
     * API 基础地址
     */
    private String baseUrl = "https://www.zhtk-iot.com:8543/api";
    
    /**
     * 用户名
     */
    private String username = "dxkj";
    
    /**
     * 密码
     */
    private String password = "dxkj250808";
    
    /**
     * 连接超时时间（秒）
     */
    private Integer connectTimeout = 30;
    
    /**
     * 读取超时时间（秒）
     */
    private Integer readTimeout = 30;
    
    /**
     * 写入超时时间（秒）
     */
    private Integer writeTimeout = 30;
    
    /**
     * Token 刷新间隔（分钟）
     * 默认每2小时刷新一次
     */
    private Integer tokenRefreshInterval = 120;

    public String getLoginUrl() {
        return baseUrl + "/login";
    }

    // 传感器列表接口
    public String getSensorListUrl() {
        return baseUrl + "/DataProvision/sensorList";
    }

    // 历史数据接口
    public String getHistoryDataUrl() {
        return baseUrl + "/DataProvision/getRtHistoryData";
    }

}

