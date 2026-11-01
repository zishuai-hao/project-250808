package com.zd.sdq;

import cn.hutool.core.date.DateUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.company.cbf.starter.data.service.forward.device.DeviceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zd.sdq.config.InclineFluctuationConfig;
import com.zd.sdq.config.VibrationFilterConfig;
import com.zd.sdq.entity.DeviceInfoExt;
import com.zd.sdq.entity.dto.incline.InclineHistoryResponse;
import com.zd.sdq.mapper.InclineForwardDataMapper;
import com.zd.sdq.service.DeviceLatestDataCache;
import com.zd.sdq.service.incline.InclineDataHandler;
import com.zd.sdq.service.incline.InclineFluctuationService;
import com.zd.sdq.service.incline.InclineForwardDataService;
import com.zd.sdq.service.incline.VibrationSpikeFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class InclineFluctuationTest {
    @TempDir Path directory;

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(InclineFluctuationConfig.class)
    static class Binding {}

    private InclineFluctuationConfig configured() {
        var config = new InclineFluctuationConfig();
        config.setEnabled(true);
        var center = new InclineFluctuationConfig.Center();
        center.setX(1.5);
        center.setY(-2.0);
        config.getCenters().put("TEST-INC", center);
        config.validate();
        return config;
    }

    @Test
    void doublesChangesAroundFixedCentersWithoutAccumulatingAcrossCallsOrRestarts() {
        var config = configured();
        var service = new InclineFluctuationService(config);
        assertEquals(1.5, service.apply("TEST-INC", 1.5, -2).x());
        var changed = service.apply("TEST-INC", 1.5001, -1.9999);
        assertEquals(1.5002, changed.x());
        assertEquals(-1.9998, changed.y());
        assertEquals(changed, service.apply("TEST-INC", 1.5001, -1.9999));
        assertEquals(changed, new InclineFluctuationService(config).apply("TEST-INC", 1.5001, -1.9999));
        assertEquals(1.4998, service.apply("TEST-INC", 1.4999, -2).x());
        assertEquals(1.54, service.apply("TEST-INC", 1.52, -2).x());
    }

    @Test
    void loadsAllFortyCentersAndOneUniformFactorThroughTheActualApplicationImport() {
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(Binding.class).run(context -> {
                    assertNull(context.getStartupFailure());
                    var config = context.getBean(InclineFluctuationConfig.class);
                    assertTrue(config.isEnabled());
                    assertEquals(2.0, config.getFactor());
                    assertEquals(40, config.getCenters().size());
                    var service = new InclineFluctuationService(config);
                    config.getCenters().forEach((code, center) -> {
                        var result = service.apply(code, center.getX() + .0001, center.getY() - .0001);
                        assertEquals(center.getX() + .0002, result.x(), 1e-12);
                        assertEquals(center.getY() - .0002, result.y(), 1e-12);
                    });
                });
    }

    @Test
    void supportsDisablingAndDoesNotInventCentersOrAllowInvalidNumbers() {
        var config = configured();
        var service = new InclineFluctuationService(config);
        assertEquals(1.5001, service.apply("NEW-INC", 1.5001, -2).x());
        assertThrows(IllegalArgumentException.class, () -> service.apply("TEST-INC", Double.NaN, -2));
        assertThrows(IllegalArgumentException.class, () -> service.apply("TEST-INC", Double.MAX_VALUE, -2));
        config.setFactor(1);
        assertEquals(1.5001, service.apply("TEST-INC", 1.5001, -2).x());
        config.setFactor(2);
        config.setEnabled(false);
        assertEquals(1.5001, service.apply("TEST-INC", 1.5001, -2).x());
    }

    @Test
    void rejectsInvalidConfiguration() {
        var config = configured();
        for (double gain : new double[]{0, .5, -2, Double.NaN, Double.POSITIVE_INFINITY}) {
            config.setFactor(gain);
            assertThrows(IllegalArgumentException.class, config::validate);
        }
        config.setFactor(2);
        var center = config.getCenters().get("TEST-INC");
        center.setY(null);
        assertThrows(IllegalArgumentException.class, config::validate);
        center.setY(Double.NaN);
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void storesAmplifiedAnglesInExistingPayloadAndCacheWithoutAddingAnyFields() throws Exception {
        var baseline = InclineBaselineTest.database(directory.resolve("devices.db")).service();
        var source = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("collected.db"));
        var mybatis = new MybatisConfiguration();
        mybatis.setMapUnderscoreToCamelCase(true);
        mybatis.addMapper(InclineForwardDataMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(mybatis);
        var mapper = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()))
                .getMapper(InclineForwardDataMapper.class);
        var json = new ObjectMapper();
        var store = new InclineForwardDataService(mapper, json, source);
        store.initTable();
        var jdbc = new JdbcTemplate(source);
        var cache = new DeviceLatestDataCache();
        var handler = new InclineDataHandler(null, null, null, cache,
                new VibrationSpikeFilter(new VibrationFilterConfig()), store, baseline,
                new InclineFluctuationService(configured()));
        Map<String, Date> cursor = (Map<String, Date>) ReflectionTestUtils.getField(handler,
                "remoteDeviceIdLastDataTimeMap");
        cursor.put("SN", DateUtil.parse("2026-11-01 00:00:00"));
        var incline = device("TEST-INC", DeviceType.INC);
        var vibration = device("TEST-VIB", DeviceType.VIB);
        var record = new InclineHistoryResponse.InclineRecord();
        record.setTemp(20.123456);
        record.setAccMaxZ(22.0);
        record.setRunStatus(0);
        record.setAlarmStatus(2);
        var data = new InclineHistoryResponse.InclineHistoryData();
        data.setResultData(List.of(record));
        var response = new InclineHistoryResponse();
        response.setData(data);
        for (int pass = 0; pass < 3; pass++) {
            record.setTm(String.format("2026-11-01 %02d:00:00", pass + 1));
            record.setAngleX(pass == 0 ? 1.5 : 1.5001);
            record.setAngleY(pass == 0 ? -2.0 : -1.9999);
            if (pass == 2) baseline.save("TEST-INC", 1.0, -2.0);
            ReflectionTestUtils.invokeMethod(handler, "processResponse", List.of(incline, vibration), response, "SN");
            var row = jdbc.queryForMap("SELECT * FROM incline_forward_data WHERE device_code='TEST-INC' ORDER BY id DESC LIMIT 1");
            var payload = json.readTree(row.get("payload").toString()).get(0);
            String expectedX = pass == 0 ? "1.500000" : pass == 1 ? "1.500200" : "0.500200";
            String expectedY = pass == 0 ? "-2.000000" : pass == 1 ? "-1.999800" : "0.000200";
            assertEquals(4, payload.size());
            assertEquals(expectedX, payload.get(1).asText());
            assertEquals(expectedY, payload.get(2).asText());
            assertEquals("20.123", payload.get(3).asText());
            assertEquals(json.valueToTree(record), json.readTree(row.get("raw_data").toString()));
            var latest = cache.getLatestData("TEST-INC").getLatestData();
            assertEquals(expectedX, latest.get("angleX"));
            assertEquals(expectedY, latest.get("angleY"));
            assertEquals(record.getAngleX(), latest.get("rawAngleX"));
            assertEquals(2, latest.get("alarmStatus"));
            Set<String> expectedKeys = pass == 2
                    ? Set.of("angleX", "angleY", "rawAngleX", "rawAngleY", "baselineApplied", "baselineX", "baselineY",
                        "baselineUpdatedAt", "temp", "angleUnit", "tempUnit", "alarmStatus", "runStatus")
                    : Set.of("angleX", "angleY", "rawAngleX", "rawAngleY", "baselineApplied", "temp", "angleUnit",
                        "tempUnit", "alarmStatus", "runStatus");
            assertEquals(expectedKeys, latest.keySet());
            var vib = jdbc.queryForMap("SELECT payload FROM incline_forward_data WHERE device_code='TEST-VIB' ORDER BY id DESC LIMIT 1");
            assertEquals("0.2157", json.readTree(vib.get("payload").toString()).get(0).get(1).asText());
            assertEquals((pass + 1)*2, jdbc.queryForObject("SELECT COUNT(*) FROM incline_forward_data", Integer.class));
            ReflectionTestUtils.invokeMethod(handler, "processResponse", List.of(incline, vibration), response, "SN");
            assertEquals((pass + 1)*2, jdbc.queryForObject("SELECT COUNT(*) FROM incline_forward_data", Integer.class));
        }
        assertEquals(Set.of("id", "device_code", "remote_device_id", "device_type", "data_time", "sample_time",
                "payload", "raw_data", "create_time"), jdbc.queryForList("PRAGMA table_info(incline_forward_data)")
                .stream().map(c -> c.get("name")).collect(java.util.stream.Collectors.toSet()));
    }

    private DeviceInfoExt device(String code, DeviceType type) {
        var device = new DeviceInfoExt();
        device.setDeviceCode(code);
        device.setDeviceType(type);
        device.setRemoteDeviceId("SN");
        device.setEnable(true);
        return device;
    }
}
