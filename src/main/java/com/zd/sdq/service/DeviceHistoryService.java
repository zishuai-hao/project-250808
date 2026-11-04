package com.zd.sdq.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zd.sdq.entity.DeviceInfoExt;
import com.zd.sdq.entity.dto.DeviceHistoryDTO;
import com.zd.sdq.entity.dto.DeviceHistoryDTO.Point;
import com.zd.sdq.entity.dto.DeviceHistoryDTO.Series;
import com.zd.sdq.mapper.DeviceInfoExtMapper;
import com.zd.sdq.mapper.InclineForwardDataMapper;
import com.zd.sdq.mapper.WaterLevelDataMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DeviceHistoryService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_POINTS = 2000;
    private final DeviceInfoExtMapper deviceMapper;
    private final InclineForwardDataMapper inclineMapper;
    private final WaterLevelDataMapper waterMapper;
    private final ObjectMapper json;

    public DeviceHistoryDTO history(String deviceCode, int hours, Instant now) {
        if (hours < 1 || hours > 45 * 24) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "时间范围须为 1 至 1080 小时");
        }
        now = now.truncatedTo(ChronoUnit.SECONDS);
        return history(deviceCode, now.minusSeconds(hours * 3600L), now);
    }

    public DeviceHistoryDTO history(String deviceCode, Instant from, Instant to) {
        from = from.truncatedTo(ChronoUnit.SECONDS);
        to = to.truncatedTo(ChronoUnit.SECONDS);
        if (!from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(45)) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "起始时间须早于结束时间，查询跨度不能超过45天");
        }
        DeviceInfoExt device = deviceMapper.selectOne(new LambdaQueryWrapper<DeviceInfoExt>()
                .select(DeviceInfoExt::getDeviceCode, DeviceInfoExt::getDeviceType, DeviceInfoExt::getRemoteDeviceId)
                .eq(DeviceInfoExt::getDeviceCode, deviceCode));
        if (device == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "设备不存在");
        String type = device.getDeviceType() == null ? "" : device.getDeviceType().name();
        List<Series> series = switch (type) {
            case "INC" -> List.of(new Series("X 轴倾角", "°", 6), new Series("Y 轴倾角", "°", 6),
                    new Series("温度", "℃", 3));
            case "VIB" -> List.of(new Series("Z 轴振动", "m/s²", 4));
            case "WLV" -> List.of(new Series("水位差值", "m", 4), new Series("原始水位", "m", 4));
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该设备类型暂不支持观测曲线");
        };
        String fromTime = TIME.format(from.atZone(ZONE));
        String toTime = TIME.format(to.atZone(ZONE));
        List<Point> points = new ArrayList<>();
        if ("WLV".equals(type)) {
            for (var row : waterMapper.selectObservationHistory(deviceCode, fromTime, toTime, MAX_POINTS + 1)) {
                List<Double> values = values(row.getPayload(), series.size());
                // 旧记录只留存差值，不能用当前基准倒推原始水位。
                if (row.getPayload() == null) values.set(0, finite(row.getWaterLevel()));
                points.add(new Point(timestamp(row.getDataTime()), values));
            }
        } else {
            for (var row : inclineMapper.selectSimulationHistory(deviceCode, device.getRemoteDeviceId(), type,
                    fromTime, toTime, MAX_POINTS + 1)) {
                points.add(new Point(timestamp(row.getDataTime()), values(row.getPayload(), series.size())));
            }
        }
        boolean truncated = points.size() > MAX_POINTS;
        if (truncated) points.remove(points.size() - 1);
        Collections.reverse(points);
        return new DeviceHistoryDTO(deviceCode, type, from.toEpochMilli(), to.toEpochMilli(), series, points, truncated);
    }

    private List<Double> values(String payload, int size) {
        List<Double> result = new ArrayList<>(Collections.nCopies(size, null));
        if (payload == null) return result;
        try {
            JsonNode root = json.readTree(payload);
            if (root == null || !root.isArray() || root.size() != 1 || !root.path(0).isArray()) return result;
            for (int i = 0; i < size; i++) {
                JsonNode value = root.path(0).path(i + 1);
                if (!value.isNumber() && !value.isTextual()) continue;
                try {
                    result.set(i, finite(Double.parseDouble(value.asText())));
                } catch (NumberFormatException ignored) {
                    // 缺失或损坏的数值显示为断点，不伪造为零。
                }
            }
        } catch (JsonProcessingException ignored) {
            // 单条快照损坏不影响其他时间点。
        }
        return result;
    }

    private Double finite(Double value) {
        return value != null && Double.isFinite(value) ? value : null;
    }

    private long timestamp(String time) {
        return LocalDateTime.parse(time, TIME).atZone(ZONE).toInstant().toEpochMilli();
    }
}
