package com.zd.sdq.controller;

import com.zd.sdq.entity.dto.DeviceHistoryDTO;
import com.zd.sdq.service.DeviceHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.format.DateTimeParseException;

@RestController
@RequestMapping("/api/device-history")
@RequiredArgsConstructor
public class DeviceHistoryController {
    private final DeviceHistoryService historyService;

    @GetMapping("/{deviceCode}")
    public DeviceHistoryDTO history(@PathVariable String deviceCode,
                                    @RequestParam(required = false) Integer hours,
                                    @RequestParam(required = false) String start,
                                    @RequestParam(required = false) String end) {
        if (start == null && end == null) {
            return historyService.history(deviceCode, hours == null ? 24 : hours, Instant.now());
        }
        if (start == null || end == null || hours != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请同时提供起止时间，且不要同时指定 hours");
        }
        try {
            return historyService.history(deviceCode, Instant.parse(start), Instant.parse(end));
        } catch (DateTimeParseException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "起止时间须为带时区的 ISO 时间");
        }
    }
}
