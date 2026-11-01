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
import com.zd.sdq.entity.dto.incline.InclineHistoryResponse.InclineRecord;
import com.zd.sdq.mapper.InclineForwardDataMapper;
import com.zd.sdq.service.DeviceLatestDataCache;
import com.zd.sdq.service.incline.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class InclineAutoZeroTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();
    private InclineBaselineTest.Database db;
    private InclineBaselineService baselines;
    private InclineForwardDataMapper mapper;
    private InclineDataHandler handler;
    private final DeviceInfoExt device = device();
    private DeviceLatestDataCache cache;

    @BeforeEach
    void setup() throws Exception {
        db = InclineBaselineTest.database(directory.resolve("collected.db"));
        var source = Objects.requireNonNull(db.jdbc().getDataSource());
        var proxy = new ProxyFactory(db.service());
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()));
        baselines = (InclineBaselineService) proxy.getProxy();
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(InclineForwardDataMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        mapper = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()))
                .getMapper(InclineForwardDataMapper.class);
        var store = new InclineForwardDataService(mapper, json, source);
        store.initTable();
        restart(store);
    }

    @SuppressWarnings("unchecked")
    private void restart(InclineForwardDataService store) {
        cache = new DeviceLatestDataCache();
        var config = new InclineFluctuationConfig();
        config.setEnabled(true);
        handler = new InclineDataHandler(null, null, null, cache,
                new VibrationSpikeFilter(new VibrationFilterConfig()), store, baselines,
                new InclineFluctuationService(config, mapper, json));
        ((Map<String, Date>) ReflectionTestUtils.getField(handler, "remoteDeviceIdLastDataTimeMap"))
                .put("SN", DateUtil.parse("2026-11-01 00:00:00"));
    }

    @Test
    void newestNewRecordBecomesZeroDespiteOlderStoredHistoryAndRestartResumesIncrements() throws Exception {
        db.jdbc().update("INSERT INTO incline_forward_data "
                + "(device_code, remote_device_id, device_type, data_time, sample_time, payload, raw_data, create_time) "
                + "VALUES ('TEST-INC','SN','INC','2026-11-01 00:00:00',0,?,?,'2026-11-01 00:00:00')",
                "[[\"2026-11-01 00:00:00\",\"999\",\"999\",\"20\"]]",
                "{\"angleX\":1.5,\"angleY\":-2}");
        var latest = record(2, 1.5002, -1.9998);
        process(latest, record(0, 100, 100), record(1, 1.5001, -1.9999));
        var baseline = baselines.list().get("TEST-INC");
        assertEquals(1.5002, baseline.angleX());
        assertEquals(-1.9998, baseline.angleY());
        assertEquals(2, count()); // 已有历史保留，只新增最新一条零值。
        assertPayload(2, "0.000000", "0.000000");
        assertEquals(json.valueToTree(latest), json.readTree(mapper.selectPreviousIncline(
                "TEST-INC", latest.getTm()).getRawData()));
        assertEquals(1.5002, cache.getLatestData("TEST-INC").getLatestData().get("rawAngleX"));
        process(latest); // 重复数据不会再置零或再次入库。
        assertEquals(2, count());
        process(record(3, 1.5003, -1.9997), record(4, 1.5003, -1.9997));
        assertPayload(4, "0.000200", "0.000200");
        restart(new InclineForwardDataService(mapper, json, db.jdbc().getDataSource()));
        process(record(5, 1.5004, -1.9996));
        assertPayload(5, "0.000400", "0.000400");
        assertEquals(baseline, baselines.list().get("TEST-INC"));
    }

    @Test
    void duplicatesAndInvalidRecordsCannotCreateAZero() {
        process(record(0, 1.5, -2), record(1, Double.NaN, -2));
        assertTrue(baselines.list().isEmpty());
        assertEquals(0, count());
        process(record(2, 1.5, -2));
        assertNotNull(baselines.list().get("TEST-INC"));
        assertEquals(1, count());
    }

    @Test
    void selectsLatestValidRecordAndProcessesDuplicateTimestampsOnlyOnce() throws Exception {
        process(record(2, 1.5002, -2), record(2, 1.5002, -2), record(3, Double.NaN, -2),
                record(1, 1.5, -2));
        assertEquals(1.5002, baselines.list().get("TEST-INC").angleX());
        assertEquals(1, count());
        assertPayload(2, "0.000000", "0.000000");
    }

    @Test
    void existingZeroIncludingNumericZeroIsNeverReplaced() throws Exception {
        var baseline = baselines.save("TEST-INC", 0.0, 0.0);
        process(record(1, 1.5, -2), record(2, 1.5001, -1.9999));
        assertEquals(baseline, baselines.list().get("TEST-INC"));
        assertEquals(2, count());
        assertPayload(2, "1.500200", "-1.999800");
    }

    @Test
    @SuppressWarnings("unchecked")
    void snapshotFailureRollsBackZeroAndLeavesTheRecordAvailableForRetry() throws Exception {
        db.jdbc().execute("CREATE TRIGGER reject_snapshot BEFORE INSERT ON incline_forward_data "
                + "BEGIN SELECT RAISE(ABORT, 'test failure'); END");
        var latest = record(2, 1.5002, -2);
        assertThrows(RuntimeException.class, () -> process(record(1, 1.5, -2), latest));
        assertTrue(baselines.list().isEmpty());
        assertEquals(0, count());
        assertNull(cache.getLatestData("TEST-INC"));
        var cursor = (Map<String, Date>) ReflectionTestUtils.getField(handler, "remoteDeviceIdLastDataTimeMap");
        assertTrue(cursor.get("SN").before(DateUtil.parse(latest.getTm())));
        db.jdbc().execute("DROP TRIGGER reject_snapshot");
        process(latest);
        assertPayload(2, "0.000000", "0.000000");
        assertEquals(1.5002, baselines.list().get("TEST-INC").angleX());
    }

    private void assertPayload(int hour, String x, String y) throws Exception {
        var row = mapper.selectPreviousIncline("TEST-INC", record(hour, 0, 0).getTm());
        var values = json.readTree(row.getPayload()).get(0);
        assertEquals(x, values.get(1).asText());
        assertEquals(y, values.get(2).asText());
        assertEquals("20.123", values.get(3).asText());
        assertEquals(4, values.size());
    }

    private int count() {
        return db.jdbc().queryForObject("SELECT COUNT(*) FROM incline_forward_data", Integer.class);
    }

    private void process(InclineRecord... records) {
        var data = new InclineHistoryResponse.InclineHistoryData();
        data.setResultData(List.of(records));
        var response = new InclineHistoryResponse();
        response.setData(data);
        ReflectionTestUtils.invokeMethod(handler, "processResponse", List.of(device), response, "SN");
    }

    private InclineRecord record(int hour, double x, double y) {
        var record = new InclineRecord();
        record.setTm(String.format("2026-11-01 %02d:00:00", hour));
        record.setAngleX(x); record.setAngleY(y); record.setTemp(20.1234);
        return record;
    }

    private static DeviceInfoExt device() {
        var device = new DeviceInfoExt();
        device.setDeviceCode("TEST-INC"); device.setRemoteDeviceId("SN");
        device.setDeviceType(DeviceType.INC); device.setEnable(true);
        return device;
    }
}
