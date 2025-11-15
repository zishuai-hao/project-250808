package com.zd.sdq.service;

import cn.hutool.core.util.HexUtil;
import cn.hutool.core.util.NumberUtil;
import com.company.cbf.starter.data.service.forward.BufferForwardMqttClientAdapter;
import com.zd.sdq.entity.DeviceInfoExt;
import com.zd.sdq.util.ModbusRtuUtil;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单个雷达水位仪设备的TCP服务器 (基于Vert.x)
 * 每个设备独占一个端口,连接一个设备
 * @author hzs
 * @date 2024/01/01
 */
@Slf4j
public class SingleDeviceRadarWaterLevelServer {

    private final DeviceInfoExt deviceInfo;
    private final BufferForwardMqttClientAdapter mqttAdapter;
    private final Vertx vertx;
    private final int port;
    private final String deviceAddress;
    private final int sendIntervalMs;

    private NetServer netServer;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Long sendTimerId;
    private NetSocket currentSocket;
    private final AtomicLong lastDataTime = new AtomicLong(0);

    /**
     * 构造函数
     * @param deviceInfo 设备信息
     * @param mqttAdapter MQTT适配器
     * @param vertx Vert.x实例
     */
    public SingleDeviceRadarWaterLevelServer(DeviceInfoExt deviceInfo, 
                                            BufferForwardMqttClientAdapter mqttAdapter,
                                            Vertx vertx) {
        this.deviceInfo = deviceInfo;
        this.mqttAdapter = mqttAdapter;
        this.vertx = vertx;
        this.port = Integer.parseInt(deviceInfo.getPort());
        this.deviceAddress = deviceInfo.getRemoteDeviceId();
        // frequency是秒,转换为毫秒
        int frequency = Integer.parseInt(deviceInfo.getFrequency());
        this.sendIntervalMs = frequency * 1000;
    }

    /**
     * 启动TCP服务器
     */
    public void start() {
        if (running.get()) {
            log.warn("设备[{}]的TCP服务器已在运行中,端口: {}", deviceInfo.getDeviceCode(), port);
            return;
        }

        netServer = vertx.createNetServer();

        netServer.connectHandler(this::handleConnection)
                .listen(port, res -> {
                    if (res.succeeded()) {
                        running.set(true);
                        log.info("设备[{}]的雷达水位仪TCP服务器启动成功,监听端口: {}, 设备地址: {}, 发送间隔: {}ms",
                                deviceInfo.getDeviceCode(), port, deviceAddress, sendIntervalMs);
                    } else {
                        log.error("设备[{}]的雷达水位仪TCP服务器启动失败: {}",
                                deviceInfo.getDeviceCode(), res.cause().getMessage(), res.cause());
                    }
                });
    }

    /**
     * 停止TCP服务器
     */
    public void stop() {
        if (!running.get()) {
            log.warn("设备[{}]的TCP服务器未运行", deviceInfo.getDeviceCode());
            return;
        }

        running.set(false);

        // 取消定时发送任务
        if (sendTimerId != null) {
            vertx.cancelTimer(sendTimerId);
            sendTimerId = null;
        }

        // 关闭当前连接
        if (currentSocket != null) {
            currentSocket.close();
            currentSocket = null;
        }

        // 关闭服务器
        if (netServer != null) {
            netServer.close(res -> {
                if (res.succeeded()) {
                    log.info("设备[{}]的雷达水位仪TCP服务器已停止", deviceInfo.getDeviceCode());
                } else {
                    log.error("设备[{}]的雷达水位仪TCP服务器停止失败: {}",
                            deviceInfo.getDeviceCode(), res.cause().getMessage());
                }
            });
        }
    }

    /**
     * 处理客户端连接
     */
    private void handleConnection(NetSocket socket) {
        String clientAddress = socket.remoteAddress().host() + ":" + socket.remoteAddress().port();
        log.info("设备[{}]客户端连接: {}", deviceInfo.getDeviceCode(), clientAddress);

        // 如果已有连接,关闭旧连接
        if (currentSocket != null) {
            log.warn("设备[{}]已有连接,关闭旧连接", deviceInfo.getDeviceCode());
            currentSocket.close();
        }

        currentSocket = socket;

        // 设置连接处理器
        socket.handler(this::handleData)
                .closeHandler(v -> handleClose(clientAddress))
                .exceptionHandler(e -> handleException(clientAddress, e));

        // 启动定时发送任务
        startSendTimer(socket);
    }

