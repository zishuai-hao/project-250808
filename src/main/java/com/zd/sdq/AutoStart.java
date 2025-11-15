package com.zd.sdq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.cbf.starter.data.entity.DeviceInfo;
import com.company.cbf.starter.data.entity.MqttServerGateway;
import com.company.cbf.starter.data.service.forward.BufferForwardMqttClientAdapter;
import com.company.cbf.starter.data.service.forward.DeviceInfoManager;
import com.zd.sdq.entity.DeviceInfoExt;
import com.zd.sdq.entity.MqttClientConfigEntity;
import com.zd.sdq.mapper.DeviceInfoExtMapper;
import com.zd.sdq.mapper.MqttConfigEntityMapper;
import com.zd.sdq.service.incline.InclineApiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;


/**
 * @author hzs
 * @date 2023/12/08
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutoStart implements CommandLineRunner {
    private final InclineApiService inclineApiService;
    private final MqttConfigEntityMapper mqttConfigEntityMapper;
    private final BufferForwardMqttClientAdapter bufferForwardMqttClientAdapter;
    private final DeviceInfoExtMapper deviceInfoExtMapper;
    private final DeviceInfoManager deviceInfoManager;

    @Override
    public void run(String... args) {
        List<MqttClientConfigEntity> mqttClientConfigEntities = mqttConfigEntityMapper.selectList(new LambdaQueryWrapper<MqttClientConfigEntity>().eq(MqttServerGateway::isEnable, 1));
        log.info("启动的MQTT客户端配置数量: {}", mqttClientConfigEntities.size());

        // 启动带缓冲的MQTT客户端
        for (MqttClientConfigEntity mqttClientConfigEntity : mqttClientConfigEntities) {
            bufferForwardMqttClientAdapter.addMqttClientAdapter(mqttClientConfigEntity);
        }

        // 加载设备信息到服务器中
        deviceInfoExtMapper.selectList(new LambdaQueryWrapper<DeviceInfoExt>().eq(DeviceInfo::isEnable, 1)).forEach(deviceInfoExt -> {
            deviceInfoManager.registerDevice(deviceInfoExt, deviceInfoExt.getMqttGatewayId());
        });
    }

    // 根据配置文件的间隔时间，自动刷新Token， 计算方式为 配置文件的间隔时间 * 60_000 
    @Scheduled(fixedRateString = "#{${incline.api.token-refresh-interval} * 60000}")
    public void refreshTokenScheduled() {
        inclineApiService.refreshToken();
    }

}
