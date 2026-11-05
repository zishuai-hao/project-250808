package com.zd.sdq;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.company.cbf.starter.data.entity.MqttData;
import com.company.cbf.starter.data.service.forward.MqttDataBuilder;
import com.company.cbf.starter.data.service.forward.device.DeviceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zd.sdq.config.DeviceReportConfig;
import com.zd.sdq.entity.DeviceInfoExt;
import com.zd.sdq.entity.dto.incline.InclineHistoryResponse;
import com.zd.sdq.mapper.InclineForwardDataMapper;
import com.zd.sdq.mapper.WaterLevelDataMapper;
import com.zd.sdq.service.DeviceReportMqttSender;
import com.zd.sdq.service.DeviceScheduledReportService;
import com.zd.sdq.service.WaterLevelDataService;
import com.zd.sdq.service.incline.InclineForwardDataService;
import com.zd.sdq.service.DeviceReportStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.SimpleTriggerContext;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

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
                new DeviceReportStateStore(source));
        when(sender.sendOnce(anyLong(), anyList())).thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void selectsLatestRealPerDeviceWithExclusiveStartAndInclusiveEnd() {
        for (String code : List.of("A", "BOUNDARY", "OLD", "AT-T", "FUTURE", "WRONG-SN")) device(code, "INC", 1, 1);
        device("DISABLED", "INC", 1, 0);
        device("V", "VIB", 2, 1);
        sample("A", "INC", "2026-11-01 09:01:00", "0.1");
        sample("A", "INC", "2026-11-01 09:59:00", "0.2");
        sample("A", "INC", "2026-11-01 10:00:01", "99");
        sample("BOUNDARY", "INC", "2026-11-01 09:00:00", "9");
        sample("OLD", "INC", "2026-11-01 08:00:00", "9");
        sample("AT-T", "INC", "2026-11-01 10:00:00", "0.3");
        sample("FUTURE", "INC", "2026-11-01 10:00:01", "9");
        sample("WRONG-SN", "INC", "2026-11-01 09:59:00", "9");
        sample("DISABLED", "INC", "2026-11-01 09:59:00", "9");
        sample("V", "VIB", "2026-11-01 09:59:00", "0.006");
        jdbc.update("UPDATE s_device_info_ext SET remote_device_id = 'OTHER' WHERE device_code = 'WRONG-SN'");
        var before = jdbc.queryForList("SELECT payload, data_time, sample_time FROM incline_forward_data ORDER BY id");

        reports.reportInclineVibAt(T);

        var values = ArgumentCaptor.forClass(List.class);
        verify(sender).sendOnce(eq(1L), values.capture());
        List<MqttData> data = values.getValue();
        assertEquals(List.of("A", "AT-T"), data.stream().map(MqttData::getDeviceId).toList());
        assertEquals("0.2", data.get(0).getValue().get(0).get(1));
        assertEquals("2026-11-01 10:00:00.000", data.get(0).getValue().get(0).get(0));
        verify(sender).sendOnce(eq(2L), anyList());
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM incline_forward_data WHERE report_status='SENT'", Integer.class));
        assertEquals(before, jdbc.queryForList("SELECT payload, data_time, sample_time FROM incline_forward_data ORDER BY id"));
    }

    @Test
    void sameRecordNeverAppearsInTheNextHourAndSentRecordsSurviveRestart() {
        device("A", "INC", 1, 1);
        sample("A", "INC", "2026-11-01 10:00:00", "0.1");
        reports.reportInclineVibAt(T);
        reports.reportInclineVibAt(T.plusSeconds(3600));
        inclineStore.initTable(); // 重启初始化不能重置发送状态
        reports.retryAt(T.plusSeconds(3600));
        verify(sender, times(1)).sendOnce(anyLong(), anyList());
        assertEquals("SENT", state("A").get("report_status"));
        assertEquals(1, state("A").get("report_attempts"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"INC", "VIB", "WLV"})
    void fiveSamplesSendOnlyNewestAndNeverFallBackToTheFourOlderRows(String type) {
        device("A", type, 1, 1);
        for (int minute : List.of(10, 20, 30, 40, 50)) reportSample(type, minute);
        reports.retryAt(T);
        // 更早的记录即使仍未发送或曾经失败，也不应被轮流补发。
        jdbc.update("UPDATE " + table(type) + " SET report_status='FAILED' WHERE data_time < '2026-11-01 09:50:00'");
        for (int minute : List.of(5, 10, 15, 60)) reports.retryAt(T.plusSeconds(minute * 60L));
        verify(sender, times(1)).sendOnce(eq(1L), argThat(data -> data.size() == 1
                && Double.parseDouble(data.get(0).getValue().get(0).get(1)) == 50));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM " + table(type) + " WHERE report_status='SENT'", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"INC", "VIB", "WLV"})
    void staleQueryCandidateCannotBeClaimedAfterANewerSampleArrivesOrSucceeds(String type) {
        device("A", type, 1, 1);
        long staleId = reportSample(type, 10); // 模拟任务A已经查出的候选。
        reportSample(type, 50); // A暂停期间，迟到的新记录入库。
        var store = new DeviceReportStateStore(jdbc.getDataSource());
        assertNull(store.claim(source(type), staleId, "2026-11-01 10:00:00", System.currentTimeMillis()));
        reports.retryAt(T); // 任务B先成功发送新记录。
        assertNull(store.claim(source(type), staleId, "2026-11-01 10:00:00", System.currentTimeMillis()));
        verify(sender, times(1)).sendOnce(anyLong(), anyList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"INC", "VIB", "WLV"})
    void sameHourNewerSampleWaitsUntilTheOlderInFlightAttemptFinishes(String type) {
        device("A", type, 1, 1);
        long oldId = reportSample(type, 10);
        var store = new DeviceReportStateStore(jdbc.getDataSource());
        String token = store.claim(source(type), oldId, "2026-11-01 10:00:00", System.currentTimeMillis());
        assertNotNull(token);
        reportSample(type, 50);
        reports.retryAt(T);
        verifyNoInteractions(sender); // 同测点同小时不能让两个不同源行并行发送。
        store.complete(source(type), oldId, token, null, System.currentTimeMillis());
        reports.retryAt(T.plusSeconds(300));
        verify(sender).sendOnce(eq(1L), argThat(data -> Double.parseDouble(data.get(0).getValue().get(0).get(1)) == 50));
    }

    @ParameterizedTest
    @ValueSource(strings = {"INC", "VIB", "WLV"})
    void duplicateTimestampWithANewIdDoesNotResendAnAlreadySuccessfulSample(String type) {
        device("A", type, 1, 1);
        reportSample(type, 50);
        reports.retryAt(T);
        if ("WLV".equals(type)) {
            reportSample(type, 50); // 水位允许同秒收到多条。
        } else {
            // 兼容外部补录或旧库产生的同时间重复行。
            jdbc.update("""
                    INSERT INTO incline_forward_data
                        (device_code, remote_device_id, device_type, data_time, sample_time, payload, raw_data, create_time)
                    SELECT device_code, remote_device_id, device_type, data_time, sample_time, payload, raw_data, create_time
                    FROM incline_forward_data
                    """);
        }
        // 判断成功记录应使用采集时间，不能依赖某条记录是否填写了report_time。
        jdbc.update("UPDATE " + table(type) + " SET report_time=NULL WHERE report_status='SENT'");
        reports.retryAt(T.plusSeconds(300));
        verify(sender, times(1)).sendOnce(anyLong(), anyList());
    }

    @Test
    void failedGatewayIsRetriedWithoutResendingSuccessfulGateway() {
        device("A", "INC", 1, 1);
        device("B", "VIB", 2, 1);
        sample("A", "INC", "2026-11-01 09:59:00", "0.1");
        sample("B", "VIB", "2026-11-01 09:59:00", "0.006");
        when(sender.sendOnce(eq(1L), anyList())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
        reports.reportInclineVibAt(T);
        assertEquals("FAILED", state("A").get("report_status"));
        assertTrue(state("A").get("report_error").toString().contains("offline"));
        assertNull(state("A").get("report_sent_at"));
        assertEquals("SENT", state("B").get("report_status"));
        when(sender.sendOnce(eq(1L), anyList())).thenReturn(CompletableFuture.completedFuture(null));
        reports.retryAt(T.plusSeconds(300));
        verify(sender, times(2)).sendOnce(eq(1L), argThat(data ->
                "2026-11-01 10:00:00.000".equals(data.get(0).getValue().get(0).get(0))));
        verify(sender, times(1)).sendOnce(eq(2L), anyList());
        assertEquals("SENT", state("A").get("report_status"));
        assertEquals(2, state("A").get("report_attempts"));
        assertNull(state("A").get("report_error"));
        assertNotNull(state("A").get("report_sent_at"));
    }

    @Test
    void lateDataBackfillsItsOriginalHourWithoutSendingAnOpenHourOrExpiredData() {
        for (String code : List.of("LATE", "OPEN", "EXPIRED", "RECENT")) device(code, "INC", 1, 1);
        reports.reportInclineVibAt(T);
        verifyNoInteractions(sender);
        sample("LATE", "INC", "2026-11-01 08:59:00", "0.1");
        sample("OPEN", "INC", "2026-11-01 10:01:00", "0.2");
        sample("EXPIRED", "INC", "2026-10-31 10:04:59", "9");
        sample("RECENT", "INC", "2026-10-31 10:05:01", "0.3");
        reports.retryAt(T.plusSeconds(300));
        verify(sender).sendOnce(eq(1L), argThat(data -> data.size() == 1 && data.get(0).getDeviceId().equals("LATE")
                && data.get(0).getValue().get(0).get(0).equals("2026-11-01 09:00:00.000")));
        verify(sender).sendOnce(eq(1L), argThat(data -> data.size() == 1 && data.get(0).getDeviceId().equals("RECENT")
                && data.get(0).getValue().get(0).get(0).equals("2026-10-31 11:00:00.000")));
        assertEquals("PENDING", state("OPEN").get("report_status"));
        assertEquals("PENDING", state("EXPIRED").get("report_status"));
        reports.retryAt(T.plusSeconds(600));
        verify(sender, times(2)).sendOnce(anyLong(), anyList());
    }

    @Test
    void olderLateSamplesDoNotFallBackButANewerLateSampleCanCorrectTheHour() {
        device("A", "INC", 1, 1);
        sample("A", "INC", "2026-11-01 09:50:00", "0.1");
        reports.reportInclineVibAt(T);
        sample("A", "INC", "2026-11-01 09:40:00", "0.2");
        reports.retryAt(T.plusSeconds(300));
        verify(sender, times(1)).sendOnce(anyLong(), anyList());
        sample("A", "INC", "2026-11-01 09:59:00", "0.3");
        reports.retryAt(T.plusSeconds(600));
        verify(sender).sendOnce(eq(1L), argThat(data -> "0.3".equals(data.get(0).getValue().get(0).get(1))));
        // 回查重复数据也不能重置快照或发送标记。
        sample("A", "INC", "2026-11-01 09:59:00", "999");
        reports.retryAt(T.plusSeconds(900));
        verify(sender, times(2)).sendOnce(anyLong(), anyList());
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM incline_forward_data", Integer.class));
    }

    @Test
    void corruptPayloadIsMarkedFailedWithoutBlockingHealthySamples() {
        device("BAD", "INC", 1, 1);
        device("OK", "VIB", 1, 1);
        sample("BAD", "INC", "2026-11-01 09:59:00", "0.1");
        sample("OK", "VIB", "2026-11-01 09:59:00", "0.006");
        jdbc.update("UPDATE incline_forward_data SET payload='broken' WHERE device_code='BAD'");
        reports.reportInclineVibAt(T);
        assertEquals("FAILED", state("BAD").get("report_status"));
        assertEquals("SENT", state("OK").get("report_status"));
        verify(sender).sendOnce(eq(1L), argThat(data -> data.size() == 1 && data.get(0).getDeviceId().equals("OK")));
    }

    @Test
    void claimPreventsConcurrentSendAndRecoversAbandonedAttemptWithoutAcceptingStaleCompletion() {
        device("A", "INC", 1, 1);
        sample("A", "INC", "2026-11-01 09:59:00", "0.1");
        var store = new DeviceReportStateStore(jdbc.getDataSource());
        long id = ((Number) state("A").get("id")).longValue();
        var source = DeviceReportStateStore.Source.INCLINE;
        String first = store.claim(source, id, "2026-11-01 10:00:00", 1000);
        assertNotNull(first);
        assertNull(store.claim(source, id, "2026-11-01 10:00:00", 1001));
        String second = store.claim(source, id, "2026-11-01 10:00:00", 121000);
        assertNotNull(second);
        store.complete(source, id, first, null, 121001);
        assertEquals("SENDING", state("A").get("report_status"));
        store.complete(source, id, second, null, 121002);
        assertEquals("SENT", state("A").get("report_status"));
        assertNull(store.claim(source, id, "2026-11-01 10:00:00", 999999));
    }

    @Test
    void waterAlsoUsesOneHourAndRetriesWithTheOriginalStoredValues() {
        device("W", "WLV", 1, 1);
        device("OLD", "WLV", 1, 1);
        waterStore.saveCollectedData("W", 6.75, 3.25, T.minusSeconds(60).atZone(ZONE));
        waterStore.saveCollectedData("OLD", 1, 9, T.minusSeconds(3600).atZone(ZONE));
        when(sender.sendOnce(anyLong(), anyList())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
        reports.reportWaterLevelAt(T);
        assertEquals("FAILED", jdbc.queryForObject("SELECT report_status FROM water_level_data WHERE device_code='W'", String.class));
        when(sender.sendOnce(anyLong(), anyList())).thenReturn(CompletableFuture.completedFuture(null));
        reports.retryAt(T.plusSeconds(300));
        verify(sender, times(2)).sendOnce(eq(1L), argThat(data -> data.size() == 1 && data.get(0).getValue().get(0)
                .equals(List.of("2026-11-01 10:00:00.000", "6.7500", "3.2500"))));
        assertEquals("SENT", jdbc.queryForObject("SELECT report_status FROM water_level_data WHERE device_code='W'", String.class));
    }

    @Test
    void scheduledExecutionUsesPlannedHourAndConfigurationCanDisableGroupAndRetry() {
        device("A", "INC", 1, 1);
        sample("A", "INC", "2026-11-01 09:59:00", "0.1");
        var registrar = new ScheduledTaskRegistrar();
        reports.configureTasks(registrar);
        assertEquals(3, registrar.getTriggerTaskList().size());
        var task = registrar.getTriggerTaskList().get(0);
        var context = new SimpleTriggerContext(Clock.fixed(T.minusSeconds(1), ZONE));
        assertEquals(T, task.getTrigger().nextExecution(context));
        context.update(T, T.plusSeconds(3), T.plusSeconds(4));
        task.getRunnable().run();
        verify(sender).sendOnce(eq(1L), argThat(data -> data.get(0).getValue().get(0).get(0).equals("2026-11-01 10:00:00.000")));
        config.getInclineVib().setCron("-");
        config.setRetryCron("-");
        assertDoesNotThrow(config::validate);
        registrar = new ScheduledTaskRegistrar();
        reports.configureTasks(registrar);
        assertEquals(1, registrar.getTriggerTaskList().size());
        sample("A", "INC", "2026-11-01 10:59:00", "0.2");
        reports.reportInclineVibAt(T.plusSeconds(3600));
        reports.retryAt(T.plusSeconds(3600));
        verify(sender, times(1)).sendOnce(anyLong(), anyList());
        config.getWaterLevel().setCron("0 */30 * * * *");
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    private java.util.Map<String, Object> state(String code) {
        return jdbc.queryForMap("SELECT * FROM incline_forward_data WHERE device_code=? ORDER BY data_time DESC LIMIT 1", code);
    }

    private String table(String type) {
        return "WLV".equals(type) ? "water_level_data" : "incline_forward_data";
    }

    private DeviceReportStateStore.Source source(String type) {
        return "WLV".equals(type) ? DeviceReportStateStore.Source.WATER : DeviceReportStateStore.Source.INCLINE;
    }

    private long reportSample(String type, int minute) {
        if ("WLV".equals(type)) {
            waterStore.saveCollectedData("A", minute, 1, T.minusSeconds((60L - minute) * 60).atZone(ZONE));
        } else {
            sample("A", type, String.format("2026-11-01 09:%02d:00", minute), Integer.toString(minute));
        }
        return jdbc.queryForObject("SELECT MAX(id) FROM " + table(type), Long.class);
    }

    private void device(String code, String type, long gateway, int enabled) {
        jdbc.update("INSERT INTO s_device_info_ext VALUES (?, ?, 'SN', ?, ?)", code, type, gateway, enabled);
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
