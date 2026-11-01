package com.zd.sdq.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 全体倾角通道使用同一增量倍率，无固定中心。 */
@Data
@Configuration
@ConfigurationProperties(prefix = "incline.fluctuation")
public class InclineFluctuationConfig {
    private boolean enabled = false;
    private double factor = 2.0;

    @PostConstruct
    public void validate() {
        if (!Double.isFinite(factor) || factor < 1) {
            throw new IllegalArgumentException("incline.fluctuation.factor 必须是大于等于1的有限数");
        }
    }
}
