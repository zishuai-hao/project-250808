package com.zd.sdq.service.incline;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.cbf.starter.data.constant.NetworkGatewayType;
import com.company.cbf.starter.data.entity.DeviceInfo;
import com.company.cbf.starter.data.entity.MqttData;
import com.company.cbf.starter.data.service.forward.MqttDataBuilder;
import com.company.cbf.starter.data.service.forward.device.DeviceType;
import com.zd.sdq.entity.DeviceInfoExt;
import com.zd.sdq.entity.dto.incline.InclineHistoryRequest;
import com.zd.sdq.entity.dto.incline.InclineHistoryResponse;
import com.zd.sdq.mapper.DeviceInfoExtMapper;
import com.zd.sdq.service.DeviceLatestDataCache;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.vertx.core.Vertx;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 倾角传感器数据处理器
 * 定时从 HTTP-Client 获取倾角传感器数据
 *
 * @author hzs
 * @date 2025/11/18
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InclineDataHandler {
    private final Vertx vertx;
    private final DeviceInfoExtMapper deviceInfoExtMapper;
    private final InclineApiService inclineApiService;
    private final DeviceLatestDataCache deviceLatestDataCache;
    private final VibrationSpikeFilter vibrationSpikeFilter;
    private final InclineForwardDataService inclineForwardDataService;
    private final InclineBaselineService inclineBaselineService;
    private final InclineFluctuationService inclineFluctuationService;

    /**
     * HTTP-Client 网关类型标识
     */
    private static final NetworkGatewayType GATEWAY_TYPE_HTTP_CLIENT = NetworkGatewayType.HTTP_CLIENT;

    /**
     * 查询间隔：2分钟，独立于平台定时上报频率。
     */
    private static final int QUERY_INTERVAL_MS = 2 * 60 * 1000;

    /**
     * 记录每个远程设备的最新数据时间
     * Key: 远程设备ID (remoteDeviceId)
     * Value: 最新数据时间
     */
    private final Map<String, Date> remoteDeviceIdLastDataTimeMap = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("========================================");
        log.info("倾角传感器数据处理器初始化");
        log.info("========================================");

        // 查询所有 HTTP-Client 类型的设备
        List<DeviceInfoExt> allDevices = queryHttpClientDevices();

        if (allDevices.isEmpty()) {
            log.warn("未找到任何 HTTP-Client 类型的设备");
            return;
        }
        log.info("找到 {} 个 HTTP-Client 类型设备", allDevices.size());

        // 启动也回查24小时，避免停机期间的真实记录丢失
        Date currentTime = Date.from(java.time.Instant.now().minusSeconds(24 * 3600));

        try {
            // 根据remoteDeviceId分组（实际上每组固定两个设备），每个组内根据 ID 取模构建延迟时间，并查询数据
            // 每一个 remotedDevice 查询到的数据可以分配给这两个设备作为数据
            Map<String, List<DeviceInfoExt>> remoteDeviceIdGroup = allDevices.stream()
                    .collect(Collectors.groupingBy(DeviceInfoExt::getRemoteDeviceId));
            int index = 0;

            // 为每个设备组根据 remoteDeviceId 取模计算延迟时间，并查询数据
            for (Map.Entry<String, List<DeviceInfoExt>> entry : remoteDeviceIdGroup.entrySet()) {
                String remoteDeviceId = entry.getKey();
                List<DeviceInfoExt> devices = entry.getValue();

                // 计算延迟时间
                long delayMs = (long) index++ * (QUERY_INTERVAL_MS / remoteDeviceIdGroup.size());
                remoteDeviceIdLastDataTimeMap.put(remoteDeviceId, currentTime);
                AtomicBoolean querying = new AtomicBoolean();
                log.debug("初始化远程设备[{}]的最新数据时间: {}",
                        remoteDeviceId, DateUtil.formatDateTime(currentTime));

                // vertx 定时器 - 使用 executeBlocking 避免阻塞 Event Loop 线程
                vertx.setPeriodic(delayMs, QUERY_INTERVAL_MS, id -> {
                    if (!querying.compareAndSet(false, true)) return;
                    // 将阻塞的 HTTP 调用放到 Worker 线程池中执行
                    vertx.executeBlocking(promise -> {
                        try {
                            log.debug("在 Worker 线程中查询远程设备[{}]数据", remoteDeviceId);
                            var end = ZonedDateTime.now(zoneId);
                            var format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                            InclineHistoryResponse response = fetchDeviceData(remoteDeviceId,
                                    end.minusHours(24).format(format), end.format(format));
                            // 解析、过滤和SQLite写入也在Worker线程中完成。
                            processResponse(devices, response, remoteDeviceId);
                            processBackfillResponse(devices, response, remoteDeviceId);
                            promise.complete();
                        } catch (Exception e) {
                            log.error("查询远程设备[{}]数据时发生异常", remoteDeviceId, e);
                            promise.fail(e);
                        }
                    }, false, res -> {
                        querying.set(false);
                        if (res.failed()) log.error("远程设备[{}]采集或入库失败，下轮继续查询", remoteDeviceId, res.cause());
                    });
                });
            }
        } catch (Exception e) {
            log.error("倾角传感器数据抓取任务执行失败", e);
        }
    }

    /**
     * 查询所有 HTTP-Client 类型的设备
     */
    private List<DeviceInfoExt> queryHttpClientDevices() {
        LambdaQueryWrapper<DeviceInfoExt> queryWrapper = new LambdaQueryWrapper<>();

        // 查询条件: network_gateway_type = 'HTTP-Client' 且 remote_device_id 不为空
        queryWrapper.eq(DeviceInfoExt::getNetworkGatewayType, GATEWAY_TYPE_HTTP_CLIENT).eq(DeviceInfo::isEnable, 1);

        List<DeviceInfoExt> devices = deviceInfoExtMapper.selectList(queryWrapper);

        log.debug("查询到 {} 个 HTTP-Client 类型设备", devices.size());

        return devices;
    }

    /**
     * 查询单个设备的数据
     */
    public InclineHistoryResponse fetchDeviceData(String remoteDeviceId, String startTime, String endTime) {
        try {
            if (StrUtil.isBlank(inclineApiService.getLoginToken())) inclineApiService.refreshToken();
            List<InclineHistoryResponse.InclineRecord> all = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            InclineHistoryResponse response = null;
            boolean refreshed = false;
            int received = 0;
            for (int page = 1; page <= 1000; page++) {
                InclineHistoryRequest request = new InclineHistoryRequest();
                request.setSn(remoteDeviceId);
                request.setStartTm(startTime);
                request.setEndTm(endTime);
                request.setSortMode("asc");
                request.setPagesize(100);
                request.setCurpagenum(page);
                try {
                    response = inclineApiService.getHistoryData(request);
                } catch (IOException e) {
                    if (refreshed || e.getMessage() == null || !e.getMessage().contains("401")) throw e;
                    refreshed = true;
                    inclineApiService.refreshToken();
                    response = inclineApiService.getHistoryData(request);
                }
                if (response == null || response.getData() == null || response.getData().getResultData() == null) return null;
                var records = response.getData().getResultData();
                received += records.size();
                int before = all.size();
                for (var record : records) {
                    if (record.getTm() != null && record.getTm().compareTo(startTime) >= 0
                            && record.getTm().compareTo(endTime) <= 0 && seen.add(record.getTm())) all.add(record);
                }
                Integer count = response.getData().getCount();
                Integer actualPageSize = response.getData().getPagesize();
                int pageSize = actualPageSize != null && actualPageSize > 0 ? actualPageSize : 100;
                if (records.isEmpty() || (count != null && received >= count)
                        || (count == null && records.size() < pageSize)) {
                    response.getData().setResultData(all);
                    return response;
                }
                if (all.size() == before) throw new IllegalStateException("历史接口分页未推进");
            }
            throw new IllegalStateException("24小时历史数据超过分页上限");
        } catch (Exception e) {
            log.error("查询设备24小时历史失败，下轮重查，SN: {}，范围: {} ~ {}", remoteDeviceId, startTime, endTime, e);
            return null;
        }
    }
    public static ZoneId zoneId = ZoneId.of("Asia/Shanghai");

    /**
     * 处理 API 响应数据
     */
    private void processResponse(List<DeviceInfoExt> devices, InclineHistoryResponse response, String remoteDeviceId) {
        if (response == null) {
            log.warn("远程设备[{}]响应为空", remoteDeviceId);
            return;
        }

        if (response.getData() == null) {
            log.warn("远程设备[{}]响应数据为空", remoteDeviceId);
            return;
        }
        List<InclineHistoryResponse.InclineRecord> records = response.getData().getResultData();
        if (records == null || records.isEmpty()) {
            log.debug("远程设备[{}]本次无新数据", remoteDeviceId);
            return;
        }

        // 窗口依赖相邻采集顺序，同时兼容接口返回倒序记录的情况。
        List<InclineHistoryResponse.InclineRecord> orderedRecords = new ArrayList<>(records);
        orderedRecords.sort(Comparator.comparing(InclineHistoryResponse.InclineRecord::getTm));
        // 尚无零点的INC从本批最新有效新数据起算，不把此前积压值累加到新的0值上。
        var latestNewIncline = orderedRecords.stream()
                .filter(record -> DateUtil.parse(record.getTm()).after(remoteDeviceIdLastDataTimeMap.get(remoteDeviceId)))
                .filter(record -> finite(record.getAngleX()) && finite(record.getAngleY()) && finite(record.getTemp()))
                .max(Comparator.comparing(InclineHistoryResponse.InclineRecord::getTm)).orElse(null);
        for (InclineHistoryResponse.InclineRecord record : orderedRecords) {
            log.debug("远程设备[{}]数据: {}", remoteDeviceId, record.getTm());
            // 解析记录时间
            Date recordTime = DateUtil.parse(record.getTm());

            // 过滤重复数据：只处理时间大于 lastTime 的数据
            if (!recordTime.after(remoteDeviceIdLastDataTimeMap.get(remoteDeviceId))) {
                log.debug("远程设备[{}]数据时间[{}]小于等于上次处理时间[{}]，跳过",
                        remoteDeviceId, record.getTm(), DateUtil.formatDateTime(remoteDeviceIdLastDataTimeMap.get(remoteDeviceId)));
                continue;
            }
            // 将远程传感器的数据分配到两个本地设备中
            for (DeviceInfoExt device : devices) {
                if (inclineForwardDataService.hasCollectedData(device, remoteDeviceId, record.getTm())) continue;
                // 如果是 VIB 设备，则发送加速度最大值数据
                if (device.getDeviceType().equals(DeviceType.VIB)) {
                    vibrationSpikeFilter.process(device, record, this::saveVibration,
                            inclineForwardDataService::markFilteredVibration);
                }
                // 如果是 INC 设备，则发送角度数据和温度数据
                else if (device.getDeviceType().equals(DeviceType.INC)) {
                    collectIncline(device, remoteDeviceId, record, record == latestNewIncline);
                } else {
                    log.warn("设备[{}]类型[{}]不支持", device.getDeviceCode(), device.getDeviceType());
                }
            }
            // 同一采集记录的全部通道处理完成后再推进，保存失败时保留重试机会。
            remoteDeviceIdLastDataTimeMap.put(remoteDeviceId, recordTime);
            log.debug("更新远程设备[{}]最新数据时间为 {}", remoteDeviceId, DateUtil.formatDateTime(recordTime));
        }
    }

    private void collectIncline(DeviceInfoExt device, String remoteDeviceId,
                                InclineHistoryResponse.InclineRecord record, boolean allowInitializeZero) {
        ZonedDateTime zonedDateTime = LocalDateTime.parse(record.getTm(),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(zoneId);
        if (!finite(record.getAngleX()) || !finite(record.getAngleY()) || !finite(record.getTemp())) {
            log.warn("跳过无效倾角采集值，测点: {}，采集时间: {}", device.getDeviceCode(), record.getTm());
            return;
        }
        var corrected = inclineBaselineService.apply(
                device.getDeviceCode(), record.getAngleX(), record.getAngleY());
        boolean initializeZero = corrected.baseline() == null;
        if (initializeZero && !allowInitializeZero) return;
        var angles = initializeZero ? new InclineFluctuationService.Angles(0, 0)
                : inclineFluctuationService.apply(device, record, corrected);
        MqttDataBuilder builder = new MqttDataBuilder(DeviceType.INC);
        builder.sampleTime(zonedDateTime.toInstant().toEpochMilli());
        builder.monitoringCode(device.getDeviceCode());
        builder.addData(
                zonedDateTime,
                NumberUtil.roundStr(angles.x(), 6),
                NumberUtil.roundStr(angles.y(), 6),
                NumberUtil.roundStr(record.getTemp(), 3));
        MqttData mqttData = builder.build();

        if (initializeZero) {
            corrected = inclineBaselineService.initializeAndStoreZero(device.getDeviceCode(),
                    record.getAngleX(), record.getAngleY(), () -> inclineForwardDataService
                            .saveCollectedData(device, remoteDeviceId, record, mqttData));
        } else {
            inclineForwardDataService.saveCollectedData(device, remoteDeviceId, record, mqttData);
        }
        Map<String, Object> latestData = new LinkedHashMap<>();
        latestData.put("angleX", NumberUtil.roundStr(angles.x(), 6));
        latestData.put("angleY", NumberUtil.roundStr(angles.y(), 6));
        latestData.put("rawAngleX", record.getAngleX());
        latestData.put("rawAngleY", record.getAngleY());
        latestData.put("baselineApplied", corrected.baseline() != null);
        if (corrected.baseline() != null) {
            latestData.put("baselineX", corrected.baseline().angleX());
            latestData.put("baselineY", corrected.baseline().angleY());
            latestData.put("baselineUpdatedAt", corrected.baseline().updatedAt());
        }
        latestData.put("temp", NumberUtil.roundStr(record.getTemp(), 3));
        latestData.put("angleUnit", "deg");
        latestData.put("tempUnit", "C");
        latestData.put("alarmStatus", record.getAlarmStatus());
        latestData.put("runStatus", record.getRunStatus());
        deviceLatestDataCache.updateLatestData(device, "INCLINE_HTTP", latestData, record.getTm());
    }

    /** 回补最新游标之前的缺口；已有快照和发送状态均不改写。 */
    public void processBackfillResponse(List<DeviceInfoExt> devices, InclineHistoryResponse response, String remoteDeviceId) {
        if (response == null || response.getData() == null || response.getData().getResultData() == null) return;
        var ordered = response.getData().getResultData().stream()
                .sorted(Comparator.comparing(InclineHistoryResponse.InclineRecord::getTm)).toList();
        for (DeviceInfoExt device : devices) {
            if (device.getDeviceType() == DeviceType.INC) {
                for (var record : ordered) {
                    if (!inclineForwardDataService.hasCollectedData(device, remoteDeviceId, record.getTm())) {
                        collectIncline(device, remoteDeviceId, record, false);
                    }
                }
            } else if (device.getDeviceType() == DeviceType.VIB) {
                // 用完整且有序的历史窗口判断迟到点，不把它塞入实时窗口改变相邻关系。
                vibrationSpikeFilter.replayHistory(device, ordered, sample -> {
                    if (!inclineForwardDataService.hasCollectedData(device, remoteDeviceId, sample.record().getTm())) {
                        saveVibration(sample);
                    }
                }, inclineForwardDataService::markFilteredVibration);
            }
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void flushPendingVibrationData() {
        vibrationSpikeFilter.flushExpired(this::saveVibration);
    }

    private boolean finite(Double value) {
        return value != null && Double.isFinite(value);
    }

    private void saveVibration(VibrationSpikeFilter.Sample sample) {
        DeviceInfoExt device = sample.device();
        InclineHistoryResponse.InclineRecord record = sample.record();
        if (inclineForwardDataService.hasCollectedData(device, device.getRemoteDeviceId(), record.getTm())) return;
        String accelerationValue = NumberUtil.roundStr(record.getAccMaxZ() * VibrationSpikeFilter.MG_TO_M_S2, 4);
        // 延后放行仍保存原始采集时间，不能因晚入库而延长上报有效期。
        ZonedDateTime sampleTime = LocalDateTime.parse(record.getTm(),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(zoneId);
        MqttDataBuilder builder = new MqttDataBuilder(DeviceType.VIB);
        builder.sampleTime(sampleTime.toInstant().toEpochMilli());
        builder.monitoringCode(device.getDeviceCode());
        builder.addData(sampleTime, accelerationValue);
        MqttData mqttData = builder.build();
        inclineForwardDataService.saveCollectedData(device, device.getRemoteDeviceId(), record, mqttData);

        Map<String, Object> latestData = new LinkedHashMap<>();
        latestData.put("accMaxZ", accelerationValue);
        latestData.put("unit", "m/s2");
        latestData.put("alarmStatus", record.getAlarmStatus());
        latestData.put("runStatus", record.getRunStatus());
        deviceLatestDataCache.updateLatestData(device, "INCLINE_HTTP", latestData, record.getTm());
    }

    /**
     * 打印最新数据
     */
    private void printLatestData(String remoteDeviceId, InclineHistoryResponse.InclineRecord record) {

        log.debug("========== 设备[{}]最新数据  时间: {} ==========", remoteDeviceId, record.getTm());

        log.debug("X方向角度: {}°", record.getAngleX());
        log.debug("Y方向角度: {}°", record.getAngleY());
        log.debug("Z方向角度: {}°", record.getAngleZ());
        log.debug("合倾角: {}°", record.getAngleXyz());
        log.debug("方向角: {}°", record.getTrend());
        log.debug("方位角: {}°", record.getAzi());
        // 加速度
        log.debug("X轴加速度: {}m/s²", record.getAccX());
        log.debug("Y轴加速度: {}m/s²", record.getAccY());
        log.debug("Z轴加速度: {}m/s²", record.getAccZ());
        log.debug("X轴加速度最大值: {}m/s²", record.getAccMaxX());
        log.debug("Y轴加速度最大值: {}m/s²", record.getAccMaxY());
        log.debug("Z轴加速度最大值: {}m/s²", record.getAccMaxZ());
        // 温度
        log.debug("温度: {}℃", record.getTemp());
        log.debug("报警状态: {} (0-正常, 1-曾有报警, 2-加速度报警, 3-角度报警)", record.getAlarmStatus());
        log.debug("运行状态: {} (0-正常, 1-故障)", record.getRunStatus());

        log.debug("======================================");
    }
}
