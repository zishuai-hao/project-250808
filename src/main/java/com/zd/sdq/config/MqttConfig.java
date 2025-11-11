package com.zd.sdq.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * @author hzs
 * @date 2023/12/08
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "mqtt-config")
public class MqttConfig {

    private String url;
    private Integer port;
    private String topic;
    private String username;
    private String password;

}