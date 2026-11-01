package com.zd.sdq.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.time.ZoneId;

@Data
@Configuration
@ConfigurationProperties(prefix = "device-report")
public class DeviceReportConfig {
    private String zone = "Asia/Shanghai";
    private InclineVibGroup inclineVib = new InclineVibGroup();
    private Group waterLevel = new Group("0 0 * * * *", Duration.ofMinutes(75));

    @PostConstruct
    public void validate() {
        ZoneId.of(zone);
        validateGroup(inclineVib);
        validateGroup(waterLevel);
        Simulation simulation = inclineVib.getSimulation();
        if (simulation == null || !positiveWholeSeconds(simulation.getMaxSourceAge())) {
            throw new IllegalArgumentException("device-report.incline-vib.simulation.max-source-age 必须为正整数秒时长");
        }
        if (simulation.isEnabled() && simulation.getMaxSourceAge().compareTo(inclineVib.getMaxAge()) <= 0) {
            throw new IllegalArgumentException("模拟源数据最大时长必须大于真实数据有效期");
        }
        if (!positiveWholeSeconds(simulation.getHistoryWindow()) || !positiveWholeSeconds(simulation.getMaxHistoryGap())
                || simulation.getMinHistorySize() < 6 || simulation.getHistorySize() < simulation.getMinHistorySize()
                || simulation.getHistorySize() > 1000 || !Double.isFinite(simulation.getVolatilityScale())
                || simulation.getVolatilityScale() <= 0 || simulation.getVolatilityScale() > 1) {
            throw new IllegalArgumentException("模拟学习窗口/间隔必须为正整数秒；学习条数须满足6 <= min-history-size <= history-size <= 1000；volatility-scale须在(0,1]内");
        }
    }

    private void validateGroup(Group group) {
        if (group == null || !positiveWholeSeconds(group.getMaxAge())) {
            throw new IllegalArgumentException("device-report.max-age 必须为正整数秒时长");
        }
        if (!"-".equals(group.getCron())) {
            CronExpression.parse(group.getCron());
        }
    }

    private boolean positiveWholeSeconds(Duration duration) {
        return duration != null && !duration.isNegative() && !duration.isZero() && duration.getNano() == 0;
    }

    @Getter
    @Setter
    public static class InclineVibGroup extends Group {
        private Simulation simulation = new Simulation();

        public InclineVibGroup() {
            super("0 0 * * * *", Duration.ofHours(1));
        }
    }

    @Data
    public static class Simulation {
        private boolean enabled = true;
        /** 始终相对最近真实采集时间计算，不能用模拟时间延长窗口。 */
        private Duration maxSourceAge = Duration.ofHours(4);
        private Duration historyWindow = Duration.ofHours(48);
        private int historySize = 24;
        private int minHistorySize = 6;
        private Duration maxHistoryGap = Duration.ofMinutes(90);
        private double volatilityScale = 1.0;
        /** 同一测点、真实源时间及学习窗口重建同一随机路径，重启或重复计算不跳变。 */
        private long seed = 20261101L;
    }

    @Data
    public static class Group {
        /** 使用 - 可关闭本组上报，采集和落库继续运行。 */
        private String cron;
        private Duration maxAge;

        public Group(String cron, Duration maxAge) {
            this.cron = cron;
            this.maxAge = maxAge;
        }
    }
}
