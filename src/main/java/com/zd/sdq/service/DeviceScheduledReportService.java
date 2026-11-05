package com.zd.sdq.service;

import com.company.cbf.starter.data.entity.MqttData;
import com.company.cbf.starter.data.service.forward.MqttDataBuilder;
import com.company.cbf.starter.data.service.forward.device.DeviceType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zd.sdq.config.DeviceReportConfig;
import com.zd.sdq.entity.dto.DeviceReportSample;
import com.zd.sdq.mapper.InclineForwardDataMapper;
import com.zd.sdq.mapper.WaterLevelDataMapper;
import com.zd.sdq.service.DeviceReportStateStore.Source;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceScheduledReportService implements SchedulingConfigurer {
    private static final DateTimeFormatter SOURCE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));
    private static final TypeReference<List<List<String>>> VALUES_TYPE = new TypeReference<>() { };
    private final DeviceReportConfig config;
    private final InclineForwardDataMapper inclineMapper;
    private final WaterLevelDataMapper waterMapper;
    private final ObjectMapper objectMapper;
    private final DeviceReportMqttSender sender;
    private final DeviceReportStateStore stateStore;

    private record Delivery(DeviceReportSample sample, String token, MqttData data) { }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        register(registrar, config.getInclineVib().getCron(), this::reportInclineVibAt);
        register(registrar, config.getWaterLevel().getCron(), this::reportWaterLevelAt);
        register(registrar, config.getRetryCron(), this::retryAt);
    }

    private void register(ScheduledTaskRegistrar registrar, String cron, Consumer<Instant> task) {
        if ("-".equals(cron)) return;
        CronTrigger trigger = new CronTrigger(cron, ZoneId.of(config.getZone()));
        AtomicReference<Instant> scheduledAt = new AtomicReference<>();
        // 保存计划刻度，任务晚执行时仍使用原窗口。错过的历史刻度由补发扫描恢复。
        registrar.addTriggerTask(() -> task.accept(scheduledAt.get()), context -> {
            Instant next = trigger.nextExecution(context);
            scheduledAt.set(next);
            return next;
        });
    }

    public void reportInclineVibAt(Instant reportTime) {
        if (!"-".equals(config.getInclineVib().getCron())) {
            reportAt(reportTime.minusSeconds(3600), reportTime, Source.INCLINE, inclineMapper::selectLatestForReport);
        }
    }

    public void reportWaterLevelAt(Instant reportTime) {
        if (!"-".equals(config.getWaterLevel().getCron())) {
            reportAt(reportTime.minusSeconds(3600), reportTime, Source.WATER, waterMapper::selectLatestForReport);
        }
    }

    /** 只扫描已结束的小时；最近24小时之外的数据保留状态，但不再自动补发。 */
    public void retryAt(Instant now) {
        Instant cutoff = now.minusSeconds(24 * 3600);
        Instant end = now.atZone(ZoneId.of(config.getZone())).truncatedTo(ChronoUnit.HOURS).toInstant();
        for (Instant hour = end.minusSeconds(23 * 3600); !hour.isAfter(end); hour = hour.plusSeconds(3600)) {
            Instant from = hour.minusSeconds(3600);
            if (from.isBefore(cutoff)) from = cutoff;
            if (!"-".equals(config.getInclineVib().getCron())) {
                reportAt(from, hour, Source.INCLINE, inclineMapper::selectLatestForReport);
            }
            if (!"-".equals(config.getWaterLevel().getCron())) {
                reportAt(from, hour, Source.WATER, waterMapper::selectLatestForReport);
            }
        }
    }

    private void reportAt(Instant from, Instant reportTime, Source source,
                          BiFunction<String, String, List<DeviceReportSample>> query) {
        try {
            List<DeviceReportSample> samples = query.apply(SOURCE_TIME.format(from), SOURCE_TIME.format(reportTime));
            Map<Long, List<DeviceReportSample>> byGateway = new LinkedHashMap<>();
            for (DeviceReportSample sample : samples) {
                byGateway.computeIfAbsent(sample.getMqttGatewayId(), key -> new ArrayList<>()).add(sample);
            }
            for (var entry : byGateway.entrySet()) {
                // 临发送该网关时才抢占，避免等待其他慢网关期间租约过期。
                List<Delivery> deliveries = new ArrayList<>();
                for (DeviceReportSample sample : entry.getValue()) {
                    String token = stateStore.claim(source, sample.getId(), SOURCE_TIME.format(reportTime), System.currentTimeMillis());
                    if (token == null) continue;
                    try {
                        deliveries.add(new Delivery(sample, token, build(sample, reportTime)));
                    } catch (Exception e) {
                        stateStore.complete(source, sample.getId(), token, e.toString(), System.currentTimeMillis());
                        log.warn("不可上报的采集记录: {} / {}", sample.getDeviceCode(), sample.getId(), e);
                    }
                }
                if (deliveries.isEmpty()) continue;
                String error = null;
                try {
                    // 在调度线程等待异步提交，不能在 Vert.x Event Loop 中访问数据库。
                    sender.sendOnce(entry.getKey(), deliveries.stream().map(Delivery::data).toList())
                            .get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    error = e.toString();
                    log.error("网关上报失败，等待补发，网关: {}，所属整点: {}", entry.getKey(), reportTime, e);
                }
                for (Delivery item : deliveries) {
                    stateStore.complete(source, item.sample().getId(), item.token(), error, System.currentTimeMillis());
                }
            }
        } catch (Exception e) {
            log.error("定时上报失败，待下轮扫描恢复，来源: {}，所属整点: {}", source, reportTime, e);
        }
    }

    private MqttData build(DeviceReportSample sample, Instant reportTime) throws Exception {
        if (sample.getMqttGatewayId() == null) throw new IllegalArgumentException("未配置MQTT网关");
        List<List<String>> values = objectMapper.readValue(sample.getPayload(), VALUES_TYPE);
        DeviceType type = DeviceType.valueOf(sample.getDeviceType());
        int columns = switch (type) {
            case INC -> 4;
            case VIB -> 2;
            case WLV -> 3;
            default -> throw new IllegalArgumentException("不支持的设备类型");
        };
        if (values == null || values.size() != 1 || values.get(0) == null || values.get(0).size() != columns) {
            throw new IllegalArgumentException("采集快照的列数不正确");
        }
        List<String> row = values.get(0);
        for (String value : row.subList(1, row.size())) {
            if (value == null || !Double.isFinite(Double.parseDouble(value))) {
                throw new IllegalArgumentException("采集快照含无效数值");
            }
        }
        row.set(0, reportTime.atZone(ZoneId.of(config.getZone())).format(MqttDataBuilder.TIME_FORMATTER));
        return MqttData.builder().deviceId(sample.getDeviceCode()).deviceType(type.name()).value(values).build();
    }
}
