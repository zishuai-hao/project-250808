package com.zd.sdq.service.incline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.cbf.starter.data.service.forward.device.DeviceType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zd.sdq.entity.DeviceInfoExt;
import com.zd.sdq.entity.dto.incline.InclineBaseline;
import com.zd.sdq.mapper.DeviceInfoExtMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class InclineBaselineService {
    private final DeviceInfoExtMapper deviceMapper;
    private final ObjectMapper objectMapper;
    private final DataSource dataSource;

    @PostConstruct
    public void initColumn() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        boolean exists = jdbc.queryForList("PRAGMA table_info(s_device_info_ext)").stream()
                .anyMatch(column -> "incline_baseline".equals(column.get("name")));
        if (!exists) {
            jdbc.execute("ALTER TABLE s_device_info_ext ADD COLUMN incline_baseline TEXT");
        }
    }

    public Map<String, InclineBaseline> list() {
        Map<String, InclineBaseline> result = new LinkedHashMap<>();
        deviceMapper.selectList(new LambdaQueryWrapper<DeviceInfoExt>()
                        .select(DeviceInfoExt::getDeviceCode, DeviceInfoExt::getInclineBaseline)
                        .eq(DeviceInfoExt::getDeviceType, DeviceType.INC)
                        .isNotNull(DeviceInfoExt::getInclineBaseline))
                .forEach(device -> result.put(device.getDeviceCode(), parse(device.getInclineBaseline())));
        return result;
    }

    public InclineBaseline save(String deviceCode, Double angleX, Double angleY) {
        InclineBaseline baseline = new InclineBaseline(angleX, angleY, Instant.now().toString());
        try {
            update(deviceCode, objectMapper.writeValueAsString(baseline));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("倾角基准 JSON 序列化失败", e);
        }
        return baseline;
    }

    public void remove(String deviceCode) {
        update(deviceCode, null);
    }

    private void update(String deviceCode, String json) {
        if (deviceMapper.updateInclineBaselineJson(deviceCode, json) != 1) {
            throw new IllegalStateException("保存倾角基准失败，设备不存在或不是 INC: " + deviceCode);
        }
    }

    public CorrectedAngles apply(String deviceCode, Double rawAngleX, Double rawAngleY) {
        // 每条记录读取数据库中的最新基准，不使用启动时设备对象中的旧值。
        InclineBaseline baseline = parse(deviceMapper.getInclineBaselineJson(deviceCode));
        if (baseline == null) {
            return new CorrectedAngles(rawAngleX, rawAngleY, null);
        }
        return new CorrectedAngles(subtract(rawAngleX, baseline.angleX()),
                subtract(rawAngleY, baseline.angleY()), baseline);
    }

    private InclineBaseline parse(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, InclineBaseline.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("数据库倾角基准 JSON 格式无效", e);
        }
    }

    private double subtract(double current, double baseline) {
        return BigDecimal.valueOf(current).subtract(BigDecimal.valueOf(baseline)).doubleValue();
    }

    public record CorrectedAngles(double angleX, double angleY, InclineBaseline baseline) {}
}
