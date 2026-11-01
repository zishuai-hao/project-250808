package com.zd.sdq;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.company.cbf.starter.data.entity.MqttData;
import com.company.cbf.starter.data.service.forward.MqttDataBuilder;
import com.company.cbf.starter.data.service.forward.device.DeviceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zd.sdq.config.DeviceReportConfig;
import com.zd.sdq.config.InclineFluctuationConfig;
import com.zd.sdq.service.incline.InclineFluctuationService;
import com.zd.sdq.entity.DeviceInfoExt;
import com.zd.sdq.entity.dto.incline.InclineHistoryResponse;
import com.zd.sdq.mapper.InclineForwardDataMapper;
import com.zd.sdq.mapper.WaterLevelDataMapper;
import com.zd.sdq.service.DeviceLatestDataCache;
import com.zd.sdq.service.DeviceReportMqttSender;
import com.zd.sdq.service.DeviceScheduledReportService;
import com.zd.sdq.service.SingleDeviceRadarWaterLevelServer;
import com.zd.sdq.service.WaterLevelDataService;
import com.zd.sdq.service.incline.InclineForwardDataService;
import com.zd.sdq.service.incline.HistoricalFluctuationSimulator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.SimpleTriggerContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeviceScheduledReportTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant T = ZonedDateTime.parse("2026-11-01T10:00:00+08:00").toInstant();
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();
    private final DeviceReportConfig config = new DeviceReportConfig();
    private final DeviceReportMqttSender sender = mock(DeviceReportMqttSender.class);
    private JdbcTemplate jdbc;
    private InclineForwardDataService inclineStore;
    private WaterLevelDataService waterStore;
    private DeviceScheduledReportService reports;

    @BeforeEach
    void database() throws Exception {
        var source = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("report.db"));
        jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE s_device_info_ext (device_code TEXT PRIMARY KEY, device_type TEXT,
                    remote_device_id TEXT, mqtt_gateway_id INTEGER, enable INTEGER)
                """);
        var mybatis = new MybatisConfiguration();
        mybatis.setMapUnderscoreToCamelCase(true);
        mybatis.addMapper(InclineForwardDataMapper.class);
        mybatis.addMapper(WaterLevelDataMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(mybatis);
        var session = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        var inclineMapper = session.getMapper(InclineForwardDataMapper.class);
        var waterMapper = session.getMapper(WaterLevelDataMapper.class);
        inclineStore = new InclineForwardDataService(inclineMapper, json, source);
        inclineStore.initTable();
        waterStore = new WaterLevelDataService(waterMapper, json, source);
        waterStore.initTable();
        reports = new DeviceScheduledReportService(config, inclineMapper, waterMapper, json, sender,
                new HistoricalFluctuationSimulator(inclineMapper, json,
                        new InclineFluctuationService(new InclineFluctuationConfig(), null, new com.fasterxml.jackson.databind.ObjectMapper())));
    }

    @Test
    @SuppressWarnings("unchecked")
    void alignsBothChannelsAcrossDevicesAndGatewaysWithoutChangingSourceSnapshots() throws Exception {
        device("A-INC", "INC", 1, 1);
        device("A-VIB", "VIB", 1, 1);
        device("B-INC", "INC", 2, 1);
        device("D-INC", "INC", 1, 0);
        device("EXPIRED", "INC", 1, 1);
        device("EMPTY", "INC", 1, 1);
        device("AT-T", "VIB", 1, 1);
        device("CHANGED-SN", "INC", 1, 1);
        sample("A-INC", "INC", "2026-11-01 09:50:00", "0.1");
        sample("A-INC", "INC", "2026-11-01 09:58:00", "0.2");
        // 相同采集时间保留最后一次入库值；未来数据不挤掉T之前的最新值。
        sample("A-INC", "INC", "2026-11-01 09:58:00", "0.3");
        sample("A-INC", "INC", "2026-11-01 10:00:01", "99");
        sample("A-VIB", "VIB", "2026-11-01 09:59:00", "0.006");
        sample("B-INC", "INC", "2026-11-01 09:00:00", "0.4"); // 恰好1小时，仍为实测
        sample("EXPIRED", "INC", "2026-11-01 05:59:59", "55");
        sample("D-INC", "INC", "2026-11-01 09:59:00", "66");
        sample("AT-T", "VIB", "2026-11-01 10:00:00", "0.007");
        sample("CHANGED-SN", "INC", "2026-11-01 09:59:00", "77");
        jdbc.update("UPDATE s_device_info_ext SET remote_device_id = 'replacement' WHERE device_code = 'CHANGED-SN'");
        var before = jdbc.queryForList("SELECT * FROM incline_forward_data ORDER BY id");
        verifyNoInteractions(sender); // 落库本身不发送

        reports.reportInclineVibAt(T);

        var gatewayOne = ArgumentCaptor.forClass(List.class);
        var gatewayTwo = ArgumentCaptor.forClass(List.class);
        verify(sender).sendOnce(eq(1L), gatewayOne.capture());
        verify(sender).sendOnce(eq(2L), gatewayTwo.capture());
        List<MqttData> one = gatewayOne.getValue();
        List<MqttData> two = gatewayTwo.getValue();
        assertEquals(List.of("A-INC", "A-VIB", "AT-T"), one.stream().map(MqttData::getDeviceId).toList());
        assertEquals(List.of("B-INC"), two.stream().map(MqttData::getDeviceId).toList());
        assertEquals("0.3", one.get(0).getValue().get(0).get(1));
        for (MqttData data : java.util.stream.Stream.concat(one.stream(), two.stream()).toList()) {
            assertEquals("2026-11-01 10:00:00.000", data.getValue().get(0).get(0));
            assertEquals(MqttData.class, data.getClass());
        }
        assertEquals(before, jdbc.queryForList("SELECT * FROM incline_forward_data ORDER BY id"));
        verifyNoMoreInteractions(sender);
    }

    @Test
    @SuppressWarnings("unchecked")
    void waterStoresOriginalAndDifferenceAtCollectionAndUsesItsOwnWindow() {
        device("W-1", "WLV", 1, 1);
        device("W-2", "WLV", 1, 1);
        device("W-OLD", "WLV", 1, 1);
        device("W-LEGACY", "WLV", 1, 1);
        DeviceInfoExt device = new DeviceInfoExt();
        device.setDeviceCode("W-1");
        device.setPort("41000");
        device.setFrequency("60");
        device.setBaseline(10.0);
        DeviceLatestDataCache cache = new DeviceLatestDataCache();
        var server = new SingleDeviceRadarWaterLevelServer(device, null, null, waterStore, cache);
        ReflectionTestUtils.invokeMethod(server, "saveWaterLevelData", 3.25, T.minusSeconds(75 * 60).atZone(ZONE));
        device.setBaseline(20.0); // 修改基准不重算已采集快照
        waterStore.saveCollectedData("W-2", 1.2, 8.8, T.atZone(ZONE));
        waterStore.saveCollectedData("W-OLD", 1, 9, T.minusSeconds(75 * 60 + 1).atZone(ZONE));
        jdbc.update("INSERT INTO water_level_data (device_code, water_level, data_time) VALUES ('W-LEGACY', 1, '2026-11-01 09:59:00')");
        verifyNoInteractions(sender);
        assertEquals("2026-11-01 08:45:00", cache.getLatestData("W-1").getDataTime());

        reports.reportWaterLevelAt(T);

        var values = ArgumentCaptor.forClass(List.class);
        verify(sender).sendOnce(eq(1L), values.capture());
        List<MqttData> data = values.getValue();
        assertEquals(List.of("W-1", "W-2"), data.stream().map(MqttData::getDeviceId).toList());
        assertEquals(List.of("2026-11-01 10:00:00.000", "6.7500", "3.2500"), data.get(0).getValue().get(0));
        assertEquals("2026-11-01 10:00:00.000", data.get(1).getValue().get(0).get(0));
    }

    @Test
    void failureDoesNotRetryOrStopOtherGatewaysAndNextTickUsesFreshQuery() {
        device("A", "INC", 1, 1);
        device("B", "VIB", 2, 1);
        sample("A", "INC", "2026-11-01 09:59:00", "0.1");
        sample("B", "VIB", "2026-11-01 09:59:00", "0.006");
        doThrow(new IllegalStateException("offline")).when(sender).sendOnce(eq(1L), anyList());
        reports.reportInclineVibAt(T);
        verify(sender, times(1)).sendOnce(eq(1L), anyList());
        verify(sender, times(1)).sendOnce(eq(2L), anyList());
        verifyNoMoreInteractions(sender);

        // 无发送队列；清空源数据后下一刻度没有可以补发的内容。
        jdbc.update("DELETE FROM incline_forward_data");
        reports.reportInclineVibAt(T.plusSeconds(3600));
        verifyNoMoreInteractions(sender);
    }

    @Test
    @SuppressWarnings("unchecked")
    void disablingSimulationUsesOnlyTheRealWindowAndSkipsCorruptPayloads() {
        config.getInclineVib().getSimulation().setEnabled(false);
        device("A", "INC", 1, 1);
        device("BAD", "VIB", 1, 1);
        sample("A", "INC", "2026-11-01 10:00:00", "0.1");
        sample("BAD", "VIB", "2026-11-01 09:59:00", "0.006");
        jdbc.update("UPDATE incline_forward_data SET payload = 'invalid' WHERE device_code = 'BAD'");
        reports.reportInclineVibAt(T);
        reports.reportInclineVibAt(T.plusSeconds(3600));
        var values = ArgumentCaptor.forClass(List.class);
        verify(sender, times(2)).sendOnce(eq(1L), values.capture());
        List<MqttData> first = values.getAllValues().get(0);
        List<MqttData> second = values.getAllValues().get(1);
        assertEquals(1, first.size());
        assertEquals(MqttData.class, first.get(0).getClass());
        assertEquals(MqttData.class, second.get(0).getClass());
        assertEquals("2026-11-01 10:00:00.000", first.get(0).getValue().get(0).get(0));
        assertEquals("2026-11-01 11:00:00.000", second.get(0).getValue().get(0).get(0));
        reports.reportInclineVibAt(T.plusSeconds(3601));
        verifyNoMoreInteractions(sender);
    }

    @Test
    @SuppressWarnings("unchecked")
    void usesRealThroughOneHourLearnsBothChannelsThroughFourHoursAndStopsBeyondThat() {
        device("REAL-INC", "INC", 1, 1);
        device("HOLD-INC", "INC", 1, 1);
        device("HOLD-VIB", "VIB", 1, 1);
        device("EXPIRED", "VIB", 1, 1);
        sample("REAL-INC", "INC", "2026-11-01 09:00:00", "0.123457");
        learnedHistory("HOLD-INC", "INC", "2026-11-01 08:59:59", -0.345678);
        learnedHistory("HOLD-VIB", "VIB", "2026-11-01 06:00:00", 0.0060);
        sample("EXPIRED", "VIB", "2026-11-01 05:59:59", "9.9");
        // 旧sample_time/入库时间可能是当前时间，不能用它们延长源数据有效期。
        jdbc.update("UPDATE incline_forward_data SET sample_time = ?, create_time = '2026-11-01 10:00:00'",
                T.toEpochMilli());
        var before = jdbc.queryForList("SELECT * FROM incline_forward_data ORDER BY id");

        reports.reportInclineVibAt(T);

        var values = ArgumentCaptor.forClass(List.class);
        verify(sender).sendOnce(eq(1L), values.capture());
        List<MqttData> data = values.getValue();
        assertEquals(List.of("HOLD-INC", "HOLD-VIB", "REAL-INC"), data.stream().map(MqttData::getDeviceId).toList());
        var inc = data.get(0);
        assertEquals(MqttData.class, inc.getClass());
        var vib = data.get(1);
        assertEquals(MqttData.class, vib.getClass());
        for (MqttData item : data) {
            var wire = json.valueToTree(item);
            assertFalse(wire.has("simulated"));
            assertFalse(wire.has("source_time"));
            assertFalse(wire.has("simulation_method"));
        }
        assertEquals("2026-11-01 10:00:00.000", inc.getValue().get(0).get(0));
        assertEquals("2026-11-01 10:00:00.000", vib.getValue().get(0).get(0));
        assertEquals(4, inc.getValue().get(0).size());
        assertEquals(2, vib.getValue().get(0).size());
        assertTrue(Double.parseDouble(inc.getValue().get(0).get(1)) >= -0.346078);
        assertTrue(Double.parseDouble(inc.getValue().get(0).get(1)) <= -0.345278);
        assertEquals(MqttData.class, data.get(2).getClass());
        assertEquals(before, jdbc.queryForList("SELECT * FROM incline_forward_data ORDER BY id"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void simulationNeverFeedsItselfAndNewRealDataAutomaticallyRestoresRealReporting() {
        device("A", "INC", 1, 1);
        learnedHistory("A", "INC", "2026-11-01 10:00:00", 0.123456);
        for (int hour = 0; hour <= 5; hour++) {
            reports.reportInclineVibAt(T.plusSeconds(hour * 3600L));
        }
        var values = ArgumentCaptor.forClass(List.class);
        verify(sender, times(5)).sendOnce(eq(1L), values.capture());
        for (int hour = 0; hour <= 4; hour++) {
            List<MqttData> data = values.getAllValues().get(hour);
            assertEquals(MqttData.class, data.get(0).getClass());
            if (hour <= 1) assertEquals("0.123456", data.get(0).getValue().get(0).get(1));
        }
        assertEquals(6, jdbc.queryForObject("SELECT COUNT(*) FROM incline_forward_data", Integer.class));
        verifyNoMoreInteractions(sender); // 第5小时不发送，模拟不能续期

        sample("A", "INC", "2026-11-01 14:59:00", "0.234567");
        clearInvocations(sender);
        reports.reportInclineVibAt(T.plusSeconds(5 * 3600));
        verify(sender).sendOnce(eq(1L), argThat(data -> data.size() == 1
                && data.get(0).getClass() == MqttData.class
                && "0.234567".equals(data.get(0).getValue().get(0).get(1))));
    }

    @Test
    void waterNeverUsesTheInclineSimulationWindow() {
        device("W", "WLV", 1, 1);
        waterStore.saveCollectedData("W", 1.2, 8.8, T.minusSeconds(2 * 3600).atZone(ZONE));
        reports.reportWaterLevelAt(T);
        verifyNoInteractions(sender);
    }

    @Test
    void insufficientHistorySkipsInsteadOfHoldingAndLearningDoesNotSeeFutureOrAnotherSensor() {
        device("A", "INC", 1, 1);
        sample("A", "INC", "2026-11-01 08:00:00", "0.123456");
        reports.reportInclineVibAt(T);
        verifyNoInteractions(sender);

        learnedHistory("A", "INC", "2026-11-01 08:00:00", 0.123456);
        var mapper = (InclineForwardDataMapper) ReflectionTestUtils.getField(inclineStore, "mapper");
        var anchor = mapper.selectLatestForReport("2026-11-01 06:00:00", "2026-11-01 08:00:00").get(0);
        var learner = new HistoricalFluctuationSimulator(mapper, json,
                    new InclineFluctuationService(new InclineFluctuationConfig(), null, new com.fasterxml.jackson.databind.ObjectMapper()));
        var before = learner.simulate(anchor, T, config.getInclineVib().getSimulation()).orElseThrow();
        assertEquals(6, before.trainingCount()); // 同一时间重复入库不会变成额外样本
        sample("A", "INC", "2026-11-01 09:00:00", "999"); // 锚点之后不能用于学习
        jdbc.update("""
                INSERT INTO incline_forward_data
                    (device_code, remote_device_id, device_type, data_time, sample_time, payload, raw_data, create_time)
                SELECT device_code, 'OTHER-SN', device_type, data_time, sample_time, 'invalid', raw_data, create_time
                FROM incline_forward_data WHERE device_code = 'A' AND data_time = '2026-11-01 08:00:00' LIMIT 1
                """);
        assertEquals(before, learner.simulate(anchor, T, config.getInclineVib().getSimulation()).orElseThrow());
        assertEquals(6, mapper.selectSimulationHistory("A", "SN", "INC", "2026-10-30 08:00:00",
                "2026-11-01 08:00:00", 24).size());
    }

    @Test
    void delayedExecutionKeepsScheduledTimeAndSkipsMissedTicks() {
        device("A", "INC", 1, 1);
        sample("A", "INC", "2026-11-01 09:59:00", "0.1");
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        reports.configureTasks(registrar);
        assertEquals(2, registrar.getTriggerTaskList().size());
        var incline = registrar.getTriggerTaskList().get(0);
        var water = registrar.getTriggerTaskList().get(1);
        var context = new SimpleTriggerContext(Clock.fixed(T.minusSeconds(1), ZONE));
        assertEquals(T, incline.getTrigger().nextExecution(context));
        assertEquals(T, water.getTrigger().nextExecution(context));
        context.update(T, T.plusSeconds(3), T.plusSeconds(4));
        incline.getRunnable().run();
        verify(sender).sendOnce(eq(1L), argThat(data ->
                "2026-11-01 10:00:00.000".equals(data.get(0).getValue().get(0).get(0))));
        assertEquals(T.plusSeconds(3600), incline.getTrigger().nextExecution(context));
        assertEquals(T.plusSeconds(3600), water.getTrigger().nextExecution(context));
        context.update(T, T.plusSeconds(3), T.plusSeconds(95 * 60));
        assertEquals(T.plusSeconds(120 * 60), incline.getTrigger().nextExecution(context));
        assertEquals(T.plusSeconds(120 * 60), water.getTrigger().nextExecution(context));
    }

    @Test
    void configurationBindsAndCanDisableOneGroup() {
        var properties = new org.springframework.mock.env.MockEnvironment()
                .withProperty("device-report.incline-vib.cron", "-")
                .withProperty("device-report.incline-vib.max-age", "90m")
                .withProperty("device-report.incline-vib.simulation.enabled", "false")
                .withProperty("device-report.incline-vib.simulation.max-source-age", "6h")
                .withProperty("device-report.incline-vib.simulation.history-window", "72h")
                .withProperty("device-report.incline-vib.simulation.history-size", "48")
                .withProperty("device-report.incline-vib.simulation.min-history-size", "8")
                .withProperty("device-report.incline-vib.simulation.max-history-gap", "2h")
                .withProperty("device-report.incline-vib.simulation.volatility-scale", "0.5")
                .withProperty("device-report.incline-vib.simulation.seed", "1234")
                .withProperty("device-report.water-level.max-age", "20m");
        var bound = org.springframework.boot.context.properties.bind.Binder.get(properties)
                .bind("device-report", org.springframework.boot.context.properties.bind.Bindable.of(DeviceReportConfig.class))
                .orElseThrow(IllegalStateException::new);
        assertDoesNotThrow(bound::validate);
        assertEquals(Duration.ofMinutes(20), bound.getWaterLevel().getMaxAge());
        assertEquals(Duration.ofMinutes(90), bound.getInclineVib().getMaxAge());
        assertFalse(bound.getInclineVib().getSimulation().isEnabled());
        assertEquals(Duration.ofHours(6), bound.getInclineVib().getSimulation().getMaxSourceAge());
        assertEquals(Duration.ofHours(72), bound.getInclineVib().getSimulation().getHistoryWindow());
        assertEquals(48, bound.getInclineVib().getSimulation().getHistorySize());
        assertEquals(8, bound.getInclineVib().getSimulation().getMinHistorySize());
        assertEquals(Duration.ofHours(2), bound.getInclineVib().getSimulation().getMaxHistoryGap());
        assertEquals(0.5, bound.getInclineVib().getSimulation().getVolatilityScale());
        assertEquals(1234L, bound.getInclineVib().getSimulation().getSeed());
        var disabled = new DeviceScheduledReportService(bound, null, null, json, sender, null);
        var registrar = new ScheduledTaskRegistrar();
        disabled.configureTasks(registrar);
        assertEquals(1, registrar.getTriggerTaskList().size());
        bound.getWaterLevel().setMaxAge(Duration.ZERO);
        assertThrows(IllegalArgumentException.class, bound::validate);
    }

    @Test
    void rejectsInvalidSimulationWindowsAndAcceptsDefaults() {
        assertDoesNotThrow(config::validate);
        for (Duration invalid : List.of(Duration.ZERO, Duration.ofHours(-1), Duration.ofMinutes(30),
                Duration.ofHours(1), Duration.ofMillis(3600001))) {
            config.getInclineVib().getSimulation().setMaxSourceAge(invalid);
            assertThrows(IllegalArgumentException.class, config::validate);
        }
        config.getInclineVib().getSimulation().setMaxSourceAge(Duration.ofHours(4));
        assertDoesNotThrow(config::validate);
        config.getInclineVib().getSimulation().setMinHistorySize(5);
        assertThrows(IllegalArgumentException.class, config::validate);
        config.getInclineVib().getSimulation().setMinHistorySize(6);
        for (double invalid : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY, 1.1}) {
            config.getInclineVib().getSimulation().setVolatilityScale(invalid);
            assertThrows(IllegalArgumentException.class, config::validate);
        }
    }

    private void device(String code, String type, long gateway, int enabled) {
        jdbc.update("INSERT INTO s_device_info_ext VALUES (?, ?, 'SN', ?, ?)", code, type, gateway, enabled);
    }

    private void learnedHistory(String code, String type, String sourceTime, double center) {
        var end = java.time.LocalDateTime.parse(sourceTime,
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(ZONE);
        double[] deviations = {-0.0004, 0.0002, -0.0002, 0.0004, -0.0002, 0};
        for (int i = 0; i < deviations.length; i++) {
            String time = end.minusHours(5 - i).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            sample(code, type, time, java.math.BigDecimal.valueOf(center + deviations[i])
                    .setScale(6, java.math.RoundingMode.HALF_UP).toPlainString());
        }
    }

    private void sample(String code, String type, String time, String value) {
        DeviceInfoExt device = new DeviceInfoExt();
        device.setDeviceCode(code);
        device.setDeviceType(DeviceType.valueOf(type));
        var record = new InclineHistoryResponse.InclineRecord();
        record.setTm(time);
        record.setRunStatus(0);
        record.setAlarmStatus(0);
        record.setAngleX(Double.parseDouble(value));
        record.setAngleY(0.2);
        record.setTemp(20.0);
        ZonedDateTime sourceTime = java.time.LocalDateTime.parse(time,
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(ZONE);
        var builder = new MqttDataBuilder(device.getDeviceType()).monitoringCode(code)
                .sampleTime(sourceTime.toInstant().toEpochMilli());
        if ("INC".equals(type)) builder.addData(sourceTime, value, "0.2", "20.000");
        else builder.addData(sourceTime, value);
        inclineStore.saveCollectedData(device, "SN", record, builder.build());
    }
}
