package com.zd.sdq.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.cbf.starter.data.service.forward.device.DeviceType;
import com.zd.sdq.entity.DeviceInfoExt;
import com.zd.sdq.entity.dto.incline.InclineBaseline;
import com.zd.sdq.mapper.DeviceInfoExtMapper;
import com.zd.sdq.service.incline.InclineBaselineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/incline-baselines")
@RequiredArgsConstructor
public class InclineBaselineController {
    private final InclineBaselineService baselineService;
    private final DeviceInfoExtMapper deviceMapper;

    @GetMapping
    public Map<String, InclineBaseline> list() {
        return baselineService.list();
    }

    @PutMapping("/{deviceCode}")
    public InclineBaseline save(@PathVariable String deviceCode, @RequestBody BaselineRequest request) {
        requireInclineDevice(deviceCode);
        return baselineService.save(deviceCode, request.angleX(), request.angleY());
    }

    @DeleteMapping("/{deviceCode}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable String deviceCode) {
        requireInclineDevice(deviceCode);
        baselineService.remove(deviceCode);
    }

    private void requireInclineDevice(String deviceCode) {
        DeviceInfoExt device = deviceMapper.selectOne(new LambdaQueryWrapper<DeviceInfoExt>()
                .select(DeviceInfoExt::getDeviceCode, DeviceInfoExt::getDeviceType)
                .eq(DeviceInfoExt::getDeviceCode, deviceCode));
        if (device == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "设备不存在");
        }
        if (device.getDeviceType() != DeviceType.INC) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只有 INC 倾角设备支持设置基准");
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalidValue(IllegalArgumentException exception) {
        return Map.of("message", exception.getMessage());
    }

    @ExceptionHandler({DataAccessException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> saveFailed(RuntimeException exception) {
        log.error("倾角基准数据库操作失败", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "倾角基准读取或保存失败，请检查服务日志"));
    }

    public record BaselineRequest(Double angleX, Double angleY) {}
}
