package com.zd.sdq.service;

import com.company.cbf.starter.data.entity.MqttData;
import com.company.cbf.starter.data.service.forward.MqttDataBuilder;
import com.company.cbf.starter.data.service.forward.device.DeviceType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zd.sdq.config.DeviceReportConfig;
import com.zd.sdq.entity.dto.DeviceReportSample;
import com.zd.sdq.entity.dto.SimulatedMqttData;
import com.zd.sdq.mapper.InclineForwardDataMapper;
import com.zd.sdq.mapper.WaterLevelDataMapper;
import com.zd.sdq.service.incline.HistoricalFluctuationSimulator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceScheduledReportService implements SchedulingConfigurer {
    // 现有两张表的无时区采集时间均使用北京时间，不随报文时区配置变化。
    private static final DateTimeFormatter SOURCE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));
    private static final TypeReference<List<List<String>>> VALUES_TYPE = new TypeReference<>() { };
    private final DeviceReportConfig config;
    private final InclineForwardDataMapper inclineMapper;
    private final WaterLevelDataMapper waterMapper;
    private final ObjectMapper objectMapper;
    private final DeviceReportMqttSender sender;
    private final HistoricalFluctuationSimulator simulator;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        register(registrar, config.getInclineVib(), this::reportInclineVibAt);
        register(registrar, config.getWaterLevel(), this::reportWaterLevelAt);
    }

    private void register(ScheduledTaskRegistrar registrar, DeviceReportConfig.Group group,
                          Consumer<Instant> task) {
        if ("-".equals(group.getCron())) return;
        CronTrigger trigger = new CronTrigger(group.getCron(), ZoneId.of(config.getZone()));
        AtomicReference<Instant> scheduledAt = new AtomicReference<>();
        // 使用调度器计划的刻度，而非任务真正开始执行的秒数。CronTrigger 默认跳过错过的刻度，不补发。
        registrar.addTriggerTask(() -> task.accept(scheduledAt.get()), context -> {
            Instant next = trigger.nextExecution(context);
            scheduledAt.set(next);
            return next;
        });
    }

    public void reportInclineVibAt(Instant reportTime) {
        reportAt(reportTime, config.getInclineVib(), config.getInclineVib().getSimulation(),
                inclineMapper::selectLatestForReport);
    }

    public void reportWaterLevelAt(Instant reportTime) {
        reportAt(reportTime, config.getWaterLevel(), null, waterMapper::selectLatestForReport);
    }

    private void reportAt(Instant reportTime, DeviceReportConfig.Group group,
                           DeviceReportConfig.Simulation simulation,
                           BiFunction<String, String, List<DeviceReportSample>> query) {
        try {
            boolean allowSimulation = simulation != null && simulation.isEnabled();
            Duration lookupAge = allowSimulation ? simulation.getMaxSourceAge() : group.getMaxAge();
            Instant oldestSource = reportTime.minus(lookupAge);
            Instant oldestMeasured = reportTime.minus(group.getMaxAge());
            String from = SOURCE_TIME.format(oldestSource);
            String to = SOURCE_TIME.format(reportTime);
            // 每组用一条SQL取数，保证选择来自同一个数据库读取快照。
            List<DeviceReportSample> samples = query.apply(from, to);
            Map<Long, List<MqttData>> byGateway = new LinkedHashMap<>();
            String alignedTime = reportTime.atZone(ZoneId.of(config.getZone()))
                    .format(MqttDataBuilder.TIME_FORMATTER);
            int measuredCount = 0;
            int simulatedCount = 0;
            for (DeviceReportSample sample : samples) {
                try {
                    if (sample.getMqttGatewayId() == null) {
                        throw new IllegalArgumentException("未配置MQTT网关");
                    }
                    Instant sourceTime = LocalDateTime.parse(sample.getDataTime(), SOURCE_TIME)
                            .atZone(ZoneId.of("Asia/Shanghai")).toInstant();
                    if (sourceTime.isBefore(oldestSource) || sourceTime.isAfter(reportTime)) continue;
                    boolean simulated = sourceTime.isBefore(oldestMeasured);
                    // 反序列化得到独立副本；只修改报文时间，不回写采集快照。
                    List<List<String>> values = objectMapper.readValue(sample.getPayload(), VALUES_TYPE);
                    DeviceType type = DeviceType.valueOf(sample.getDeviceType());
                    int columns = switch (type) {
                        case INC -> 4;
                        case VIB -> 2;
                        case WLV -> 3;
                        default -> throw new IllegalArgumentException("不支持的设备类型");
                    };
                    if (values == null || values.size() != 1 || values.get(0) == null
                            || values.get(0).size() != columns) {
                        throw new IllegalArgumentException("采集快照的列数不正确");
                    }
                    List<String> row = values.get(0);
                    for (String value : row.subList(1, row.size())) {
                        if (value == null || !Double.isFinite(Double.parseDouble(value))) {
                            throw new IllegalArgumentException("采集快照含无效数值");
                        }
                    }
                    row.set(0, alignedTime);
                    MqttData data;
                    if (simulated) {
                        var learned = simulator.simulate(sample, reportTime, simulation);
                        if (learned.isEmpty()) {
                            log.warn("历史不足、平坦或不适合学习，跳过模拟测点: {}，真实源时间: {}",
                                    sample.getDeviceCode(), sourceTime);
                            continue;
                        }
                        row.subList(1, row.size()).clear();
                        row.addAll(learned.get().numbers());
                        data = new SimulatedMqttData(sample.getDeviceCode(), type.name(), values,
                                sourceTime.toEpochMilli(), HistoricalFluctuationSimulator.METHOD);
                        simulatedCount++;
                        log.warn("生成模拟上报条目，测点: {}，平台时间: {}，真实源时间: {}，源数据年龄: {}，算法: {}，学习条数: {}",
                                sample.getDeviceCode(), reportTime, sourceTime, Duration.between(sourceTime, reportTime),
                                HistoricalFluctuationSimulator.METHOD, learned.get().trainingCount());
                    } else {
                        data = MqttData.builder().deviceId(sample.getDeviceCode())
                                .deviceType(type.name()).value(values).build();
                        measuredCount++;
                    }
                    byGateway.computeIfAbsent(sample.getMqttGatewayId(), key -> new ArrayList<>()).add(data);
                } catch (Exception e) {
                    log.warn("跳过不可上报的测点: {}，平台时间: {}", sample.getDeviceCode(), reportTime, e);
                }
            }
            byGateway.forEach((gateway, data) -> {
                try {
                    sender.sendOnce(gateway, data);
                } catch (Exception e) {
                    log.error("本次网关上报失败，不重试，网关: {}，平台时间: {}", gateway, reportTime, e);
                }
            });
            log.info("定时上报取数完成，平台时间: {}，真实有效期: {}，源数据窗口: {}，实测条目: {}，模拟条目: {}，网关数: {}",
                    reportTime, group.getMaxAge(), lookupAge, measuredCount, simulatedCount, byGateway.size());
        } catch (Exception e) {
            log.error("本次定时上报取数失败，不重试，平台时间: {}", reportTime, e);
        }
    }
}
