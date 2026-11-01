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
        config.validate();
        return config;
    }

    private class Memory {
        final InclineFluctuationConfig config = configured();
        final ObjectMapper json = new ObjectMapper();
        final InclineForwardDataMapper mapper = org.mockito.Mockito.mock(InclineForwardDataMapper.class);
        final Map<String, com.zd.sdq.entity.InclineForwardData> rows = new java.util.HashMap<>();
        InclineFluctuationService service = new InclineFluctuationService(config, mapper, json);
        String sn = "SN";
        int zero = 0;
        Memory() {
            org.mockito.Mockito.when(mapper.selectPreviousIncline(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString())).thenAnswer(call -> rows.get(call.getArgument(0)));
        }
        InclineFluctuationService.Angles accept(String code, int second, double x, double y, boolean fail) {
            var device = device(code, DeviceType.INC);
            device.setRemoteDeviceId(sn);
            var record = new InclineHistoryResponse.InclineRecord();
            record.setTm(java.time.LocalDateTime.of(2026, 11, 1, 0, 0).plusSeconds(second)
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            record.setAngleX(x); record.setAngleY(y); record.setZero(zero);
            var angles = service.apply(device, record,
                    new com.zd.sdq.service.incline.InclineBaselineService.CorrectedAngles(x,y,null));
            if (fail) throw new IllegalStateException("database unavailable");
            var row = new com.zd.sdq.entity.InclineForwardData();
            row.setDeviceCode(code); row.setRemoteDeviceId(sn); row.setDataTime(record.getTm());
            row.setCreateTime(record.getTm());
            try {
                row.setRawData(json.writeValueAsString(record));
                row.setPayload(json.writeValueAsString(List.of(List.of(record.getTm(),
                        Double.toString(angles.x()), Double.toString(angles.y()), "20.000"))));
            } catch (Exception e) { throw new RuntimeException(e); }
            rows.put(code, row);
            return angles;
        }
    }

    @Test
    void doublesOnlyOriginalIncrementsAndRemainsStableOnPlateausAndDuplicateTimes() {
        var f = new Memory();
        assertEquals(1.5, f.accept("INC",1,1.5,-2,false).x());
        assertEquals(1.5002, f.accept("INC",2,1.5001,-1.9999,false).x());
        assertEquals(1.5002, f.accept("INC",2,1.5001,-1.9999,false).x());
        assertEquals(1.5002, f.accept("INC",3,1.5001,-1.9999,false).x());
        assertEquals(1.5004, f.accept("INC",4,1.5002,-1.9998,false).x());
        var falling = f.accept("INC",5,1.4999,-2.0001,false);
        assertEquals(1.4998, falling.x());
        assertEquals(-2.0002, falling.y());
    }

    @Test
    void restoresFromRawDataAndPayloadAcrossRestartWithoutAnyInMemoryPreviousValue() {
        var f = new Memory();
        f.accept("INC",1,1.5,-2,false);
        f.accept("INC",2,1.5001,-2,false);
        f.service = new InclineFluctuationService(f.config,f.mapper,f.json);
        assertEquals(1.5004, f.accept("INC",3,1.5002,-2,false).x());
        assertEquals(1.5004, f.accept("INC",4,1.5002,-2,false).x());
    }

    @Test
    void failedSaveAndDatabaseReadDoNotAdvanceOrInventAPreviousPoint() {
        var f = new Memory();
        f.accept("INC",1,1.5,-2,false);
        assertThrows(IllegalStateException.class, () -> f.accept("INC",2,1.5001,-2,true));
        assertTrue(f.rows.get("INC").getDataTime().endsWith("00:00:01"));
        assertEquals(1.5002, f.accept("INC",2,1.5001,-2,false).x());
        org.mockito.Mockito.when(f.mapper.selectPreviousIncline(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenThrow(new IllegalStateException("read failed"));
        assertThrows(IllegalStateException.class, () -> f.accept("INC",3,1.5002,-2,false));
        assertTrue(f.rows.get("INC").getDataTime().endsWith("00:00:02"));
    }

    @Test
    void devicesAndSensorsHaveIndependentStartingPoints() {
        var f = new Memory();
        f.accept("A",1,1.5,-2,false); f.accept("B",1,3,4,false);
        assertEquals(1.5002,f.accept("A",2,1.5001,-2,false).x());
        assertEquals(3.0002,f.accept("B",2,3.0001,4,false).x());
        f.sn="NEW-SENSOR";
        assertEquals(1.5002,f.accept("A",3,1.5002,-2,false).x());
        assertEquals(1.5004,f.accept("A",4,1.5003,-2,false).x());
    }

    @Test
    void disabledOrUnityFactorWritesCorrectedOriginalAndRejectsInvalidNumbers() {
        var f = new Memory();
        f.config.setEnabled(false);
        assertEquals(1.5,f.accept("INC",1,1.5,-2,false).x());
        assertEquals(1.5001,f.accept("INC",2,1.5001,-2,false).x());
        org.mockito.Mockito.verify(f.mapper,org.mockito.Mockito.never()).selectPreviousIncline(
                org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString());
        f.config.setEnabled(true); f.config.setFactor(1);
        assertEquals(1.5002,f.accept("INC",3,1.5002,-2,false).x());
        assertThrows(IllegalArgumentException.class,()->f.accept("INC",4,Double.NaN,-2,false));
        f.config.setFactor(2);
        f.accept("HUGE",1,Double.MAX_VALUE,0,false);
        assertThrows(IllegalArgumentException.class,()->f.accept("HUGE",2,-Double.MAX_VALUE,0,false));
    }

    @Test
    void missingInvalidOrFutureHistoryStartsWithTheCorrectedOriginal() {
        var f = new Memory();
        f.accept("INC",2,1.5,-2,false);
        f.rows.get("INC").setRawData("{}");
        assertEquals(1.5001,f.accept("INC",3,1.5001,-2,false).x());
        assertEquals(1.4999,f.accept("INC",1,1.4999,-2,false).x());
    }

    @Test
    void loadsUniformFactorWithoutDeviceCentersAndRejectsInvalidGains() {
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(Binding.class).run(context -> {
                    assertNull(context.getStartupFailure());
                    var config=context.getBean(InclineFluctuationConfig.class);
                    assertTrue(config.isEnabled()); assertEquals(2,config.getFactor());
                });
        var config=configured();
        for(double factor:new double[]{0,.5,-1,Double.NaN,Double.POSITIVE_INFINITY}) {
            config.setFactor(factor); assertThrows(IllegalArgumentException.class,config::validate);
        }
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
        baseline.save("TEST-INC", 1.0, -2.0);
        double[] rawX = {1.5,1.5001,1.5001,1.5002,1.5002,1.4999,1.4999};
        double[] rawY = {-2,-1.9999,-1.9999,-1.9998,-1.9998,-2.0001,-2.0001};
        for (int pass = 0; pass < 7; pass++) {
            // 每条都新建处理器/缩放服务，证明仅凭数据库即可恢复，不依赖内存窗口。
            var handler = new InclineDataHandler(null, null, null, cache,
                    new VibrationSpikeFilter(new VibrationFilterConfig()), store, baseline,
                    new InclineFluctuationService(configured(), mapper, json));
            Map<String, Date> cursor = (Map<String, Date>) ReflectionTestUtils.getField(handler,"remoteDeviceIdLastDataTimeMap");
            cursor.put("SN",DateUtil.parse("2026-11-01 00:00:00"));
            record.setTm(String.format("2026-11-01 %02d:00:00", pass + 1));
            record.setAngleX(rawX[pass]);
            record.setAngleY(rawY[pass]);
            ReflectionTestUtils.invokeMethod(handler, "processResponse", List.of(incline, vibration), response, "SN");
            var row = jdbc.queryForMap("SELECT * FROM incline_forward_data WHERE device_code='TEST-INC' ORDER BY id DESC LIMIT 1");
            var payload = json.readTree(row.get("payload").toString()).get(0);
            String expectedX = new String[]{"0.500000","0.500200","0.500200","0.500400","0.500400","0.499800","0.499800"}[pass];
            String expectedY = new String[]{"0.000000","0.000200","0.000200","0.000400","0.000400","-0.000200","-0.000200"}[pass];
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
            Set<String> expectedKeys = Set.of("angleX", "angleY", "rawAngleX", "rawAngleY", "baselineApplied",
                    "baselineX", "baselineY", "baselineUpdatedAt", "temp", "angleUnit", "tempUnit", "alarmStatus", "runStatus");
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
