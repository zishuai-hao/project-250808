package com.zd.sdq.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashSet;
import java.util.Set;

/**
 * 振动孤立尖峰过滤配置，加速度阈值单位为 m/s²。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "vibration.filter")
public class VibrationFilterConfig {
    private boolean enabled = true;

    // 中间点的绝对幅值需比前后两点分别至少增大此值，不是绝对值上限。
    private double spikeMinDelta = 2.0;

    // 相对分支：同时满足历史基线倍数及相对邻点的最小绝对增量。
    private double spikeRatio = 10.0;
    private double relativeMinDelta = 0.05;
    private double noiseFloor = 0.001;
    private int baselineWindowSize = 24;

    // 前后两点的有符号加速度差不超过此值，才认为恢复到原基准。
    private double returnTolerance = 0.5;

    // 超过相邻采集时间间隔，或疑似尖峰等待超过此时长，都保留原值。
    private int maxGapSeconds = 90 * 60;

    private Set<String> deviceCodes = new HashSet<>(Set.of("ZYXH-VIB-P01-002-02", "CBXH-VIB-P01-001-03"));

    @PostConstruct
    public void validate() {
        if (!Double.isFinite(spikeMinDelta) || spikeMinDelta <= 0) {
            throw new IllegalArgumentException("vibration.filter.spike-min-delta 必须为有限正数，单位 m/s²");
        }
        if (!Double.isFinite(spikeRatio) || spikeRatio <= 1) {
            throw new IllegalArgumentException("vibration.filter.spike-ratio 必须为大于 1 的有限数");
        }
        if (!Double.isFinite(relativeMinDelta) || relativeMinDelta <= 0) {
            throw new IllegalArgumentException("vibration.filter.relative-min-delta 必须为有限正数，单位 m/s²");
        }
        if (!Double.isFinite(noiseFloor) || noiseFloor <= 0) {
            throw new IllegalArgumentException("vibration.filter.noise-floor 必须为有限正数，单位 m/s²");
        }
        if (baselineWindowSize <= 0) {
            throw new IllegalArgumentException("vibration.filter.baseline-window-size 必须为正数");
        }
        if (!Double.isFinite(returnTolerance) || returnTolerance < 0 || returnTolerance >= spikeMinDelta) {
            throw new IllegalArgumentException("vibration.filter.return-tolerance 必须为有限非负数，且小于 spike-min-delta");
        }
        if (maxGapSeconds <= 0) {
            throw new IllegalArgumentException("vibration.filter.max-gap-seconds 必须为正数");
        }
        if (deviceCodes == null) {
            throw new IllegalArgumentException("vibration.filter.device-codes 不能为 null，可用 [] 表示不对任何测点过滤尖峰");
        }
    }
}
