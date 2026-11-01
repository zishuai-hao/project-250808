package com.zd.sdq;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zd.sdq.mapper.DeviceInfoExtMapper;
import com.zd.sdq.service.incline.InclineBaselineService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class InclineBaselineTest {
    @TempDir
    Path directory;

    record Database(InclineBaselineService service, DeviceInfoExtMapper mapper, JdbcTemplate jdbc) {}

    static Database database(Path file) throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + file);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // 模拟升级前的设备表：没有倾角基准字段，保留已有水位基线。
        jdbc.execute("CREATE TABLE IF NOT EXISTS s_device_info_ext "
                + "(device_code TEXT PRIMARY KEY, device_type TEXT, baseline REAL)");
        for (String code : new String[]{"INC-1", "INC-2", "TEST-INC", "TEST-VIB"}) {
            jdbc.update("INSERT OR IGNORE INTO s_device_info_ext (device_code, device_type, baseline) VALUES (?, ?, 12.5)",
                    code, code.equals("TEST-VIB") ? "VIB" : "INC");
        }
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(DeviceInfoExtMapper.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        DeviceInfoExtMapper mapper = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()))
                .getMapper(DeviceInfoExtMapper.class);
        InclineBaselineService service = new InclineBaselineService(mapper, new ObjectMapper(), dataSource);
        service.initColumn();
        return new Database(service, mapper, jdbc);
    }

    @Test
    void migratesExistingTableAndPersistsJsonAcrossRestart() throws Exception {
        Path file = directory.resolve("devices.db");
        Database db = database(file);
        InclineBaselineService service = db.service();
        service.initColumn();
        assertEquals(1.2, service.apply("INC-1", 1.2, -2.3).angleX());
        assertNull(service.apply("INC-1", 1.2, -2.3).baseline());

        var baseline = service.save("INC-1", 1.0, -2.0);
        service.save("INC-2", 0.0, 0.0);
        assertEquals(0.2, service.apply("INC-1", 1.2, -2.3).angleX());
        assertEquals(-0.3, service.apply("INC-1", 1.2, -2.3).angleY());
        assertEquals(baseline, service.list().get("INC-1"));
        assertEquals(1.2, service.apply("INC-2", 1.2, -2.3).angleX());
        var json = new ObjectMapper().readTree(db.mapper().getInclineBaselineJson("INC-1"));
        assertEquals(1.0, json.path("angleX").doubleValue());
        assertEquals(-2.0, json.path("angleY").doubleValue());
        assertEquals(12.5, db.jdbc().queryForObject(
                "SELECT baseline FROM s_device_info_ext WHERE device_code='INC-1'", Double.class));

        InclineBaselineService restarted = database(file).service();
        assertEquals(service.list(), restarted.list());
        assertEquals(-0.3, restarted.apply("INC-1", 1.2, -2.3).angleY());
    }

    @Test
    void replacesAndClearsBaselineAndSeesDirectDatabaseChanges() throws Exception {
        Database db = database(directory.resolve("devices.db"));
        InclineBaselineService service = db.service();
        service.save("INC-1", 1.0, -2.0);
        assertEquals(0.2, service.apply("INC-1", 1.2, -2.3).angleX());
        assertEquals(0.4, service.apply("INC-1", 1.4, -2.3).angleX());
        service.save("INC-1", 1.4, -2.3);
        assertEquals(0.0, service.apply("INC-1", 1.4, -2.3).angleX());
        service.remove("INC-1");
        service.remove("INC-1");
        assertNull(db.mapper().getInclineBaselineJson("INC-1"));
        assertEquals(1.4, service.apply("INC-1", 1.4, -2.3).angleX());
        db.jdbc().update("UPDATE s_device_info_ext SET incline_baseline=? WHERE device_code='INC-1'",
                "{\"angleX\":1,\"angleY\":-2,\"updatedAt\":\"2026-11-01T00:00:00Z\"}");
        assertEquals(0.4, service.apply("INC-1", 1.4, -2.3).angleX());
    }

    @Test
    void rejectsInvalidValuesAndInvalidStoredJson() throws Exception {
        Database db = database(directory.resolve("devices.db"));
        InclineBaselineService service = db.service();
        assertThrows(IllegalArgumentException.class, () -> service.save("INC-1", null, 0.0));
        assertThrows(IllegalArgumentException.class, () -> service.save("INC-1", Double.NaN, 0.0));
        assertThrows(IllegalArgumentException.class, () -> service.save("INC-1", 0.0, Double.POSITIVE_INFINITY));
        assertThrows(IllegalStateException.class, () -> service.save("TEST-VIB", 1.0, 1.0));
        assertThrows(IllegalStateException.class, () -> service.save("MISSING", 1.0, 1.0));
        assertTrue(service.list().isEmpty());
        db.jdbc().update("UPDATE s_device_info_ext SET incline_baseline='{}' WHERE device_code='INC-1'");
        assertThrows(IllegalStateException.class, () -> service.apply("INC-1", 1.0, 1.0));
    }

    @Test
    void failedDatabaseWritePreservesPreviousBaseline() throws Exception {
        Database db = database(directory.resolve("devices.db"));
        InclineBaselineService service = db.service();
        var original = service.save("INC-1", 1.0, -2.0);
        db.jdbc().execute("CREATE TRIGGER reject_baseline_write BEFORE UPDATE OF incline_baseline "
                + "ON s_device_info_ext BEGIN SELECT RAISE(ABORT, 'test write failure'); END");
        assertThrows(RuntimeException.class, () -> service.save("INC-1", 5.0, 5.0));
        assertThrows(RuntimeException.class, () -> service.remove("INC-1"));
        assertEquals(original, service.list().get("INC-1"));
        assertEquals(0.2, service.apply("INC-1", 1.2, -2.3).angleX());
    }
}
