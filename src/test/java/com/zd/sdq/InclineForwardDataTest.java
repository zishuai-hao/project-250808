package com.zd.sdq;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.company.cbf.starter.data.entity.MqttData;
import com.company.cbf.starter.data.service.forward.MqttDataBuilder;
import com.company.cbf.starter.data.service.forward.device.DeviceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zd.sdq.entity.DeviceInfoExt;
import com.zd.sdq.entity.dto.incline.InclineHistoryResponse;
import com.zd.sdq.mapper.InclineForwardDataMapper;
import com.zd.sdq.service.incline.InclineForwardDataService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class InclineForwardDataTest {
    @TempDir
    Path directory;

    @Test
    void createsTableIdempotentlyAndPersistsRawRecordAndExactForwardPayload() throws Exception {
        // 独立临时 SQLite 文件，不启动应用、真实接口或 MQTT。
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + directory.resolve("snapshots.db"));
        InclineForwardDataMapper mapper = mapper(dataSource);
        ObjectMapper json = new ObjectMapper();
        InclineForwardDataService service = new InclineForwardDataService(mapper, json, dataSource);
        service.initTable();
        service.initTable();

        DeviceInfoExt device = new DeviceInfoExt();
        device.setDeviceCode("TEST-INC-001");
        device.setDeviceType(DeviceType.INC);
        InclineHistoryResponse.InclineRecord record = new InclineHistoryResponse.InclineRecord();
        record.setTm("2026-11-01 01:00:00");
        record.setAngleX(0.123456789);
        record.setAngleY(-0.987654321);
        record.setAngleZ(0.4);
        record.setTemp(20.123456);
        record.setAccMaxZ(22.0);
        record.setAlarmStatus(3);
        record.setRunStatus(0);
        MqttData mqttData = new MqttDataBuilder(DeviceType.INC)
                .currentTime().monitoringCode(device.getDeviceCode())
                .addData(ZonedDateTime.now(), "0.123457", "-0.987654", "20.123").build();

        service.saveBeforeForward(device, "1524020712", record, mqttData);

        // 使用独立连接读取，验证 MyBatis insert 已提交、字段映射与表结构一致。
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        var row = jdbc.queryForMap("SELECT * FROM incline_forward_data");
        assertEquals(device.getDeviceCode(), row.get("device_code"));
        assertEquals("1524020712", row.get("remote_device_id"));
        assertEquals("INC", row.get("device_type"));
        assertEquals(record.getTm(), row.get("data_time"));
        assertEquals(mqttData.getSampleTime().longValue(), ((Number) row.get("sample_time")).longValue());
        assertEquals(json.valueToTree(mqttData.getValue()), json.readTree(row.get("payload").toString()));
        assertEquals(json.valueToTree(record), json.readTree(row.get("raw_data").toString()));
        assertNotNull(row.get("create_time"));

        service.initTable();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM incline_forward_data", Integer.class));
    }

    @Test
    void cleanupDeletesOnlySnapshotsCreatedBeforeCutoffAndPreservesBoundary() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + directory.resolve("cleanup.db"));
        InclineForwardDataMapper mapper = mapper(dataSource);
        InclineForwardDataService service = new InclineForwardDataService(mapper, new ObjectMapper(), dataSource);
        service.initTable();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // 原始采集时间故意与入库时间相反，确认保留期以入库时间为准。
        String insert = "INSERT INTO incline_forward_data "
                + "(device_code, remote_device_id, device_type, data_time, sample_time, payload, raw_data, create_time) "
                + "VALUES (?, 'SN', 'INC', ?, 1, '[]', '{}', ?)";
        jdbc.update(insert, "expired", "2099-01-01 00:00:00", "2026-09-17 02:59:59");
        jdbc.update(insert, "boundary", "2000-01-01 00:00:00", "2026-09-17 03:00:00");
        jdbc.update(insert, "newer", "2000-01-01 00:00:00", "2026-09-17 03:00:01");

        assertEquals(1, mapper.deleteCreatedBefore("2026-09-17 03:00:00"));
        assertEquals(java.util.List.of("boundary", "newer"),
                jdbc.queryForList("SELECT device_code FROM incline_forward_data ORDER BY id", String.class));
        assertEquals(0, mapper.deleteCreatedBefore("2026-09-17 03:00:00"));

        // 执行实际任务，验证使用 45 天窗口，清理可重复执行。
        jdbc.update("DELETE FROM incline_forward_data");
        DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(insert, "expired", "2099-01-01 00:00:00", now.minusDays(46).format(format));
        jdbc.update(insert, "recent", "2000-01-01 00:00:00", now.minusDays(44).format(format));
        service.cleanupOldData();
        service.cleanupOldData();
        assertEquals(java.util.List.of("recent"),
                jdbc.queryForList("SELECT device_code FROM incline_forward_data", String.class));
    }

    private InclineForwardDataMapper mapper(DriverManagerDataSource dataSource) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(InclineForwardDataMapper.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        SqlSessionTemplate session = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        return session.getMapper(InclineForwardDataMapper.class);
    }
}
