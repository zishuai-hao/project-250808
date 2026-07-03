package com.zd.sdq.service;

import cn.hutool.core.date.DateUtil;
import com.company.cbf.starter.data.constant.NetworkGatewayType;
import com.company.cbf.starter.data.service.forward.device.DeviceType;
import com.zd.sdq.entity.DeviceInfoExt;
import com.zd.sdq.entity.dto.DeviceLatestDataDTO;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 设备最新数据内存缓存.
 */
@Service
public class DeviceLatestDataCache {
    private static final long DEFAULT_STALE_THRESHOLD_MS = 10 * 60 * 1000L;
    private static final long MIN_STALE_THRESHOLD_MS = 5 * 60 * 1000L;

    private final Map<String, DeviceLatestDataDTO> latestDataMap = new ConcurrentHashMap<>();

    public void registerDevice(DeviceInfoExt deviceInfo) {
        if (deviceInfo == null || deviceInfo.getDeviceCode() == null) {
            return;
        }

        latestDataMap.compute(deviceInfo.getDeviceCode(), (deviceCode, current) -> {
            DeviceLatestDataDTO snapshot = current == null ? new DeviceLatestDataDTO() : copyOf(current);
            applyDeviceInfo(snapshot, deviceInfo);
            if (snapshot.getStatus() == null) {
                snapshot.setStatus("NO_DATA");
                snapshot.setStatusText("暂无数据");
                snapshot.setMessage("设备已注册, 等待采集数据");
            }
            return snapshot;
        });
    }

    public void updateLatestData(DeviceInfoExt deviceInfo, String source, Map<String, Object> latestData, String dataTime) {
        if (deviceInfo == null || deviceInfo.getDeviceCode() == null) {
            return;
        }

        latestDataMap.compute(deviceInfo.getDeviceCode(), (deviceCode, current) -> {
            DeviceLatestDataDTO snapshot = current == null ? new DeviceLatestDataDTO() : copyOf(current);
            applyDeviceInfo(snapshot, deviceInfo);
            snapshot.setSource(source);
            snapshot.setLatestData(new LinkedHashMap<>(latestData));
            snapshot.setDataTime(dataTime);
            snapshot.setReceivedAt(DateUtil.now());
            snapshot.setReceivedAtMillis(System.currentTimeMillis());
            snapshot.setStatus("ONLINE");
            snapshot.setStatusText("正常");
            snapshot.setMessage("最新数据已接收");
            return snapshot;
        });
    }

    public List<DeviceLatestDataDTO> listLatestData() {
        return latestDataMap.values().stream()
                .map(this::withCalculatedStatus)
                .sorted(Comparator.comparing(DeviceLatestDataDTO::getDeviceCode, Comparator.nullsLast(String::compareTo)))
                .collect(Collectors.toList());
    }

    public DeviceLatestDataDTO getLatestData(String deviceCode) {
        DeviceLatestDataDTO snapshot = latestDataMap.get(deviceCode);
        if (snapshot == null) {
            return null;
        }
        return withCalculatedStatus(snapshot);
    }

    private void applyDeviceInfo(DeviceLatestDataDTO snapshot, DeviceInfoExt deviceInfo) {
        snapshot.setDeviceCode(deviceInfo.getDeviceCode());
        snapshot.setMonitoringContent(deviceInfo.getMonitoringContent());
        DeviceType deviceType = deviceInfo.getDeviceType();
        snapshot.setDeviceType(deviceType == null ? null : deviceType.name());
        snapshot.setDeviceTypeName(deviceType == null ? null : deviceType.getDescription());
        NetworkGatewayType gatewayType = deviceInfo.getNetworkGatewayType();
        snapshot.setNetworkGatewayType(gatewayType == null ? null : gatewayType.name());
        snapshot.setRemoteDeviceId(deviceInfo.getRemoteDeviceId());
        snapshot.setPort(deviceInfo.getPort());
        snapshot.setFrequency(deviceInfo.getFrequency());
        snapshot.setEnable(deviceInfo.isEnable());
    }

    private DeviceLatestDataDTO withCalculatedStatus(DeviceLatestDataDTO source) {
        DeviceLatestDataDTO snapshot = copyOf(source);
        if (Boolean.FALSE.equals(snapshot.getEnable())) {
            snapshot.setStatus("DISABLED");
            snapshot.setStatusText("停用");
            snapshot.setMessage("设备未启用");
            return snapshot;
        }
        if (snapshot.getReceivedAtMillis() == null) {
            snapshot.setStatus("NO_DATA");
            snapshot.setStatusText("暂无数据");
            snapshot.setMessage("设备已注册, 等待采集数据");
            return snapshot;
        }
        if (System.currentTimeMillis() - snapshot.getReceivedAtMillis() > resolveStaleThresholdMs(snapshot.getFrequency())) {
            snapshot.setStatus("STALE");
            snapshot.setStatusText("超时");
            snapshot.setMessage("超过预期采集间隔未收到新数据");
            return snapshot;
        }
        snapshot.setStatus("ONLINE");
        snapshot.setStatusText("正常");
        snapshot.setMessage("最新数据已接收");
        return snapshot;
    }

    private long resolveStaleThresholdMs(String frequency) {
        if (frequency == null || frequency.trim().isEmpty()) {
            return DEFAULT_STALE_THRESHOLD_MS;
        }
        try {
            long frequencySeconds = Long.parseLong(frequency.trim());
            return Math.max(MIN_STALE_THRESHOLD_MS, frequencySeconds * 1000L * 3);
        } catch (NumberFormatException e) {
            return DEFAULT_STALE_THRESHOLD_MS;
        }
    }

    private DeviceLatestDataDTO copyOf(DeviceLatestDataDTO source) {
        DeviceLatestDataDTO target = new DeviceLatestDataDTO();
        target.setDeviceCode(source.getDeviceCode());
        target.setMonitoringContent(source.getMonitoringContent());
        target.setDeviceType(source.getDeviceType());
        target.setDeviceTypeName(source.getDeviceTypeName());
        target.setNetworkGatewayType(source.getNetworkGatewayType());
        target.setRemoteDeviceId(source.getRemoteDeviceId());
        target.setPort(source.getPort());
        target.setFrequency(source.getFrequency());
        target.setEnable(source.getEnable());
        target.setSource(source.getSource());
        target.setLatestData(source.getLatestData() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source.getLatestData()));
        target.setDataTime(source.getDataTime());
        target.setReceivedAt(source.getReceivedAt());
        target.setReceivedAtMillis(source.getReceivedAtMillis());
        target.setStatus(source.getStatus());
        target.setStatusText(source.getStatusText());
        target.setMessage(source.getMessage());
        return target;
    }
}
