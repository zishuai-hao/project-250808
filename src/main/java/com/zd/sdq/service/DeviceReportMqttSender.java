package com.zd.sdq.service;

import com.company.cbf.starter.data.entity.MqttData;
import com.company.cbf.starter.data.entity.MqttPubProtocol;
import com.company.cbf.starter.data.service.forward.ForwardMqttClientService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zd.sdq.mapper.MqttConfigEntityMapper;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.vertx.core.buffer.Buffer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;

/** 到点只发送一次；不进入逐设备缓冲区，也不保存待发送任务。 */
@Slf4j
@Service
public class DeviceReportMqttSender {
    private final ForwardMqttClientService clients;
    private final MqttConfigEntityMapper gateways;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public DeviceReportMqttSender(ForwardMqttClientService clients, MqttConfigEntityMapper gateways,
                                  ObjectMapper objectMapper) {
        this(clients, gateways, objectMapper, Clock.systemUTC());
    }

    public DeviceReportMqttSender(ForwardMqttClientService clients, MqttConfigEntityMapper gateways,
                                  ObjectMapper objectMapper, Clock clock) {
        this.clients = clients;
        this.gateways = gateways;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public void sendOnce(Long gatewayId, List<MqttData> data) {
        if (data.isEmpty()) return;
        try {
            var gateway = gateways.selectById(gatewayId);
            var client = clients.getMqttClient(gatewayId);
            if (gateway == null || !gateway.isEnable() || client == null || !client.isConnected()) {
                log.warn("跳过本次定时上报，MQTT网关不可用: {}，测点数: {}", gatewayId, data.size());
                return;
            }
            long sentAt = clock.millis();
            data.forEach(item -> item.setSampleTime(sentAt));
            String payload = objectMapper.writeValueAsString(new MqttPubProtocol(gateway.getDataTag(), data));
            client.publish(gateway.getTopic(), Buffer.buffer(payload), MqttQoS.AT_MOST_ONCE,
                    false, false, result -> {
                        if (result.succeeded()) {
                            log.info("定时上报已提交MQTT，网关: {}，测点数: {}，发送时间: {}",
                                    gatewayId, data.size(), sentAt);
                        } else {
                            log.error("本次定时上报发送失败，不重试，网关: {}", gatewayId, result.cause());
                        }
                    });
        } catch (Exception e) {
            log.error("本次定时上报失败，不重试，网关: {}", gatewayId, e);
        }
    }
}
