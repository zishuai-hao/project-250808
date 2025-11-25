package com.zd.sdq.service.incline;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.cbf.starter.data.constant.NetworkGatewayType;
import com.company.cbf.starter.data.entity.DeviceInfo;
import com.company.cbf.starter.data.entity.MqttData;
import com.company.cbf.starter.data.service.forward.BufferForwardMqttClientAdapter;
import com.company.cbf.starter.data.service.forward.MqttDataBuilder;
import com.company.cbf.starter.data.service.forward.device.DeviceType;
import com.zd.sdq.entity.DeviceInfoExt;
import com.zd.sdq.entity.dto.incline.InclineHistoryRequest;
import com.zd.sdq.entity.dto.incline.InclineHistoryResponse;
import com.zd.sdq.mapper.DeviceInfoExtMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.vertx.core.Vertx;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
    private final BufferForwardMqttClientAdapter bufferForwardMqttClientAdapter;

    /**
     * HTTP-Client 网关类型标识
     */
    private static final NetworkGatewayType GATEWAY_TYPE_HTTP_CLIENT = NetworkGatewayType.HTTP_CLIENT;

    /**
     * 查询间隔：5分钟
     */
    private static final int QUERY_INTERVAL_MS = 5 * 60 * 1000;

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

        // 初始化每个远程设备的最新数据时间为当前时间
        Date currentTime = new Date();

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
                log.debug("初始化远程设备[{}]的最新数据时间: {}",
                        remoteDeviceId, DateUtil.formatDateTime(currentTime));

                // vertx 定时器
                vertx.setPeriodic(delayMs, QUERY_INTERVAL_MS, id -> {
                    InclineHistoryResponse response = fetchDeviceData(remoteDeviceId,
                            DateUtil.formatDateTime(remoteDeviceIdLastDataTimeMap.get(remoteDeviceId)),
                            DateUtil.formatDateTime(new Date()));
                    // 处理响应数据
                    processResponse(devices, response, remoteDeviceId);
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
        InclineHistoryResponse response = null;

        log.debug(">>> 开始查询设备[{}]的数据，时间范围: {} ~ {}", remoteDeviceId, startTime, endTime);

        // 从 Map 中获取上次查询的时间
        Date lastTime = remoteDeviceIdLastDataTimeMap.get(remoteDeviceId);
        if (lastTime == null) {
            lastTime = new Date();
        }

        try {
            // 确保已登录
            if (StrUtil.isBlank(inclineApiService.getLoginToken())) {
                log.error("Token 为空，先进行登录...");
                inclineApiService.refreshToken();
            }

            // 构建查询请求
            InclineHistoryRequest request = new InclineHistoryRequest();
            request.setSn(remoteDeviceId);
            request.setStartTm(DateUtil.formatDateTime(lastTime));
            request.setEndTm(DateUtil.formatDateTime(new Date()));
            response = inclineApiService.getHistoryData(request);
        } catch (IOException e) {
            log.error("查询设备[{}]数据失败: {}", remoteDeviceId, e.getMessage(), e);

            // 如果是认证失败，尝试重新登录
            if (e.getMessage() != null && e.getMessage().contains("401")) {
                log.info("认证失败，尝试重新登录...");
                try {
                    inclineApiService.refreshToken();
                    // 重试一次
                    response = fetchDeviceData(remoteDeviceId, DateUtil.formatDateTime(lastTime),
                            DateUtil.formatDateTime(new Date()));
                } catch (Exception retryException) {
                    log.error("重新登录后重试失败", retryException);
                }
            }
        } catch (Exception e) {
            log.error("查询设备[{}]数据时发生异常", remoteDeviceId, e);
        }

        return response;
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

        for (InclineHistoryResponse.InclineRecord record : records) {
            log.debug("远程设备[{}]数据: {}", remoteDeviceId, record.getTm());
            // 解析记录时间
            Date recordTime = DateUtil.parse(record.getTm());

            // 过滤重复数据：只处理时间大于 lastTime 的数据
            if (!recordTime.after(remoteDeviceIdLastDataTimeMap.get(remoteDeviceId))) {
                log.debug("远程设备[{}]数据时间[{}]小于等于上次处理时间[{}]，跳过", 
                        remoteDeviceId, record.getTm(), DateUtil.formatDateTime(remoteDeviceIdLastDataTimeMap.get(remoteDeviceId)));
                continue;
            }
            remoteDeviceIdLastDataTimeMap.put(remoteDeviceId, recordTime);
            log.debug("更新远程设备[{}]最新数据时间为 {}", remoteDeviceId, DateUtil.formatDateTime(recordTime));
            
            ZonedDateTime zonedDateTime = LocalDateTime.parse(record.getTm(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(zoneId);

            // 将远程传感器的数据分配到两个本地设备中
            for (DeviceInfoExt device : devices) {
                // 如果是 VIB 设备，则发送加速度最大值数据
                if (device.getDeviceType().equals(DeviceType.VIB)) {
                    MqttDataBuilder builder = new MqttDataBuilder(DeviceType.VIB);
                    builder.currentTime();
                    builder.monitoringCode(device.getDeviceCode());
                    builder.addData(zonedDateTime, NumberUtil.roundStr(record.getAccMaxY(), 4));
                    MqttData mqttData = builder.build();

                    // 发送数据到缓冲转发适配器
                    bufferForwardMqttClientAdapter.push(device.getDeviceCode(), mqttData.getValue());
                }
                // 如果是 INC 设备，则发送角度数据和温度数据
                else if (device.getDeviceType().equals(DeviceType.INC)) {
                    MqttDataBuilder builder = new MqttDataBuilder(DeviceType.INC);
                    builder.currentTime();
                    builder.monitoringCode(device.getDeviceCode());
                    builder.addData(
                            zonedDateTime,
                            NumberUtil.roundStr(record.getAngleX(), 6),
                            NumberUtil.roundStr(record.getAngleY(), 6),
                            NumberUtil.roundStr(record.getTemp(), 3));
                    MqttData mqttData = builder.build();

                    // 发送数据到缓冲转发适配器
                    bufferForwardMqttClientAdapter.push(device.getDeviceCode(), mqttData.getValue());
                } else {
                    log.warn("设备[{}]类型[{}]不支持", device.getDeviceCode(), device.getDeviceType());
                }
            }
        }
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
