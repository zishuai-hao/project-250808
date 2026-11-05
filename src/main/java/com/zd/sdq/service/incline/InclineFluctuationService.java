package com.zd.sdq.service.incline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zd.sdq.config.InclineFluctuationConfig;
import com.zd.sdq.entity.DeviceInfoExt;
import com.zd.sdq.entity.InclineForwardData;
import com.zd.sdq.entity.dto.incline.InclineHistoryResponse.InclineRecord;
import com.zd.sdq.mapper.InclineForwardDataMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Objects;

/** 零点不变时，用原始角度差乘倍率累加到数据库上一条入库值；不保存额外状态。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InclineFluctuationService {
    private final InclineFluctuationConfig config;
    private final InclineForwardDataMapper mapper;
    private final ObjectMapper json;
    public record Angles(double x, double y) {}
    private record Previous(BigDecimal rawX, BigDecimal rawY, BigDecimal storedX, BigDecimal storedY) {}

    public Angles apply(DeviceInfoExt device, InclineRecord record, InclineBaselineService.CorrectedAngles corrected) {
        Angles initial = finite(number(corrected.angleX()), number(corrected.angleY()));
        if (!config.isEnabled() || config.getFactor() == 1) return initial;
        // 查询失败向外传播；不能把数据库故障当成“没有前值”而重置结果。
        var row = mapper.selectPreviousIncline(device.getDeviceCode(), record.getTm());
        var old = previous(row, device, record.getTm());
        if (old == null) return initial;
        BigDecimal factor = number(config.getFactor());
        // 两条原始值扣同一零点后作差，等价于直接作原始角度差。
        return finite(old.storedX().add(number(record.getAngleX()).subtract(old.rawX()).multiply(factor)),
                old.storedY().add(number(record.getAngleY()).subtract(old.rawY()).multiply(factor)));
    }

    private Previous previous(InclineForwardData row, DeviceInfoExt device, String time) {
        if (row == null || !Objects.equals(row.getRemoteDeviceId(), device.getRemoteDeviceId())
                || row.getDataTime() == null || row.getDataTime().compareTo(time) > 0) return null;
        try {
            JsonNode raw = json.readTree(row.getRawData()), payload = json.readTree(row.getPayload());
            if (raw == null || !raw.path("angleX").isNumber() || !raw.path("angleY").isNumber()
                    || payload == null || !payload.isArray() || payload.size() != 1
                    || !payload.get(0).isArray() || payload.get(0).size() != 4) return null;
            var values = payload.get(0);
            return new Previous(number(raw.path("angleX").asDouble()), number(raw.path("angleY").asDouble()),
                    decimal(values.get(1)), decimal(values.get(2)));
        } catch (Exception invalid) {
            log.warn("上一条倾角数据不可用，按当前扣零值入库，测点: {}，采集时间: {}", device.getDeviceCode(), time);
            return null;
        }
    }

    private BigDecimal decimal(JsonNode value) {
        if (value == null || value.isNull()) throw new IllegalArgumentException("缺少上一条入库角度");
        BigDecimal result = new BigDecimal(value.asText());
        number(result.doubleValue());
        return result;
    }

    private BigDecimal number(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("倾角与倍率必须为有限数");
        return BigDecimal.valueOf(value);
    }

    private Angles finite(BigDecimal x, BigDecimal y) {
        double dx = x.doubleValue(), dy = y.doubleValue();
        if (!Double.isFinite(dx) || !Double.isFinite(dy)) throw new IllegalArgumentException("倾角增量放大结果超出有限数值范围");
        return new Angles(dx, dy);
    }
}
