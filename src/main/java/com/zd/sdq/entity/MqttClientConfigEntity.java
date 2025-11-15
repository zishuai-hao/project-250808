package com.zd.sdq.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.company.cbf.starter.data.entity.MqttServerGateway;

@TableName(value = "s_mqtt_server_gateway")
public class MqttClientConfigEntity extends MqttServerGateway {
}
