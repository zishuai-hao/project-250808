package com.zd.sdq.entity.dto;

import java.util.List;

/** 真实采集快照的观测曲线；时间统一为 Unix 毫秒，缺失数值为 null。 */
public record DeviceHistoryDTO(String deviceCode, String deviceType, long fromTime, long toTime,
                               List<Series> series, List<Point> points, boolean truncated) {
    public record Series(String name, String unit, int precision) {}
    public record Point(long time, List<Double> values) {}
}
