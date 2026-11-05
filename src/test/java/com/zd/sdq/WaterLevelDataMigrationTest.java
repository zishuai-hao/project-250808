package com.zd.sdq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zd.sdq.mapper.WaterLevelDataMapper;
import com.zd.sdq.service.WaterLevelDataService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class WaterLevelDataMigrationTest {
    @TempDir Path directory;

    @Test
    void upgradesBothLegacyTimeColumnNamesWithoutDeletingRowsAndIsRepeatable() {
        for (String timeColumn : List.of("record_time", "data_time")) {
            var source = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve(timeColumn + ".db"));
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE water_level_data (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "device_code TEXT, water_level REAL, " + timeColumn + " DATETIME NOT NULL, create_time DATETIME)");
            jdbc.update("INSERT INTO water_level_data (device_code, water_level, " + timeColumn + ") "
                    + "VALUES ('W-1', 6.75, '2026-11-01 09:59:00')");
            var service = new WaterLevelDataService(mock(WaterLevelDataMapper.class), new ObjectMapper(), source);
            service.initTable();
            service.initTable();
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM water_level_data", Integer.class));
            assertEquals("2026-11-01 09:59:00", jdbc.queryForObject("SELECT data_time FROM water_level_data", String.class));
            assertEquals(6.75, jdbc.queryForObject("SELECT water_level FROM water_level_data", Double.class));
            assertNull(jdbc.queryForObject("SELECT payload FROM water_level_data", String.class));
            assertEquals("PENDING", jdbc.queryForObject("SELECT report_status FROM water_level_data", String.class));
            assertEquals(0, jdbc.queryForObject("SELECT report_attempts FROM water_level_data", Integer.class));
        }
    }
}
