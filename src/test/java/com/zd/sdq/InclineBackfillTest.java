package com.zd.sdq;

import cn.hutool.core.date.DateUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.company.cbf.starter.data.service.forward.device.DeviceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zd.sdq.config.InclineFluctuationConfig;
import com.zd.sdq.config.VibrationFilterConfig;
import com.zd.sdq.entity.DeviceInfoExt;
import com.zd.sdq.entity.dto.incline.InclineHistoryRequest;
import com.zd.sdq.entity.dto.incline.InclineHistoryResponse;
import com.zd.sdq.entity.dto.incline.InclineHistoryResponse.InclineRecord;
import com.zd.sdq.mapper.InclineForwardDataMapper;
import com.zd.sdq.service.DeviceLatestDataCache;
import com.zd.sdq.service.incline.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InclineBackfillTest {
    @TempDir Path directory;
    private JdbcTemplate jdbc;
    private InclineDataHandler handler;
    private InclineForwardDataService store;
    private final InclineApiService api = mock(InclineApiService.class);
    private final DeviceLatestDataCache cache = new DeviceLatestDataCache();
    private final DeviceInfoExt inc = device("TEST-INC", DeviceType.INC);
    private final DeviceInfoExt vib = device("TEST-VIB", DeviceType.VIB);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void database() throws Exception {
        var db = InclineBaselineTest.database(directory.resolve("backfill.db"));
        jdbc = db.jdbc();
        db.service().save("TEST-INC", 1.0, 0.0);
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(InclineForwardDataMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setConfiguration(config);
        factory.setDataSource(jdbc.getDataSource());
        var mapper = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject())).getMapper(InclineForwardDataMapper.class);
        var json = new ObjectMapper();
        store = new InclineForwardDataService(mapper, json, jdbc.getDataSource());
        store.initTable();
        var filterConfig = new VibrationFilterConfig();
        filterConfig.getDeviceCodes().add("TEST-VIB");
        var fluctuation = new InclineFluctuationConfig();
        fluctuation.setEnabled(true);
        handler = new InclineDataHandler(null, null, api, cache,
                new VibrationSpikeFilter(filterConfig, Clock.fixed(Instant.parse("2026-11-01T04:00:00Z"), ZoneId.of("Asia/Shanghai"))),
                store, db.service(), new InclineFluctuationService(fluctuation, mapper, json));
        ((Map<String, Date>) ReflectionTestUtils.getField(handler, "remoteDeviceIdLastDataTimeMap"))
                .put("SN", DateUtil.parse("2026-11-01 00:00:00"));
        when(api.getLoginToken()).thenReturn("test-token");
    }

    @Test
    void fillsOlderMissingChannelsWithoutChangingSentDataOrLatestCacheAndIsIdempotent() {
        var first = record("2026-11-01 09:00:00", 1.1, 10);
        var last = record("2026-11-01 11:00:00", 1.3, 10);
        ReflectionTestUtils.invokeMethod(handler, "processResponse", List.of(inc, vib), response(List.of(first, last)), "SN");
        jdbc.update("UPDATE incline_forward_data SET report_status='SENT', report_attempts=1");
        jdbc.update("UPDATE incline_forward_data SET payload='[[0,999,0,20]]' WHERE device_type='INC' AND data_time='2026-11-01 11:00:00'");
        var before = jdbc.queryForList("SELECT * FROM incline_forward_data ORDER BY id");
        var receivedAt = cache.getLatestData("TEST-INC").getReceivedAtMillis();
        var late = record("2026-11-01 10:00:00", 1.2, 10);
        var full = response(List.of(last, late, first));
        handler.processBackfillResponse(List.of(inc, vib), full, "SN");
        handler.processBackfillResponse(List.of(inc, vib), full, "SN");
        assertEquals(6, jdbc.queryForObject("SELECT COUNT(*) FROM incline_forward_data", Integer.class));
        assertEquals(before, jdbc.queryForList("SELECT * FROM incline_forward_data WHERE report_status='SENT' ORDER BY id"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM incline_forward_data WHERE report_status='PENDING'", Integer.class));
        assertEquals("2026-11-01 11:00:00", cache.getLatestData("TEST-INC").getDataTime());
        assertEquals(receivedAt, cache.getLatestData("TEST-INC").getReceivedAtMillis());
        assertEquals("2026-11-01 11:00:00", cache.getLatestData("TEST-VIB").getDataTime());
        // 增量来自采集时间之前的记录，不能使用11点的数据作为10点的前值。
        String payload = jdbc.queryForObject("SELECT payload FROM incline_forward_data WHERE device_type='INC' AND data_time='2026-11-01 10:00:00'", String.class);
        assertTrue(payload.contains("0.300000"));
    }

    @Test
    void replayKeepsFilteredSpikeExcludedWhenItBecomesFirstPointOfTheRollingWindow() {
        var first = record("2026-11-01 09:00:00", 1.1, 10);
        var spike = record("2026-11-01 10:00:00", 1.2, 1000);
        var next = record("2026-11-01 11:00:00", 1.3, 10);
        handler.processBackfillResponse(List.of(vib), response(List.of(first, spike, next)), "SN");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM incline_forward_data", Integer.class));
        assertTrue(store.hasCollectedData(vib, "SN", spike.getTm()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM incline_vibration_filtered", Integer.class));
        handler.processBackfillResponse(List.of(vib), response(List.of(spike, next)), "SN");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM incline_forward_data", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM incline_forward_data WHERE data_time=?", Integer.class, spike.getTm()));
    }

    @Test
    void fetchesAllPagesWithinTheSameRangeAndRejectsOutOfRangeRecords() throws Exception {
        var firstPage = response(IntStream.range(0, 100).mapToObj(i ->
                record(String.format("2026-11-01 09:%02d:%02d", i / 60, i % 60), 1, 1)).toList());
        firstPage.getData().setCount(102);
        var secondPage = response(List.of(record("2026-11-01 10:00:00", 1, 1), record("2026-11-01 12:00:01", 1, 1)));
        secondPage.getData().setCount(102);
        when(api.getHistoryData(any())).thenAnswer(call -> {
            InclineHistoryRequest request = call.getArgument(0);
            assertEquals("2026-10-31 12:00:00", request.getStartTm());
            assertEquals("2026-11-01 12:00:00", request.getEndTm());
            assertEquals("asc", request.getSortMode());
            return request.getCurpagenum() == 1 ? firstPage : secondPage;
        });
        var result = handler.fetchDeviceData("SN", "2026-10-31 12:00:00", "2026-11-01 12:00:00");
        assertNotNull(result);
        assertEquals(101, result.getData().getResultData().size());
        verify(api, times(2)).getHistoryData(any());
    }

    @Test
    void followsAllPagesWhenTheRemoteServiceCapsTheRequestedPageSize() throws Exception {
        when(api.getHistoryData(any())).thenAnswer(call -> {
            InclineHistoryRequest request = call.getArgument(0);
            int page = request.getCurpagenum();
            var response = response(IntStream.range((page - 1) * 2, Math.min(page * 2, 5))
                    .mapToObj(i -> record(String.format("2026-11-01 09:00:%02d", i), 1, 1)).toList());
            response.getData().setCount(5);
            response.getData().setPagesize(2);
            return response;
        });
        var result = handler.fetchDeviceData("SN", "2026-10-31 12:00:00", "2026-11-01 12:00:00");
        assertEquals(5, result.getData().getResultData().size());
        verify(api, times(3)).getHistoryData(any());
    }

    @Test
    void authenticationRetryPreservesRequestedHistoricalRangeAndCannotRecurseForever() throws Exception {
        when(api.getHistoryData(any())).thenThrow(new IOException("401"));
        assertNull(handler.fetchDeviceData("SN", "2026-10-31 12:00:00", "2026-11-01 12:00:00"));
        verify(api, times(1)).refreshToken();
        verify(api, times(2)).getHistoryData(argThat(request -> request.getStartTm().equals("2026-10-31 12:00:00")
                && request.getEndTm().equals("2026-11-01 12:00:00") && request.getCurpagenum() == 1));
    }

    @Test
    void pageFailureDoesNotReturnAPartialHistoryWindow() throws Exception {
        var page = response(List.of(record("2026-11-01 09:00:00", 1, 1)));
        page.getData().setCount(102);
        when(api.getHistoryData(any())).thenReturn(page).thenThrow(new IOException("timeout"));
        assertNull(handler.fetchDeviceData("SN", "2026-10-31 12:00:00", "2026-11-01 12:00:00"));
    }

    private static DeviceInfoExt device(String code, DeviceType type) {
        var device = new DeviceInfoExt();
        device.setDeviceCode(code); device.setRemoteDeviceId("SN"); device.setDeviceType(type);
        return device;
    }

    private static InclineRecord record(String time, double angle, double acceleration) {
        var record = new InclineRecord();
        record.setTm(time); record.setAngleX(angle); record.setAngleY(0.0); record.setTemp(20.0);
        record.setAccMaxZ(acceleration); record.setRunStatus(0); record.setAlarmStatus(0);
        return record;
    }

    private static InclineHistoryResponse response(List<InclineRecord> records) {
        var response = new InclineHistoryResponse();
        var data = new InclineHistoryResponse.InclineHistoryData();
        data.setResultData(records); response.setData(data);
        return response;
    }
}
