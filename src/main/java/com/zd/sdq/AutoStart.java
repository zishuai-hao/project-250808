//package com.zd.sdq;
//
//import cn.hutool.core.bean.BeanUtil;
//import cn.hutool.core.collection.CollUtil;
//import cn.hutool.core.date.DateField;
//import cn.hutool.core.date.DateTime;
//import cn.hutool.core.date.DateUtil;
//import cn.hutool.core.util.RandomUtil;
//import cn.hutool.core.util.ReflectUtil;
//import cn.hutool.json.JSONUtil;
//import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
//import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
//import com.zd.sdq.config.SystemConfig;
//import com.zd.sdq.entity.*;
//import com.zd.sdq.entity.dto.SensorDataDTO;
//import com.zd.sdq.mapper.*;
//import com.zd.sdq.protocol.ChannelData;
//import com.zd.sdq.protocol.MqttPubProtocol;
//import com.zd.sdq.service.daas.DaasFileMonitor;
//import com.zd.sdq.service.http.RequestUtil;
//import com.zd.sdq.service.mqtt.MqttConstant;
//import com.zd.sdq.service.mqtt.MqttService;
//import io.netty.handler.codec.mqtt.MqttQoS;
//import io.vertx.core.buffer.Buffer;
//import io.vertx.mqtt.MqttClient;
//import io.vertx.mqtt.impl.MqttClientImpl;
//import lombok.RequiredArgsConstructor;
//import lombok.SneakyThrows;
//import lombok.extern.slf4j.Slf4j;
//import org.jetbrains.annotations.NotNull;
//import org.springframework.boot.CommandLineRunner;
//import org.springframework.scheduling.annotation.Scheduled;
//import org.springframework.stereotype.Component;
//
//import java.math.RoundingMode;
//import java.util.*;
//import java.util.concurrent.TimeUnit;
//import java.util.stream.Collectors;
//
//import static java.util.stream.Collectors.groupingBy;
//import static org.apache.commons.math3.util.Precision.round;
//
///**
// * @author hzs
// * @date 2023/12/08
// */
//@Slf4j
//@Component
//@RequiredArgsConstructor
//public class AutoStart implements CommandLineRunner {
//    private final DaasFileMonitor daasFileMonitor;
//    private final BaseGnssDataMapper baseGnssDataMapper;
//    private final DeviceMapper deviceMapper;
//    private final SensorDataMapper sensorDataMapper;
//    private final SystemConfig systemConfig;
//
//    private final MqttService mqttService;
//
//    private List<BaseGnssData> baseGnssDataList;
//    private Integer index = 0;
//
//    @Override
//    public void run(String... args) throws Exception {
//        baseGnssDataList = baseGnssDataMapper.selectList(null);
//
//        if (!systemConfig.isDaasDataEnable()) {
//            return;
//        }
//        // 1. 启动daas
//        daasFileMonitor.start();
//    }
//
//    //    @Scheduled(fixedRate = 30, timeUnit = TimeUnit.SECONDS)
//    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.MINUTES , initialDelay = 1)
//    public void pushFrequencyOne() {
//        final ArrayList<String> strings = CollUtil.toList("应变", "主梁挠度", "温度");
//        pushNormalData(strings,1);
//    }
//
//    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.MINUTES , initialDelay = 1)
//    public void pushFrequencyTen() {
//        final ArrayList<String> strings = CollUtil.toList("裂缝监测");
////        pushNormalData(strings,10);
//        pushNormalData(strings,1);
//    }
//
//    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.MINUTES , initialDelay = 2)
//    public void pushFrequencyTwenty() {
//        final ArrayList<String> strings = CollUtil.toList("位移");
////        pushNormalData(strings,20);
//        pushNormalData(strings,1);
//    }
//
//
//    private void pushNormalData(ArrayList<String> strings, int frequency) {
//        if (!systemConfig.isMqttDataEnable()) {
//            return;
//        }
//        if (mqttService.getMqttClient() == null) {
//            return;
//        }
//        final DateTime now = DateTime.now();
//        final DateTime startDate = DateUtil.offsetMinute(now, -3).setField(DateField.SECOND, 0).setField(DateField.MILLISECOND, 0);
//        final DateTime endDate = DateUtil.offsetMinute(now, -2).setField(DateField.SECOND, 0).setField(DateField.MILLISECOND, 0);
//        // 2. 定时推送mqtt
//        final List<Device> strainDeviceList = deviceMapper.selectList(new LambdaQueryWrapper<Device>()
//                .in(Device::getDeviceType, strings));
//        final Map<String, List<Device>> collect = strainDeviceList.stream().collect(groupingBy(Device::getCjyNo));
//        collect.forEach((cjyNo, deviceList) -> {
//            // 根据设备号查询传感器数据
//            final Set<Integer> stationIds = deviceList.stream().map(Device::getDeviceKey).collect(Collectors.toSet());
//            final List<SensorDataDTO> sensorData = sensorDataMapper.listData(new QueryWrapper<SensorDataDTO>()
//                    .in("d.device_key", stationIds)
//                    .between("sd.data_time", startDate.getTime(), endDate.getTime())
//            );
//
//            if (sensorData.isEmpty()) {
//                return;
//            }
//
//            final List<ChannelData> collectData = formatData(frequency, sensorData);
//
//            log.info("原始数据数量: {}，数据频率: {}, 推送数据数量: {}", sensorData.size(), frequency, collectData.size());
//            // 构造推送报文
//            final MqttPubProtocol mqttPubProtocol = new MqttPubProtocol();
//            mqttPubProtocol.setBridge_code(MqttConstant.BRIDGE_CODE);
//            mqttPubProtocol.setCjy_no(cjyNo);
//            mqttPubProtocol.setChannel_data(collectData);
//
//            // 推送
//            final MqttClient mqttClient = mqttService.getMqttClient();
//
//            mqttClient.publish(MqttConstant.TOPIC, Buffer.buffer(JSONUtil.toJsonStr(mqttPubProtocol)), MqttQoS.AT_MOST_ONCE, false, false);
//        });
//    }
//
//    @NotNull
//    private List<ChannelData> formatData(int frequency, List<SensorDataDTO> sensorData) {
//        // 转换数据格式
//        final List<ChannelData> collectData = sensorData.stream().map(data -> {
//            final ArrayList<ChannelData> dataList = new ArrayList<>(frequency);
//            for (int i = 0; i < frequency; i++) {
//                final ChannelData channelData = new ChannelData();
//                channelData.setChannel_no(data.getChannelNo());
//                channelData.setValue(data.getDataValue());
//                channelData.setCode_no(data.getCodeNo());
//                channelData.setMonitoring_point_code(data.getRemotePointCode());
//                channelData.setSample_time(data.getDataTime());
//                dataList.add(channelData);
//            }
//            return dataList;
//        }).flatMap(Collection::stream).collect(Collectors.toList());
//        return collectData;
//    }
//
//    /**
//     * 温湿度
//     */
//    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.MINUTES, initialDelay = 2)
////    @Scheduled(fixedRate = 30, timeUnit = TimeUnit.SECONDS)
//    public void pushWsdj() {
//        if (!systemConfig.isWsdjDataEnable()) {
//            return;
//        }
//        if (mqttService.getMqttClient() == null) {
//            return;
//        }
//        final DateTime now = DateTime.now();
//        final DateTime startDate = DateUtil.offsetMinute(now, -3).setField(DateField.SECOND, 0).setField(DateField.MILLISECOND, 0);
//        final DateTime endDate = DateUtil.offsetMinute(now, -2).setField(DateField.SECOND, 0).setField(DateField.MILLISECOND, 0);
//        // 2. 定时推送mqtt
//        final List<Device> strainDeviceList = deviceMapper.selectList(new LambdaQueryWrapper<Device>()
//                // 处理温湿度计、桥梁倾斜
//                .in(Device::getDeviceType, "humidity", "temperature", "x", "y"));
//        final Map<String, List<Device>> collect = strainDeviceList.stream().collect(groupingBy(Device::getCjyNo));
//        collect.forEach((cjyNo, deviceList) -> {
//            // 根据设备号查询传感器数据
//            final Set<Integer> stationIds = deviceList.stream().map(Device::getDeviceKey).collect(Collectors.toSet());
//            final List<SensorDataDTO> sensorData = sensorDataMapper.listWsdjData(new QueryWrapper<SensorDataDTO>()
//                    .in("d.device_key", stationIds)
//                    .between("sd.data_time", startDate.getTime(), endDate.getTime())
//            );
//
//            if (sensorData.isEmpty()) {
//                return;
//            }
//
//            // 转换数据格式
//            final List<ChannelData> collectData = formatData(1, sensorData);
//
//            // 构造推送报文
//            final MqttPubProtocol mqttPubProtocol = new MqttPubProtocol();
//            mqttPubProtocol.setBridge_code(MqttConstant.BRIDGE_CODE);
//            mqttPubProtocol.setCjy_no(cjyNo);
//            mqttPubProtocol.setChannel_data(collectData);
//
//            // 推送
//            final MqttClient mqttClient = mqttService.getMqttClient();
//
//            mqttClient.publish(MqttConstant.TOPIC, Buffer.buffer(JSONUtil.toJsonStr(mqttPubProtocol)), MqttQoS.AT_MOST_ONCE, false, false);
//        });
//    }
//
//    String bridgeUp = "59566";
//    String topSpeed = "59636";
//    String topDirection = "59637";
//
//    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.MINUTES, initialDelay = 3)
////    @Scheduled(fixedRate = 30, timeUnit = TimeUnit.SECONDS)
//    public void pushWind() {
//        if (!systemConfig.isWindDataEnable()) {
//            return;
//        }
//        if (mqttService.getMqttClient() == null) {
//            return;
//        }
//        final DateTime now = DateTime.now();
//        final DateTime startDate = DateUtil.offsetMinute(now, -3).setField(DateField.SECOND, 0).setField(DateField.MILLISECOND, 0);
//        final DateTime endDate = DateUtil.offsetMinute(now, -2).setField(DateField.SECOND, 0).setField(DateField.MILLISECOND, 0);
//
//        // 2. 定时推送mqtt
//        final List<Device> strainDeviceList = deviceMapper.selectList(new LambdaQueryWrapper<Device>()
//                .in(Device::getDeviceType, "风速风向", "风速", "风向"));
//        final Map<String, List<Device>> collect = strainDeviceList.stream().collect(groupingBy(Device::getCjyNo));
//        collect.forEach((cjyNo, deviceList) -> {
//            // 根据设备号查询传感器数据
//            final Set<Integer> stationIds = deviceList.stream().map(Device::getDeviceKey).collect(Collectors.toSet());
//            final List<SensorDataDTO> sensorData = sensorDataMapper.listData(new QueryWrapper<SensorDataDTO>()
//                    .in("d.device_key", stationIds)
//                    .between("sd.data_time", startDate.getTime(), endDate.getTime())
//            );
//
//            if (sensorData.isEmpty()) {
//                return;
//            }
//
//            // 转换数据格式
//            final List<ChannelData> collectData = sensorData.stream().map(data -> {
//                final ChannelData channelData = new ChannelData();
//                channelData.setChannel_no(data.getChannelNo());
//                // 处理桥面风向风速计
//                if (data.getDeviceKey().equals(bridgeUp)) {
//                    // 处理风向
//                    if (data.getDataKey().equals("direction")) {
//                        data.setCodeNo("2");
//                        if (data.getDataValue() == 0d) {
//                            // 获取最后一条非0数据
//                            final SensorData lastData = sensorDataMapper.selectOne(new LambdaQueryWrapper<SensorData>()
//                                    .eq(SensorData::getDataKey, data.getDataKey())
//                                    .eq(SensorData::getDeviceKey, data.getDeviceKey())
//                                    .gt(SensorData::getDataValue, 0.001)
//                                    .orderByDesc(SensorData::getDataTime)
//                                    .last("limit 1"));
//                            data.setDataValue(Math.max(0, round(lastData.getDataValue() + RandomUtil.randomDouble(-0.2, 0.2), 1)));
//                        }
//                        final Optional<SensorDataDTO> first = sensorData.stream().filter(d -> d.getDeviceKey().equals(topDirection) && d.getDataTime().equals(data.getDataTime()))
//                                .findFirst();
//                        first.ifPresent(sensorDataDTO -> sensorDataDTO.setDataValue(Math.max(0, round(data.getDataValue() + RandomUtil.randomDouble(-2, 2), 1))));
//
//                    } else if (data.getDataKey().equals("speed")) {
//                        data.setCodeNo("1");
//                    }
//                }
//                // 处理塔顶风向
//                if (data.getDeviceKey().equals(topDirection)) {
//                    data.setCodeNo("4");
//                    final Optional<SensorDataDTO> first = sensorData.stream().filter(d -> d.getDeviceKey().equals(bridgeUp) && d.getDataKey().equals(data.getDataKey()) && d.getDataTime().equals(data.getDataTime()))
//                            .findFirst();
//                    if (first.isPresent()) {
//                        data.setDataValue(Math.max(0, round(first.get().getDataValue() + RandomUtil.randomDouble(-2, 2), 1)));
//                    } else {
//                        return null;
//                    }
//                }
//
//                // 塔顶风速
//                if (data.getDeviceKey().equals(topSpeed)) {
//                    data.setCodeNo("3");
//                }
//
//                channelData.setValue(data.getDataValue());
//                channelData.setCode_no(data.getCodeNo());
//                channelData.setMonitoring_point_code(data.getRemotePointCode());
//                channelData.setSample_time(data.getDataTime());
//                return channelData;
//            }).filter(Objects::nonNull).collect(Collectors.toList());
//
//            // 构造推送报文
//            final MqttPubProtocol mqttPubProtocol = new MqttPubProtocol();
//            mqttPubProtocol.setBridge_code(MqttConstant.BRIDGE_CODE);
//            mqttPubProtocol.setCjy_no(cjyNo);
//            mqttPubProtocol.setChannel_data(collectData);
//
//            // 推送
//            final MqttClient mqttClient = mqttService.getMqttClient();
//
//            mqttClient.publish(MqttConstant.TOPIC, Buffer.buffer(JSONUtil.toJsonStr(mqttPubProtocol)), MqttQoS.AT_MOST_ONCE, false, false);
//        });
//    }
//
//
//    @Scheduled(fixedRate = 10, timeUnit = TimeUnit.SECONDS)
//    public void testQueueCount() {
//        final MqttClientImpl mqttClient = (MqttClientImpl) mqttService.getMqttClient();
//        final Object countInflightQueue = ReflectUtil.getFieldValue(mqttClient, "countInflightQueue");
//        log.info("消息堆积数量：{}", countInflightQueue);
//    }
//
//    /**
//     * 添加gnss数据
//     */
//    @Scheduled(cron = "0/1 * * * * ? ")
//    public void addGnssData() {
//        if (!systemConfig.isGnssDataEnable()) {
//            return;
//        }
//        if (mqttService.getMqttClient() == null) {
//            return;
//        }
//        if (CollUtil.isEmpty(baseGnssDataList)) {
//            return;
//        }
//        // 3. 定时添加gnss数据
//        if (index >= baseGnssDataList.size()) {
//            index = 0;
//        }
//        final GnssData gnssData = new GnssData();
//
//        BeanUtil.copyProperties(baseGnssDataList.get(index++), gnssData);
//        gnssData.setId(null);
//        gnssData.setDx(round(gnssData.getDx() + RandomUtil.randomDouble(-0.8, 0.8, 1, RoundingMode.HALF_UP), 1));
//        gnssData.setDy(round(gnssData.getDy() + RandomUtil.randomDouble(-0.8, 0.8, 1, RoundingMode.HALF_UP), 1));
//        gnssData.setDz(round(gnssData.getDz() + RandomUtil.randomDouble(-1.5, 1.5, 1, RoundingMode.HALF_UP), 1));
//        gnssData.setDateTime(DateUtil.date());
//
//        final MqttClient mqttClient = mqttService.getMqttClient();
//        if (mqttClient != null) {
//            final MqttPubProtocol mqttPubProtocol = new MqttPubProtocol();
//            mqttPubProtocol.setCjy_no("23");
//            mqttPubProtocol.setBridge_code(MqttConstant.BRIDGE_CODE);
//            final String gnssPointCode = "SDXLQ-DIS-T07-001-01";
//            final List<ChannelData> channelDataList = new ArrayList<>();
//            ChannelData channelData = new ChannelData();
//            channelData.setMonitoring_point_code(gnssPointCode + "-X");
//            channelData.setChannel_no(0);
//            channelData.setSample_time(gnssData.getDateTime().getTime());
//            channelData.setValue(gnssData.getDx());
//            channelData.setCode_no("0");
//            channelDataList.add(channelData);
//
//            channelData = new ChannelData();
//            channelData.setMonitoring_point_code(gnssPointCode + "-Y");
//            channelData.setChannel_no(0);
//            channelData.setSample_time(gnssData.getDateTime().getTime());
//            channelData.setValue(gnssData.getDy());
//            channelData.setCode_no("0");
//            channelDataList.add(channelData);
//
//            channelData = new ChannelData();
//            channelData.setMonitoring_point_code(gnssPointCode + "-Z");
//            channelData.setChannel_no(0);
//            channelData.setSample_time(gnssData.getDateTime().getTime());
//            channelData.setValue(gnssData.getDz());
//            channelData.setCode_no("0");
//            channelDataList.add(channelData);
//
//            mqttPubProtocol.setChannel_data(channelDataList);
//            mqttClient.publish(MqttConstant.TOPIC, Buffer.buffer(JSONUtil.toJsonStr(mqttPubProtocol)), MqttQoS.AT_MOST_ONCE, false, false);
//        }
////        gnssDataMapper.insert(gnssData);
//    }
//
//    /**
//     * 删除过期数据
//     */
//    @Scheduled(timeUnit = TimeUnit.MINUTES, fixedRate = 60)
//    public void removeExpiredData() {
//        final Date expiredTime = DateUtil.offsetMinute(new Date(), -60);
//        final int i = sensorDataMapper.delete(new LambdaQueryWrapper<>(SensorData.class).lt(SensorData::getDataTime, expiredTime));
//
//        log.info("删除过期数据：{}", i);
//    }
//
//
//    @SneakyThrows
//    @Scheduled(timeUnit = TimeUnit.HOURS, fixedRate = 3)
//    public void getToken() {
//        if (!systemConfig.isHttpDataEnable()) {
//            return;
//        }
//        RequestUtil.getToken();
//    }
//
//}
