package com.zd.sdq.entity.dto.incline;

/** 单个倾角测点的固定基准，单位为度。 */
public record InclineBaseline(Double angleX, Double angleY, String updatedAt) {
    public InclineBaseline {
        if (angleX == null || angleY == null || !Double.isFinite(angleX) || !Double.isFinite(angleY)) {
            throw new IllegalArgumentException("X、Y 基准值必须为有效数字");
        }
        if (updatedAt == null || updatedAt.isBlank()) {
            throw new IllegalArgumentException("基准值缺少设置时间");
        }
    }
}
