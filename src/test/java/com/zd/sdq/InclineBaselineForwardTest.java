package com.zd.sdq;

import cn.hutool.core.date.DateUtil;
import com.company.cbf.starter.data.entity.MqttData;
import com.company.cbf.starter.data.service.forward.device.DeviceType;
import com.zd.sdq.entity.DeviceInfoExt;
import com.zd.sdq.config.VibrationFilterConfig;
import com.zd.sdq.service.incline.VibrationSpikeFilter;
import com.zd.sdq.entity.dto.incline.InclineHistoryResponse;
import com.zd.sdq.service.DeviceLatestDataCache;
import com.zd.sdq.service.incline.InclineBaselineService;
import com.zd.sdq.service.incline.InclineDataHandler;
import com.zd.sdq.service.incline.InclineForwardDataService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InclineBaselineForwardTest {
    @TempDir
    Path directory;

    @Test
    @SuppressWarnings("unchecked")
    void storesRelativeAnglesButPreservesRawRecordTemperatureAndVibration() throws Exception {
        InclineBaselineService baselines = InclineBaselineTest.database(directory.resolve("devices.db")).service();
        DeviceLatestDataCache cache = new DeviceLatestDataCache();
        InclineForwardDataService snapshots = mock(InclineForwardDataService.class);
        InclineDataHandler handler = new InclineDataHandler(null, null, null, cache,
                new VibrationSpikeFilter(new VibrationFilterConfig()), snapshots, baselines);
        Map<String, Date> cursor = (Map<String, Date>) ReflectionTestUtils.getField(handler,
                "remoteDeviceIdLastDataTimeMap");
        DeviceInfoExt incline = device("TEST-INC", DeviceType.INC);
        DeviceInfoExt vibration = device("TEST-VIB", DeviceType.VIB);
        InclineHistoryResponse.InclineRecord record = new InclineHistoryResponse.InclineRecord();
        record.setTm("2026-11-01 01:00:00");
        record.setAngleX(1.23456789);
        record.setAngleY(-2.98765432);
        record.setTemp(20.123456);
        record.setAccMaxZ(22.0);
        InclineHistoryResponse.InclineHistoryData data = new InclineHistoryResponse.InclineHistoryData();
        data.setResultData(List.of(record));
        InclineHistoryResponse response = new InclineHistoryResponse();
        response.setData(data);

        // 同一路真实处理函数依次验证未设置、已设置、清除后的转发内容。
        for (int pass = 0; pass < 3; pass++) {
            record.setTm(String.format("2026-11-01 %02d:00:00", pass + 1));
            if (pass == 1) baselines.save("TEST-INC", 1.0, -2.0);
            if (pass == 2) baselines.remove("TEST-INC");
            cursor.put("SN", DateUtil.parse("2026-11-01 00:00:00"));
            clearInvocations(snapshots);
            ReflectionTestUtils.invokeMethod(handler, "processResponse", List.of(incline, vibration), response, "SN");

            ArgumentCaptor<MqttData> payload = ArgumentCaptor.forClass(MqttData.class);
            verify(snapshots).saveCollectedData(eq(incline), eq("SN"), same(record), payload.capture());
            var values = payload.getValue().getValue();
            assertEquals(record.getTm() + ".000", values.get(0).get(0));
            assertEquals(List.of(pass == 1 ? "0.234568" : "1.234568",
                    pass == 1 ? "-0.987654" : "-2.987654", "20.123"), values.get(0).subList(1, 4));

            verify(snapshots).saveCollectedData(eq(vibration), eq("SN"), same(record), payload.capture());
            assertEquals("0.2157", payload.getValue().getValue().get(0).get(1));

            var latest = cache.getLatestData("TEST-INC").getLatestData();
            assertEquals(1.23456789, latest.get("rawAngleX"));
            assertEquals(-2.98765432, latest.get("rawAngleY"));
            assertEquals(pass == 1, latest.get("baselineApplied"));
            assertEquals(pass == 1 ? "0.234568" : "1.234568", latest.get("angleX"));
            assertEquals(1.23456789, record.getAngleX());
            assertEquals(-2.98765432, record.getAngleY());
            assertEquals(20.123456, record.getTemp());
        }
    }

    private DeviceInfoExt device(String code, DeviceType type) {
        DeviceInfoExt device = new DeviceInfoExt();
        device.setDeviceCode(code);
        device.setDeviceType(type);
        device.setRemoteDeviceId("SN");
        device.setEnable(true);
        return device;
    }
}
