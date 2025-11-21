package com.zd.sdq.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.cbf.starter.data.entity.DeviceInfo;
import com.company.cbf.starter.data.service.forward.BufferForwardMqttClientAdapter;
import com.company.cbf.starter.data.service.forward.device.DeviceType;
import com.zd.sdq.entity.DeviceInfoExt;
import com.zd.sdq.mapper.DeviceInfoExtMapper;
import io.vertx.core.Vertx;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 雷达水位仪TCP服务器管理器 (基于Vert.x)
 * 负责管理多个设备的TCP服务器
 * @author hzs
 * @date 2024/01/01
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RadarWaterLevelServerManager {

    private final DeviceInfoExtMapper deviceInfoExtMapper;
    private final BufferForwardMqttClientAdapter mqttAdapter;
    private final Vertx vertx;

    /**
     *  存储所有运行中的TCP服务器，key为设备编码
     */
    private final Map<String, SingleDeviceRadarWaterLevelServer> serverMap = new ConcurrentHashMap<>();
    
    /**
     *  存储端口占用情况，key为端口号，value为设备编码
     */
    private final Map<String, String> portMap = new ConcurrentHashMap<>();

    /**
     * 启动所有WLV设备的TCP服务器
     */
    @PostConstruct
    public void startAll() {
        log.info("开始初始化雷达水位仪TCP服务器(基于Vert.x)...");
        
        try {
            // 从数据库查询所有WLV设备
            List<DeviceInfoExt> wlvDevices = queryWlvDevices();
            
            if (wlvDevices.isEmpty()) {
                log.warn("未找到任何WLV设备配置");
                return;
            }
            
            // 为每个设备启动TCP服务器
            for (DeviceInfoExt device : wlvDevices) {
                try {
                    startServer(device);
                } catch (Exception e) {
                    log.error("启动设备[{}]的TCP服务器失败: {}", device.getDeviceCode(), e.getMessage(), e);
                }
            }
            
            log.info("雷达水位仪TCP服务器管理器初始化完成，成功启动 {} 个服务器", serverMap.size());
            
        } catch (Exception e) {
            log.error("初始化雷达水位仪TCP服务器管理器失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 停止所有TCP服务器
     */
    @PreDestroy
    public void stopAll() {
        log.info("开始停止所有雷达水位仪TCP服务器...");
        
        serverMap.forEach((deviceKey, server) -> {
            try {
                log.info("停止设备[{}]的TCP服务器", deviceKey);
                server.stop();
            } catch (Exception e) {
                log.error("停止设备[{}]的TCP服务器失败: {}", deviceKey, e.getMessage(), e);
            }
        });
        
        serverMap.clear();
        portMap.clear();
        log.info("所有雷达水位仪TCP服务器已停止");
    }

    /**
     * 启动单个设备的TCP服务器
     */
    public void startServer(DeviceInfoExt device) {
        String deviceCode = device.getDeviceCode();
        String port = device.getPort();
        
        // 验证设备配置
        if (!validateDeviceConfig(device)) {
            log.warn("设备[{}]配置不完整，跳过启动", deviceCode);
            return;
        }
        
        // 检查设备是否已经启动
        if (serverMap.containsKey(deviceCode)) {
            log.warn("设备[{}]的TCP服务器已经在运行中", deviceCode);
            return;
        }
        
        // 检查端口是否已被占用
        if (portMap.containsKey(port)) {
            String occupyingDevice = portMap.get(port);
            log.error("设备[{}]端口[{}]已被设备[{}]占用，无法启动", 
                    deviceCode, port, occupyingDevice);
            return;
        }
        
        try {
            // 创建并启动TCP服务器
            SingleDeviceRadarWaterLevelServer server = new SingleDeviceRadarWaterLevelServer(
                    device, mqttAdapter, vertx, deviceInfoExtMapper);
            server.start();
            
            // 添加到管理列表
            serverMap.put(deviceCode, server);
            portMap.put(port, deviceCode);
            
            log.info("设备[{}]的TCP服务器启动成功，端口: {}, 地址: {}",
                    deviceCode, port, device.getRemoteDeviceId());
            
        } catch (Exception e) {
            log.error("启动设备[{}]的TCP服务器失败: {}", deviceCode, e.getMessage(), e);
            // 启动失败时清理端口映射
            portMap.remove(port);
            throw e;
        }
    }

    /**
     * 停止单个设备的TCP服务器
     */
    public void stopServer(String deviceCode) {
        SingleDeviceRadarWaterLevelServer server = serverMap.get(deviceCode);
        if (server == null) {
            log.warn("设备[{}]的TCP服务器不存在或未运行", deviceCode);
            return;
        }
        
        try {
            server.stop();
            serverMap.remove(deviceCode);
            
            // 清理端口映射
            portMap.entrySet().removeIf(entry -> entry.getValue().equals(deviceCode));
            
            log.info("设备[{}]的TCP服务器已停止", deviceCode);
        } catch (Exception e) {
            log.error("停止设备[{}]的TCP服务器失败: {}", deviceCode, e.getMessage(), e);
        }
    }

    /**
     * 从数据库查询所有WLV设备
     */
    private List<DeviceInfoExt> queryWlvDevices() {
        LambdaQueryWrapper<DeviceInfoExt> queryWrapper = new LambdaQueryWrapper<>();
        
        // 查询条件: port和remote_device_id都不为空
        queryWrapper.isNotNull(DeviceInfoExt::getPort)
                .isNotNull(DeviceInfoExt::getRemoteDeviceId)
                .eq(DeviceInfo::getDeviceType, DeviceType.WLV);
        
        List<DeviceInfoExt> devices = deviceInfoExtMapper.selectList(queryWrapper);
        
        log.debug("查询到 {} 个WLV设备", devices.size());
        
        return devices;
    }

    /**
     * 验证设备配置是否完整
     */
    private boolean validateDeviceConfig(DeviceInfoExt device) {
        if (device == null) {
            log.warn("设备信息为空");
            return false;
        }
        
        if (device.getDeviceCode() == null || device.getDeviceCode().trim().isEmpty()) {
            log.warn("设备编号为空");
            return false;
        }
        
        if (device.getPort() == null || device.getPort().trim().isEmpty()) {
            log.warn("设备[{}]端口配置为空", device.getDeviceCode());
            return false;
        }
        
        try {
            Integer.parseInt(device.getPort());
        } catch (NumberFormatException e) {
            log.warn("设备[{}]端口配置无效: {}", device.getDeviceCode(), device.getPort());
            return false;
        }
        
        if (device.getRemoteDeviceId() == null || device.getRemoteDeviceId().trim().isEmpty()) {
            log.warn("设备[{}]远程设备地址为空", device.getDeviceCode());
            return false;
        }
        
        if (device.getMqttGatewayId() == null) {
            log.warn("设备[{}]MQTT网关ID为空", device.getDeviceCode());
            return false;
        }
        
        // 验证frequency字段
        if (device.getFrequency() == null || device.getFrequency().trim().isEmpty()) {
            log.warn("设备[{}]采集频率配置为空", device.getDeviceCode());
            return false;
        }
        
        try {
            int frequency = Integer.parseInt(device.getFrequency());
            if (frequency <= 0) {
                log.warn("设备[{}]采集频率必须大于0: {}", device.getDeviceCode(), frequency);
                return false;
            }
        } catch (NumberFormatException e) {
            log.warn("设备[{}]采集频率配置无效: {}", device.getDeviceCode(), device.getFrequency());
            return false;
        }
        
        // 验证baseline字段
        if (device.getBaseline() == null) {
            log.warn("设备[{}]基线值配置为空", device.getDeviceCode());
            return false;
        }
        
        return true;
    }

}
