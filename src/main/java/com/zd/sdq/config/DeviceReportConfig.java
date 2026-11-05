package com.zd.sdq.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.support.CronExpression;

import java.time.ZoneId;

@Data
@Configuration
@ConfigurationProperties(prefix = "device-report")
public class DeviceReportConfig {
    private String zone = "Asia/Shanghai";
    private Group inclineVib = new Group();
    private Group waterLevel = new Group();
    private String retryCron = "0 */5 * * * *";

    @PostConstruct
    public void validate() {
        ZoneId.of(zone);
        validateGroup(inclineVib);
        validateGroup(waterLevel);
        if (!"-".equals(retryCron)) CronExpression.parse(retryCron);
    }

    private void validateGroup(Group group) {
        if (group == null || (!"-".equals(group.getCron()) && !"0 0 * * * *".equals(group.getCron()))) {
            throw new IllegalArgumentException("设备上报 cron 必须为每小时整点 0 0 * * * *，或使用 - 关闭");
        }
    }

    @Data
    public static class Group {
        /** 关闭后，本组的正常发送和补发均停止，采集仍继续。 */
        private String cron = "0 0 * * * *";
    }
}
