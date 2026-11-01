package com.zd.sdq.service.incline;

import com.zd.sdq.config.InclineFluctuationConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/** 在采集构建payload之前，对原始角度围绕固定中心作一次统一倍率缩放。 */
@Service
@RequiredArgsConstructor
public class InclineFluctuationService {
    private final InclineFluctuationConfig config;

    public Angles apply(String deviceCode, double rawX, double rawY) {
        if (!Double.isFinite(rawX) || !Double.isFinite(rawY)) {
            throw new IllegalArgumentException("倾角原始值必须为有限数");
        }
        var center = config.isEnabled() ? config.getCenters().get(deviceCode) : null;
        if (center == null || config.getFactor() == 1.0) return new Angles(rawX, rawY);
        return new Angles(scale(rawX, center.getX()), scale(rawY, center.getY()));
    }

    private double scale(double raw, double center) {
        BigDecimal b = BigDecimal.valueOf(center);
        double result = b.add(BigDecimal.valueOf(raw).subtract(b)
                .multiply(BigDecimal.valueOf(config.getFactor()))).doubleValue();
        if (!Double.isFinite(result)) throw new IllegalArgumentException("倾角放大结果超出有限数值范围");
        return result;
    }

    public record Angles(double x, double y) {}
}
