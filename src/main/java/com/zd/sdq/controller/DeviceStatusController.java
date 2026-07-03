package com.zd.sdq.controller;

import com.zd.sdq.entity.dto.DeviceLatestDataDTO;
import com.zd.sdq.service.DeviceLatestDataCache;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 设备最新数据状态查询接口.
 */
@RestController
@RequestMapping("/api/device-status")
@RequiredArgsConstructor
public class DeviceStatusController {
    private final DeviceLatestDataCache deviceLatestDataCache;

    @GetMapping
    public List<DeviceLatestDataDTO> listLatestData() {
        return deviceLatestDataCache.listLatestData();
    }

    @GetMapping("/{deviceCode}")
    public ResponseEntity<DeviceLatestDataDTO> getLatestData(@PathVariable String deviceCode) {
        DeviceLatestDataDTO latestData = deviceLatestDataCache.getLatestData(deviceCode);
        if (latestData == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(latestData);
    }
}