    /**
     * 启动定时发送任务
     */
    private void startSendTimer(NetSocket socket) {
        // 取消之前的定时器
        if (sendTimerId != null) {
            vertx.cancelTimer(sendTimerId);
        }

        // 创建新的定时器
        sendTimerId = vertx.setPeriodic(sendIntervalMs, id -> {
            if (running.get() && socket != null && !socket.writeQueueFull()) {
                try {
                    // 构造并发送 Modbus 读保持寄存器请求
                    byte[] query = ModbusRtuUtil.buildReadRequest(deviceAddress, 0x0001, 0x0001);
                    Buffer buffer = Buffer.buffer(query);
                    
                    socket.write(buffer, res -> {
                        if (res.succeeded()) {
                            log.debug("设备[{}]发送查询水位指令: {}", 
                                    deviceInfo.getDeviceCode(), HexUtil.encodeHexStr(query));
                        } else {
                            log.error("设备[{}]发送指令失败: {}", 
                                    deviceInfo.getDeviceCode(), res.cause().getMessage());
                        }
                    });
                } catch (Exception e) {
                    log.error("设备[{}]构造Modbus请求失败: {}", 
                            deviceInfo.getDeviceCode(), e.getMessage(), e);
                }
            }
        });

        log.info("设备[{}]启动定时发送任务,间隔: {}ms", deviceInfo.getDeviceCode(), sendIntervalMs);
    }

    /**
     * 处理接收到的数据
     */
    private void handleData(Buffer buffer) {
        try {
            byte[] data = buffer.getBytes();
            log.debug("设备[{}]收到响应: {}", deviceInfo.getDeviceCode(), HexUtil.encodeHexStr(data));

            // 过滤设备活性消息 (FE开头的消息)
            if (data.length > 0 && (data[0] & 0xFF) == 0xFE) {
                log.debug("设备[{}]收到活性消息,已过滤: {}", deviceInfo.getDeviceCode(), HexUtil.encodeHexStr(data));
                return;
            }

            // CRC 校验
            if (!ModbusRtuUtil.verifyCrc(data)) {
                log.warn("设备[{}]响应CRC校验失败", deviceInfo.getDeviceCode());
                return;
            }


            // 解析水位
            Double waterLevelMeter = parseModbusWaterLevel(data);
            if (waterLevelMeter != null) {
                log.info("设备[{}]当前水位: {} m", deviceInfo.getDeviceCode(), waterLevelMeter);
                lastDataTime.set(System.currentTimeMillis());
                
                // 转发数据到MQTT
                forwardDataToMqtt(waterLevelMeter);
            } else {
                log.warn("设备[{}]无法从响应中解析水位", deviceInfo.getDeviceCode());
            }
        } catch (Exception e) {
            log.error("设备[{}]处理数据失败: {}", deviceInfo.getDeviceCode(), e.getMessage(), e);
        }
    }

    /**
     * 处理连接关闭
     */
    private void handleClose(String clientAddress) {
        log.info("设备[{}]客户端连接已关闭: {}", deviceInfo.getDeviceCode(), clientAddress);
        
        // 取消定时发送任务
        if (sendTimerId != null) {
            vertx.cancelTimer(sendTimerId);
            sendTimerId = null;
        }
        
        currentSocket = null;
    }

    /**
     * 处理连接异常
     */
    private void handleException(String clientAddress, Throwable e) {
        log.error("设备[{}]客户端连接异常: {}, 错误: {}", 
                deviceInfo.getDeviceCode(), clientAddress, e.getMessage(), e);
    }

    /**
     * 解析 Modbus 响应中的水位
     * 规则：
     * - 功能码 0x03
     * - 字节数 0x02
     * - 数据为 2 字节无符号整数，单位毫米，需 /1000 转米
     */
    private Double parseModbusWaterLevel(byte[] resp) {
        try {
            if (resp.length < 7) {
                return null;
            }
            // 基本帧: 地址(1) 功能码(1) 字节数(1) 数据(2) CRC(2)
            byte addr = resp[0];
            byte func = resp[1];
            int byteCount = resp[2] & 0xFF;
            if (func != 0x03 || byteCount != 0x02) {
                return null;
            }
            int hi = resp[3] & 0xFF;
            int lo = resp[4] & 0xFF;
            int value = (hi << 8) | lo; // 0x0B99 = 2969
            // 2.969 m
            return value / 1000.0;
        } catch (Exception e) {
            log.warn("设备[{}]解析Modbus水位失败: {}", deviceInfo.getDeviceCode(), e.getMessage());
            return null;
        }
    }

    /**
     * 转发数据到MQTT
     */
    private void forwardDataToMqtt(Double waterLevel) {
        try {
            double diff = deviceInfo.getBaseline() - waterLevel;

            // 通过BufferForwardMqttClientAdapter转发数据
            mqttAdapter.push(deviceInfo.getDeviceCode(), Collections.singletonList(Collections.singletonList(NumberUtil.roundStr(diff, 3))));
            
            log.debug("设备[{}]数据已转发到MQTT: {} m", deviceInfo.getDeviceCode(), waterLevel);
        } catch (Exception e) {
            log.error("设备[{}]转发数据到MQTT失败: {}", deviceInfo.getDeviceCode(), e.getMessage(), e);
        }
    }
}
