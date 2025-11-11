//package com.zd.sdq.service.mqtt;
//
//import cn.hutool.core.util.IdUtil;
//import com.zd.sdq.config.MqttConfig;
//import io.vertx.core.Vertx;
//import io.vertx.mqtt.MqttClient;
//import io.vertx.mqtt.MqttClientOptions;
//import lombok.Getter;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.boot.CommandLineRunner;
//import org.springframework.stereotype.Service;
//
//import javax.annotation.Resource;
//
//
///**
// * @author hzs
// * @date 2023/07/14
// */
//@Slf4j
//@Service
//public class MqttService implements CommandLineRunner {
//
//    @Resource
//    MqttConfig config;
//    @Getter
//    private MqttClient mqttClient;
//
//    @Override
//    public void run(String... args)   {
//        final Vertx vertx = Vertx.vertx();
//        final MqttClientOptions mqttClientOptions = new MqttClientOptions();
//        if (config.getUsername() != null) {
//            mqttClientOptions.setUsername(config.getUsername());
//        }
//        if (config.getPassword() != null) {
//            mqttClientOptions.setPassword(config.getPassword());
//        }
//        mqttClientOptions.setCleanSession(true);
//        mqttClientOptions.setAutoKeepAlive(true);
//        mqttClientOptions.setClientId("client_data_push" + IdUtil.nanoId());
//        mqttClientOptions.setMaxInflightQueue(65535);
//        mqttClientOptions.setReconnectInterval(10 * 1000);
//        final MqttClient mqttClientTemp = MqttClient.create(vertx, mqttClientOptions);
//        connect(mqttClientTemp);
//        vertx.setPeriodic(10000, id -> {
//            if (!this.mqttClient.isConnected()) {
//                log.error("Mqtt已掉线，启动重连");
//                log.info("Mqtt已掉线，启动重连");
//                connect(mqttClientTemp);
//            }
//        });
//    }
//
//    private void connect(MqttClient mqttClientTemp) {
//        mqttClientTemp
//                .connect(config.getPort(), config.getUrl(), s -> {
//                    if (s.succeeded()) {
//                        log.info("MQTT Client connect success.");
//                        this.mqttClient = mqttClientTemp;
//                    } else {
//                        log.error("Client connect fail: ", s.cause());
////                        try {
////                            Thread.sleep(5000);
////                        } catch (InterruptedException e) {
////                            e.printStackTrace();
////                        }
////                        connect();
//                    }
//                })
//                .exceptionHandler(event -> log.error("error : {}", event.getMessage()));
//    }
//
//
//}
