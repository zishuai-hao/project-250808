package com.zd.sdq.service.incline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zd.sdq.config.DeviceReportConfig.Simulation;
import com.zd.sdq.entity.InclineForwardData;
import com.zd.sdq.entity.dto.DeviceReportSample;
import com.zd.sdq.mapper.InclineForwardDataMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;

/** 有界AR(1)及历史残差向量重采样；仅使用真实记录，不保存生成结果。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HistoricalFluctuationSimulator {
    public static final String METHOD = "HISTORICAL_AR1";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final InclineForwardDataMapper mapper;
    private final ObjectMapper json;
    private final InclineFluctuationService inclineFluctuationService;

    public record Result(List<String> numbers, int trainingCount) { }
    private record Point(Instant time, double[] numbers, double[] baseline, JsonNode zero) { }

    public Optional<Result> simulate(DeviceReportSample sample, Instant reportTime, Simulation config) {
        try {
            Instant sourceTime = time(sample.getDataTime());
            if (!config.isEnabled() || reportTime.isBefore(sourceTime)
                    || Duration.between(sourceTime, reportTime).compareTo(config.getMaxSourceAge()) > 0) {
                return Optional.empty();
            }
            boolean incline = "INC".equals(sample.getDeviceType());
            if (!incline && !"VIB".equals(sample.getDeviceType())) return Optional.empty();
            List<InclineForwardData> rows = mapper.selectSimulationHistory(sample.getDeviceCode(),
                    sample.getRemoteDeviceId(), sample.getDeviceType(),
                    TIME.format(sourceTime.minus(config.getHistoryWindow()).atZone(ZONE)),
                    sample.getDataTime(), config.getHistorySize());
            List<Point> history = new ArrayList<>();
            double[] anchorValues = values(json.readTree(sample.getPayload()), incline);
            Point anchor = null;
            for (InclineForwardData row : rows) {
                Point point;
                try {
                    point = point(row, incline);
                } catch (Exception invalid) {
                    break; // 不跨越无效、未知状态或故障点建立相邻关系。
                }
                if (anchor == null) {
                    if (!point.time().equals(sourceTime) || !Arrays.equals(point.numbers(), anchorValues)) {
                        return Optional.empty(); // 并发改写了同一源时间的快照，等下一刻度再取数。
                    }
                    anchor = point;
                } else {
                    Point newer = history.get(history.size() - 1);
                    Duration gap = Duration.between(point.time(), newer.time());
                    if (gap.isNegative() || gap.isZero() || gap.compareTo(config.getMaxHistoryGap()) > 0
                            || !sameBaseline(newer, point)) break;
                }
                history.add(point);
            }
            if (history.size() < config.getMinHistorySize()) return Optional.empty();
            Collections.reverse(history);
            return generate(sample.getDeviceCode(), history, reportTime, config, incline);
        } catch (Exception e) {
            log.warn("模拟学习失败，跳过测点: {}", sample.getDeviceCode(), e);
            return Optional.empty();
        }
    }

    private Point point(InclineForwardData row, boolean incline) throws Exception {
        JsonNode raw = json.readTree(row.getRawData());
        if (raw == null || !raw.path("runStatus").isIntegralNumber() || raw.path("runStatus").asInt() != 0
                || !raw.path("alarmStatus").isIntegralNumber() || raw.path("alarmStatus").asInt() != 0) {
            throw new IllegalArgumentException("仅使用明确正常且无报警的真实记录学习");
        }
        double[] numbers = values(json.readTree(row.getPayload()), incline);
        double[] baseline = new double[0];
        if (incline) {
            if (!raw.path("angleX").isNumber() || !raw.path("angleY").isNumber()) {
                throw new IllegalArgumentException("缺少原始倾角，不能识别基准切换");
            }
            // 累加后的payload满足 k*raw-payload 为段内常数，不需要重放状态或再次放大入库值。
            var amplified = inclineFluctuationService.historyReference(
                    raw.path("angleX").asDouble(), raw.path("angleY").asDouble());
            baseline = new double[]{amplified.x() - numbers[0], amplified.y() - numbers[1]};
            if (!Double.isFinite(baseline[0]) || !Double.isFinite(baseline[1])) {
                throw new IllegalArgumentException("无效倾角基准");
            }
        }
        return new Point(time(row.getDataTime()), numbers, baseline, raw.path("zero"));
    }

    private boolean sameBaseline(Point anchor, Point point) {
        if (!anchor.zero().equals(point.zero())) return false;
        for (int d = 0; d < anchor.baseline().length; d++) {
            // 比较相邻点的段内常数，允许6位小数的逐次舍入误差，不累积对比远端锚点。
            if (Math.abs(anchor.baseline()[d] - point.baseline()[d]) > 0.0000015) return false;
        }
        return true;
    }

    private double[] values(JsonNode payload, boolean incline) {
        int dimensions = incline ? 3 : 1;
        if (payload == null || !payload.isArray() || payload.size() != 1 || !payload.get(0).isArray()
                || payload.get(0).size() != dimensions + 1) throw new IllegalArgumentException("无效学习快照");
        double[] values = new double[dimensions];
        for (int d = 0; d < dimensions; d++) {
            values[d] = Double.parseDouble(payload.get(0).get(d + 1).asText());
            if (!Double.isFinite(values[d])) throw new IllegalArgumentException("非有限学习数值");
        }
        return values;
    }

    private Optional<Result> generate(String code, List<Point> history, Instant reportTime,
                                       Simulation config, boolean incline) {
        int count = history.size();
        int dimensions = history.get(0).numbers().length;
        double[] mean = new double[dimensions];
        double[] minimum = history.get(0).numbers().clone();
        double[] maximum = minimum.clone();
        double[] maxStep = new double[dimensions];
        double[] phi = new double[dimensions];
        double[][] residuals = new double[count - 1][dimensions];
        long[] intervals = new long[count - 1];
        for (int i = 0; i < count; i++) {
            for (int d = 0; d < dimensions; d++) {
                double value = history.get(i).numbers()[d];
                mean[d] += (value - mean[d]) / (i + 1);
                minimum[d] = Math.min(minimum[d], value);
                maximum[d] = Math.max(maximum[d], value);
            }
            if (i > 0) intervals[i - 1] = Duration.between(history.get(i - 1).time(), history.get(i).time()).toMillis();
        }
        boolean varying = false;
        for (int d = 0; d < dimensions; d++) {
            double covariance = 0;
            double variance = 0;
            double[] steps = new double[count - 1];
            for (int i = 1; i < count; i++) {
                double previous = history.get(i - 1).numbers()[d] - mean[d];
                covariance += previous * (history.get(i).numbers()[d] - mean[d]);
                variance += previous * previous;
                steps[i - 1] = Math.abs(history.get(i).numbers()[d] - history.get(i - 1).numbers()[d]);
            }
            phi[d] = variance == 0 ? 0 : clamp(covariance / variance, -0.95, 0.95);
            Arrays.sort(steps);
            maxStep[d] = steps[(int) Math.ceil(0.95 * steps.length) - 1];
            varying |= maximum[d] > minimum[d];
            double residualMean = 0;
            for (int i = 1; i < count; i++) {
                residuals[i - 1][d] = history.get(i).numbers()[d] - mean[d]
                        - phi[d] * (history.get(i - 1).numbers()[d] - mean[d]);
                residualMean += residuals[i - 1][d] / (count - 1);
            }
            for (double[] residual : residuals) residual[d] -= residualMean;
            if (!Double.isFinite(mean[d]) || !Double.isFinite(phi[d]) || !Double.isFinite(maxStep[d])) {
                return Optional.empty();
            }
        }
        if (!varying) return Optional.empty(); // 历史没有波动时不凭空制造波动。
        Arrays.sort(intervals);
        Point anchor = history.get(count - 1);
        double remaining = (double) Duration.between(anchor.time(), reportTime).toMillis() / intervals[intervals.length / 2];
        if (!Double.isFinite(remaining) || remaining <= 0 || remaining > 256) return Optional.empty();
        double[] generated = anchor.numbers().clone();
        SplittableRandom random = new SplittableRandom(config.getSeed() ^ anchor.time().toEpochMilli()
                ^ ((long) code.hashCode() << 32));
        while (remaining > 0.000000001) {
            double dt = Math.min(1, remaining);
            double[] residual = residuals[random.nextInt(residuals.length)]; // 整个向量重采样，保留轴间残差关联。
            for (int d = 0; d < dimensions; d++) {
                double drift = mean[d] + phi[d] * (generated[d] - mean[d]) - generated[d];
                double step = drift * dt + residual[d] * Math.sqrt(dt) * config.getVolatilityScale();
                step = clamp(step, -maxStep[d] * dt, maxStep[d] * dt);
                generated[d] = clamp(generated[d] + step, minimum[d], maximum[d]);
                if (!Double.isFinite(generated[d])) return Optional.empty();
            }
            remaining -= dt;
        }
        List<String> numbers = new ArrayList<>();
        for (int d = 0; d < dimensions; d++) {
            int scale = incline ? (d == 2 ? 3 : 6) : 4;
            numbers.add(BigDecimal.valueOf(generated[d]).setScale(scale, RoundingMode.HALF_UP).toPlainString());
        }
        return Optional.of(new Result(numbers, count));
    }

    private static Instant time(String value) {
        return LocalDateTime.parse(value, TIME).atZone(ZONE).toInstant();
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
