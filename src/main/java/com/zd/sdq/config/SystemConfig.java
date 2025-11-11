package com.zd.sdq.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * @author hzs
 * @date 2023/12/23
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "system-config")
public class SystemConfig {
    boolean httpDataEnable = true;
    boolean gnssDataEnable = true;
    boolean wsdjDataEnable = true;
    boolean mqttDataEnable = true;
    boolean windDataEnable = true;
    boolean daasDataEnable = true;
}
