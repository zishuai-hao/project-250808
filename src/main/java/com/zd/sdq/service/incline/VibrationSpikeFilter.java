package com.zd.sdq.service.incline;

import cn.hutool.core.date.DateUtil;
import com.zd.sdq.config.VibrationFilterConfig;
import com.zd.sdq.entity.DeviceInfoExt;
import com.zd.sdq.entity.dto.incline.InclineHistoryResponse.InclineRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 按采集时间输入记录，仅在前后邻点确认后丢弃孤立尖峰。
 * 每个测点最多暂存一个待确认点，连续高值原样放行。
 */
@Slf4j
@Component
public class VibrationSpikeFilter {
    public static final double MG_TO_M_S2 = 0.00980665;

    private final VibrationFilterConfig config;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public VibrationSpikeFilter(VibrationFilterConfig config) {
        this(config, Clock.systemUTC());
    }

    public VibrationSpikeFilter(VibrationFilterConfig config, Clock clock) {
        this.config = config;
        this.clock = clock;
    }

    /** 转发失败时回滚窗口，下一轮重试仍能拿到原记录。 */
    public void process(DeviceInfoExt device, InclineRecord record, Consumer<Sample> forward) {
        Window window = windows.computeIfAbsent(device.getDeviceCode(), code -> new Window());
        synchronized (window) {
            Window before = window.copy();
            try {
                for (Sample sample : accept(window, device, record)) {
                    forward.accept(sample);
                }
            } catch (RuntimeException | Error e) {
                window.restore(before);
                throw e;
            }
        }
    }

    private List<Sample> accept(Window window, DeviceInfoExt device, InclineRecord record) {
        List<Sample> ready = new ArrayList<>(2);
        Sample sample = new Sample(device, record);
        Double raw = record.getAccMaxZ();
        Double acceleration = raw == null ? null : raw * MG_TO_M_S2;
        boolean enabled = config.isEnabled() && config.getDeviceCodes().contains(device.getDeviceCode());
        long time = DateUtil.parse(record.getTm()).getTime();
        if (time <= window.lastSeenTime) {
            return ready;
        }
        window.lastSeenTime = time;
        if (!valid(acceleration)) {
            // 无效记录打断相邻关系，不能跨过它确认前一个点是孤立尖峰。
            releasePending(window, ready);
            resetContext(window, null);
            logInvalid(sample);
            return ready;
        }
        Point current = new Point(sample, acceleration, time);
        if (!enabled || window.previous == null || (window.pending == null
                && time - window.previous.time() > maxGapMillis())
                || (window.pending != null && (time - window.pending.time() > maxGapMillis()
                || clock.millis() - window.pendingSince >= maxGapMillis()))) {
            releasePending(window, ready);
            resetContext(window, current);
            ready.add(sample);
            return ready;
        }

        if (!normal(current) || !normal(window.previous)) {
            releasePending(window, ready);
            resetContext(window, current);
            ready.add(sample);
            return ready;
        }

        if (window.pending != null) {
            Point pending = window.pending;
            boolean recovered = recovered(window, current);
            if (recovered && largerThan(pending, current, window.excursionBaseline)) {
                log.warn("过滤孤立振动尖峰：测点[{}]，SN[{}]，采集时间[{}]，accMaxZ原值[{}]mG，前/中/后[{} / {} / {}]m/s²，历史幅值中位数[{}]m/s²，accMaxX[{}]mG，accMaxY[{}]mG，runStatus[{}]，alarmStatus[{}]",
                        device.getDeviceCode(), device.getRemoteDeviceId(), pending.sample().record().getTm(),
                        pending.sample().record().getAccMaxZ(), window.previous.acceleration(), pending.acceleration(),
                        acceleration, window.excursionBaseline, pending.sample().record().getAccMaxX(), pending.sample().record().getAccMaxY(),
                        pending.sample().record().getRunStatus(), pending.sample().record().getAlarmStatus());
                window.pending = null;
            } else {
                // 第二条仍偏高时立即放行两条，并持续放行本轮高值，直到恢复基准。
                releasePending(window, ready);
            }
            if (recovered) {
                window.excursionAnchor = null;
                remember(window, current);
            }
            window.previous = current;
            ready.add(sample);
        } else if (window.excursionAnchor != null) {
            // 已确认连续高值，本轮后续即使继续升高也不再暂存或丢弃。
            if (recovered(window, current)) {
                window.excursionAnchor = null;
                remember(window, current);
            }
            window.previous = current;
            ready.add(sample);
        } else if (largerThan(current, window.previous, baseline(window))) {
            window.pending = current;
            window.pendingSince = clock.millis();
            window.excursionAnchor = window.previous;
            window.excursionBaseline = baseline(window);
        } else {
            window.previous = current;
            remember(window, current);
            ready.add(sample);
        }
        return ready;
    }

