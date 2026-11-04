package com.zd.sdq;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zd.sdq.controller.DeviceHistoryController;
import com.zd.sdq.mapper.DeviceInfoExtMapper;
import com.zd.sdq.mapper.InclineForwardDataMapper;
import com.zd.sdq.mapper.WaterLevelDataMapper;
import com.zd.sdq.service.DeviceHistoryService;
import com.zd.sdq.service.WaterLevelDataService;
import com.zd.sdq.service.incline.InclineForwardDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DeviceHistoryTest {
    private static final Instant NOW = Instant.parse("2026-11-04T04:00:00Z");
    @TempDir Path directory;
    private JdbcTemplate jdbc;
    private DeviceHistoryService service;

    @BeforeEach
    void setup() throws Exception {
        var source = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("history.db"));
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE s_device_info_ext (device_code TEXT PRIMARY KEY, device_type TEXT, remote_device_id TEXT)");
        for (String type : List.of("INC", "VIB", "WLV")) {
            jdbc.update("INSERT INTO s_device_info_ext VALUES (?, ?, 'SN')", type, type);
        }
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(DeviceInfoExtMapper.class);
        configuration.addMapper(InclineForwardDataMapper.class);
        configuration.addMapper(WaterLevelDataMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        var session = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        var inclineMapper = session.getMapper(InclineForwardDataMapper.class);
        var waterMapper = session.getMapper(WaterLevelDataMapper.class);
        var json = new ObjectMapper();
        new InclineForwardDataService(inclineMapper, json, source).initTable();
        new WaterLevelDataService(waterMapper, json, source).initTable();
        service = new DeviceHistoryService(session.getMapper(DeviceInfoExtMapper.class), inclineMapper, waterMapper, json);
    }

    @Test
    void readsSavedInclineValuesInTimeOrderDeduplicatesAndIsolatesSensorAndWindow() {
        incline("INC", "SN", "2026-11-04 12:00:00", "[[\"ignored\",\"0.123456\",-0.2,21.125]]");
        incline("INC", "SN", "2026-11-03 12:00:00", "[[0,1,2,20]]");
        incline("INC", "SN", "2026-11-03 12:00:00", "[[0,3,4,22]]");
        incline("INC", "OLD-SN", "2026-11-04 11:00:00", "[[0,99,99,99]]");
        incline("INC", "SN", "2026-11-03 11:59:59", "[[0,99,99,99]]");
        incline("INC", "SN", "2026-11-04 12:00:01", "[[0,99,99,99]]");
        incline("VIB", "SN", "2026-11-04 11:00:00", "[[0,9]]");
        var before = jdbc.queryForList("SELECT * FROM incline_forward_data");

        var result = service.history("INC", 24, NOW);

        assertEquals(2, result.points().size());
        assertEquals(List.of(3.0, 4.0, 22.0), result.points().get(0).values());
        assertEquals(List.of(0.123456, -0.2, 21.125), result.points().get(1).values());
        assertEquals(NOW.minusSeconds(86400).toEpochMilli(), result.points().get(0).time());
        assertEquals(NOW.toEpochMilli(), result.points().get(1).time());
        assertEquals("°", result.series().get(0).unit());
        assertFalse(result.truncated());
        assertEquals(before, jdbc.queryForList("SELECT * FROM incline_forward_data"));
    }

    @Test
    void preservesZeroAndRepresentsInvalidNumbersAndBrokenSnapshotsAsGaps() {
        incline("INC", "SN", "2026-11-04 10:00:00", "[[0,\"0.000000\",null,\"NaN\"]]");
        incline("INC", "SN", "2026-11-04 11:00:00", "broken json");
        incline("INC", "SN", "2026-11-04 12:00:00", "[[0,true,\"Infinity\",\"\"]]");
        var result = service.history("INC", 24, NOW);
        assertEquals(Arrays.asList(0.0, null, null), result.points().get(0).values());
        assertEquals(Arrays.asList(null, null, null), result.points().get(1).values());
        assertEquals(Arrays.asList(null, null, null), result.points().get(2).values());
        incline("VIB", "SN", "2026-11-04 12:00:00", "[[0,\"-0.0062\"]]");
        var vibration = service.history("VIB", 24, NOW);
        assertEquals(List.of(-0.0062), vibration.points().get(0).values());
        assertEquals("m/s²", vibration.series().get(0).unit());
    }

    @Test
    void waterUsesStoredDifferenceAndRawLevelWithoutInventingLegacyRawValues() {
        water("2026-11-03 12:00:00", 1.2, null);
        water("2026-11-04 12:00:00", 2.3, "[[0,\"2.3000\",\"8.7000\"]]");
        water("2026-11-04 12:00:00", 2.4, "[[0,\"2.4000\",\"8.6000\"]]");
        water("2026-11-04 12:00:01", 99, null);
        water("2026-11-03 11:59:59", 99, null);
        jdbc.update("INSERT INTO water_level_data (device_code, water_level, data_time) VALUES ('OTHER', 99, '2026-11-04 11:00:00')");
        var result = service.history("WLV", 24, NOW);
        assertEquals(2, result.points().size());
        assertEquals(Arrays.asList(1.2, null), result.points().get(0).values());
        assertEquals(List.of(2.4, 8.6), result.points().get(1).values());
    }

    @Test
    void limitsLargeResponsesToNewestPointsAndReportsTruncation() {
        List<Object[]> batch = new ArrayList<>();
        var format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));
        for (int i = 0; i <= 2000; i++) batch.add(new Object[]{format.format(NOW.minusSeconds(i))});
        jdbc.batchUpdate("INSERT INTO water_level_data (device_code, water_level, data_time) VALUES ('WLV', 1, ?)", batch);
        var result = service.history("WLV", 1, NOW);
        assertEquals(2000, result.points().size());
        assertTrue(result.truncated());
        assertEquals(NOW.minusSeconds(1999).toEpochMilli(), result.points().get(0).time());
        assertEquals(NOW.toEpochMilli(), result.points().get(1999).time());
    }

    @Test
    void endpointReturnsHistoryAndValidatesRequestParameters() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new DeviceHistoryController(service)).build();
        var time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.of("Asia/Shanghai")).format(Instant.now().minusSeconds(60));
        incline("VIB", "SN", time, "[[0,0.25]]");
        mvc.perform(get("/api/device-history/VIB"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deviceCode").value("VIB"))
                .andExpect(jsonPath("$.points[0].values[0]").value(0.25))
                .andExpect(jsonPath("$.series[0].unit").value("m/s²"));
        mvc.perform(get("/api/device-history/INC")).andExpect(status().isOk())
                .andExpect(jsonPath("$.points").isEmpty());
        mvc.perform(get("/api/device-history/MISSING")).andExpect(status().isNotFound());
        for (String hours : List.of("0", "1081", "-1", "abc")) {
            mvc.perform(get("/api/device-history/INC").param("hours", hours)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void customRangeIncludesBothBoundariesAndValidatesDatesAndSpan() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new DeviceHistoryController(service)).build();
        incline("VIB", "SN", "2026-11-04 10:00:00", "[[0,0.1]]");
        incline("VIB", "SN", "2026-11-04 11:00:00", "[[0,0.2]]");
        incline("VIB", "SN", "2026-11-04 11:00:01", "[[0,99]]");
        mvc.perform(get("/api/device-history/VIB").param("start", "2026-11-04T02:00:00Z")
                        .param("end", "2026-11-04T03:00:00Z"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.points.length()").value(2))
                .andExpect(jsonPath("$.points[0].values[0]").value(0.1))
                .andExpect(jsonPath("$.points[1].values[0]").value(0.2))
                .andExpect(jsonPath("$.fromTime").value(Instant.parse("2026-11-04T02:00:00Z").toEpochMilli()));
        for (String[] pair : List.of(
                new String[]{"bad", "2026-11-04T03:00:00Z"},
                new String[]{"2026-11-04T04:00:00Z", "2026-11-04T03:00:00Z"},
                new String[]{"2026-11-04T03:00:00Z", "2026-11-04T03:00:00Z"},
                new String[]{"2026-09-01T03:00:00Z", "2026-11-04T03:00:00Z"})) {
            mvc.perform(get("/api/device-history/VIB").param("start", pair[0]).param("end", pair[1]))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/device-history/VIB").param("start", "2026-11-04T02:00:00Z"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/device-history/VIB").param("end", "2026-11-04T03:00:00Z"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/device-history/VIB").param("hours", "24")
                        .param("start", "2026-11-04T02:00:00Z").param("end", "2026-11-04T03:00:00Z"))
                .andExpect(status().isBadRequest());
    }

    private void incline(String type, String sn, String time, String payload) {
        jdbc.update("INSERT INTO incline_forward_data "
                        + "(device_code, remote_device_id, device_type, data_time, sample_time, payload, raw_data, create_time) "
                        + "VALUES (?, ?, ?, ?, 1, ?, '{}', ?)", type, sn, type, time, payload, time);
    }

    private void water(String time, double difference, String payload) {
        jdbc.update("INSERT INTO water_level_data (device_code, water_level, data_time, payload) VALUES ('WLV', ?, ?, ?)",
                difference, time, payload);
    }
}
