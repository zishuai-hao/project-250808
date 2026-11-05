package com.zd.sdq.service;

import cn.hutool.core.util.NumberUtil;
import com.company.cbf.starter.data.service.forward.MqttDataBuilder;
import com.company.cbf.starter.data.service.forward.device.DeviceType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zd.sdq.entity.WaterLevelData;
import com.zd.sdq.mapper.WaterLevelDataMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WaterLevelDataService {
    private final WaterLevelDataMapper mapper;
    private final ObjectMapper objectMapper;
    private final DataSource dataSource;

    @PostConstruct
    public void initTable() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // 早期部署使用 record_time，实体和后续版本使用 data_time；保留数据迁移后再建索引。
        var columns = jdbc.queryForList("PRAGMA table_info(water_level_data)");
        boolean hasDataTime = columns.stream().anyMatch(column -> "data_time".equals(column.get("name")));
        boolean hasRecordTime = columns.stream().anyMatch(column -> "record_time".equals(column.get("name")));
        if (!hasDataTime && hasRecordTime) {
            jdbc.execute("ALTER TABLE water_level_data RENAME COLUMN record_time TO data_time");
        }
        new ResourceDatabasePopulator(new ClassPathResource("db/create_water_level_table.sql"))
                .execute(dataSource);
        boolean hasPayload = jdbc.queryForList("PRAGMA table_info(water_level_data)").stream()
                .anyMatch(column -> "payload".equals(column.get("name")));
        if (!hasPayload) {
            jdbc.execute("ALTER TABLE water_level_data ADD COLUMN payload TEXT");
        }
        DeviceReportStateStore.migrate(dataSource, DeviceReportStateStore.Source.WATER);
    }

    public void saveCollectedData(String deviceCode, double difference, double waterLevel,
                                  ZonedDateTime receivedAt) {
        if (!Double.isFinite(difference) || !Double.isFinite(waterLevel)) {
            throw new IllegalArgumentException("水位及差值必须为有限数值");
        }
        ZonedDateTime sourceTime = receivedAt.withZoneSameInstant(ZoneId.of("Asia/Shanghai"));
        String time = sourceTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        var values = new MqttDataBuilder(DeviceType.WLV)
                .monitoringCode(deviceCode).sampleTime(sourceTime.toInstant().toEpochMilli())
                .addData(sourceTime, List.of(NumberUtil.roundStr(difference, 4), NumberUtil.roundStr(waterLevel, 4)))
                .build().getValue();
        try {
            WaterLevelData data = new WaterLevelData(null, deviceCode, difference, time, time,
                    objectMapper.writeValueAsString(values));
            if (mapper.insert(data) != 1) {
                throw new IllegalStateException("水位采集数据入库失败，测点: " + deviceCode);
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化水位采集数据失败，测点: " + deviceCode, e);
        }
    }
}
