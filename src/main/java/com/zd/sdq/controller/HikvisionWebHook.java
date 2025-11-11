package com.zd.sdq.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * @author hzs
 * @date 2025/08/09
 */
@Slf4j
@RestController
@RequestMapping("/api/hikvision-webhook")
public class HikvisionWebHook {
    // 这里可以添加处理Hikvision WebHook的相关方法
    // 例如接收事件通知、处理视频流等

    // 示例方法
    @PostMapping("/event")
    public ResponseEntity<?> handleEvent(@RequestParam Map<String, String> formDataMap
    ) {
        log.info("Received form data (Map): {}", formDataMap);

        // 打印 Map 中的每个键值对
        formDataMap.forEach((key, value) -> log.info("  Key: {}, Value: {}", key, value));

//        @RequestBody GaugeReadingEventNotification notification
//        try {
//            // 在这里，'notification' 对象已经是解析好的Java对象，可以直接使用
//            System.out.println("成功接收到事件，设备ID: " + notification.getDeviceID());
//
//            GaugeReadingEventDetails eventDetails = notification.getGaugeReadingEvent();
//            if (eventDetails != null) {
//                System.out.println("当前水位: " + eventDetails.getWaterLevel() + " m");
//                System.out.println("观测时间: " + eventDetails.getObservationTime());
//            }
//
//        } catch (Exception e) {
//            // 记录异常日志
//            System.err.println("处理事件时发生错误: " + e.getMessage());
//            return ResponseEntity.status(500).body("服务器内部错误");
//        }
        return ResponseEntity.ok("OK");
    }
}
