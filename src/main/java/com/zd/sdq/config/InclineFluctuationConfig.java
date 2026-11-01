package com.zd.sdq.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/** 全体倾角通道使用同一放大倍率；每台设备保存固定的原始角度中心。 */
@Data
@Configuration
@ConfigurationProperties(prefix = "incline.fluctuation")
public class InclineFluctuationConfig {
    private boolean enabled = false;
    private double factor = 2.0;
    private Map<String, Center> centers = new LinkedHashMap<>();

    @Data
    public static class Center {
        private Double x;
        private Double y;
    }

    @PostConstruct
    public void validate() {
        if (!Double.isFinite(factor) || factor < 1) {
            throw new IllegalArgumentException("incline.fluctuation.factor 必须是大于等于1的有限数");
        }
        if (centers == null) throw new IllegalArgumentException("incline.fluctuation.centers 不能为 null");
        centers.forEach((code, center) -> {
            if (code == null || code.isBlank() || center == null
                    || center.getX() == null || !Double.isFinite(center.getX())
                    || center.getY() == null || !Double.isFinite(center.getY())) {
                throw new IllegalArgumentException("倾角放大中心必须包含有效测点及有限X/Y角度: " + code);
            }
        });
    }
}