    /** 没有后续数据时无法确认孤立性，超时后按原值放行。 */
    public void flushExpired(Consumer<Sample> forward) {
        for (Window window : windows.values()) {
            synchronized (window) {
                if (window.pending != null && clock.millis() - window.pendingSince >= maxGapMillis()) {
                    log.warn("振动尖峰待确认超时，保留原值：测点[{}]，采集时间[{}]，accMaxZ[{}]mG",
                            window.pending.sample().device().getDeviceCode(), window.pending.sample().record().getTm(),
                            window.pending.sample().record().getAccMaxZ());
                    // 先成功入库/转发，再清除待确认点；失败时保留以便下次重试。
                    forward.accept(window.pending.sample());
                    resetContext(window, null);
                }
            }
        }
    }

    private boolean largerThan(Point peak, Point neighbor, double baseline) {
        double magnitude = Math.abs(peak.acceleration());
        double delta = magnitude - Math.abs(neighbor.acceleration());
        return delta >= config.getSpikeMinDelta()
                || (delta >= config.getRelativeMinDelta()
                && magnitude / Math.max(baseline, config.getNoiseFloor()) >= config.getSpikeRatio());
    }

    private boolean recovered(Window window, Point current) {
        return !largerThan(current, window.excursionAnchor, window.excursionBaseline)
                && Math.abs(window.excursionAnchor.acceleration() - current.acceleration()) <= config.getReturnTolerance();
    }

    private double baseline(Window window) {
        List<Double> magnitudes = new ArrayList<>(window.history);
        magnitudes.sort(Double::compareTo);
        int middle = magnitudes.size() / 2;
        return magnitudes.size() % 2 == 0
                ? magnitudes.get(middle - 1) / 2 + magnitudes.get(middle) / 2
                : magnitudes.get(middle);
    }

    private void remember(Window window, Point point) {
        if (normal(point)) {
            window.history.add(Math.abs(point.acceleration()));
            while (window.history.size() > config.getBaselineWindowSize()) {
                window.history.remove(0);
            }
        }
    }

    private void resetContext(Window window, Point current) {
        window.previous = current;
        window.pending = null;
        window.excursionAnchor = null;
        window.history.clear();
        if (current != null) {
            remember(window, current);
        }
    }

    private boolean normal(Point point) {
        InclineRecord record = point.sample().record();
        return Integer.valueOf(0).equals(record.getRunStatus()) && Integer.valueOf(0).equals(record.getAlarmStatus());
    }

    private boolean valid(Double acceleration) {
        return acceleration != null && Double.isFinite(acceleration);
    }

    private long maxGapMillis() {
        return config.getMaxGapSeconds() * 1000L;
    }

    private void releasePending(Window window, List<Sample> ready) {
        if (window.pending != null) {
            ready.add(window.pending.sample());
            window.pending = null;
        }
    }

    private void logInvalid(Sample sample) {
        log.warn("跳过无效振动数据：测点[{}]，采集时间[{}]，accMaxZ[{}]mG",
                sample.device().getDeviceCode(), sample.record().getTm(), sample.record().getAccMaxZ());
    }

    public record Sample(DeviceInfoExt device, InclineRecord record) { }

    private record Point(Sample sample, double acceleration, long time) { }

    private static class Window {
        private Point previous;
        private Point pending;
        private Point excursionAnchor;
        private double excursionBaseline;
        private final List<Double> history = new ArrayList<>();
        private long pendingSince;
        private long lastSeenTime = Long.MIN_VALUE;

        private Window copy() {
            Window copy = new Window();
            copy.restore(this);
            return copy;
        }

        private void restore(Window source) {
            previous = source.previous;
            pending = source.pending;
            excursionAnchor = source.excursionAnchor;
            excursionBaseline = source.excursionBaseline;
            history.clear();
            history.addAll(source.history);
            pendingSince = source.pendingSince;
            lastSeenTime = source.lastSeenTime;
        }
    }
}
