package com.zd.sdq;

import com.zd.sdq.entity.dto.incline.*;
import com.zd.sdq.service.incline.InclineApiService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;

/**
 * 倾角传感器 API 测试类
 * @author hzs
 * @date 2025/11/13
 */
@Slf4j
@SpringBootTest
@Disabled
public class InclineApiTest {
    
    @Autowired
    private InclineApiService inclineApiService;
    
    /**
     * 测试登录
     */
    @Test
    public void testLogin() throws IOException {
        LoginResponse response = inclineApiService.login("dxkj", "dxkj250808");
        log.info("登录结果: {}", response);
        log.info("登录状态: {}", response.getSuccess());
        log.info("Token: {}", response.getData().getLoginToken());
    }
    
    /**
     * 测试获取传感器列表
     */
    @Test
    public void testGetSensorList() throws IOException {
        // 先登录
        inclineApiService.login("dxkj", "dxkj250808");
        
        // 获取传感器列表
        SensorListRequest request = new SensorListRequest(10, 1);
        SensorListResponse response = inclineApiService.getSensorList(request);
        
        log.info("传感器列表查询结果: {}", response.getMsg());
        log.info("总记录数: {}", response.getData().getTotal());
        log.info("总页数: {}", response.getData().getPages());
        
        if (response.getData().getRecords() != null) {
            response.getData().getRecords().forEach(sensor ->
                    log.info("传感器 SN: {}, 类型: {}", sensor.getSn(), sensor.getTpCode()));
        }
    }
    
    /**
     * 测试获取倾角历史数据
     */
    @Test
    public void testGetHistoryData() throws IOException {
        // 先登录
        inclineApiService.login("dxkj", "dxkj250808");
        
        // 获取历史数据
        InclineHistoryRequest request = new InclineHistoryRequest(
                "1524020752",
                "1051",
                "2025-11-11 12:00:00",
                "2025-11-13 00:00:00"
        );
        request.setPagesize(10);
        request.setCurpagenum(1);
        
        InclineHistoryResponse response = inclineApiService.getHistoryData(request);
        
        log.info("历史数据查询结果: {}", response.getMsg());
        log.info("总记录数: {}", response.getData().getCount());
        log.info("当前页: {}/{}", response.getData().getCurpagenum(), 
                (response.getData().getCount() + response.getData().getPagesize() - 1) / response.getData().getPagesize());
        
        if (response.getData().getResultData() != null) {
            response.getData().getResultData().forEach(record ->
                    log.info("时间: {}, X角度: {}, Y角度: {}, Z角度: {}, 合倾角: {}, 温度: {}",
                    record.getTm(),
                    record.getAngleX(),
                    record.getAngleY(),
                    record.getAngleZ(),
                    record.getAngleXyz(),
                    record.getTemp()));
        }
    }
    
    /**
     * 测试完整流程
     */
    @Test
    public void testFullProcess() throws IOException {
        // 1. 登录
        log.info("========== 开始登录 ==========");
        LoginResponse loginResponse = inclineApiService.login("dxkj", "dxkj250808");
        log.info("登录成功: {}", loginResponse.getSuccess());
        
        // 2. 获取传感器列表
        log.info("\n========== 获取传感器列表 ==========");
        SensorListRequest sensorRequest = new SensorListRequest(10, 1);
        SensorListResponse sensorResponse = inclineApiService.getSensorList(sensorRequest);
        log.info("查询到 {} 个传感器", sensorResponse.getData().getTotal());
        
        // 3. 获取历史数据
        if (sensorResponse.getData().getRecords() != null && !sensorResponse.getData().getRecords().isEmpty()) {
            SensorListResponse.SensorRecord firstSensor = sensorResponse.getData().getRecords().get(0);
            log.info("\n========== 获取传感器 {} 的历史数据 ==========", firstSensor.getSn());
            
            InclineHistoryRequest historyRequest = new InclineHistoryRequest(
                    firstSensor.getSn(),
                    String.valueOf(firstSensor.getTpCode()),
                    "2025-11-11 12:00:00",
                    "2025-11-13 00:00:00"
            );
            historyRequest.setPagesize(5);
            
            InclineHistoryResponse historyResponse = inclineApiService.getHistoryData(historyRequest);
            log.info("查询到 {} 条历史记录", historyResponse.getData().getCount());
            
            if (historyResponse.getData().getResultData() != null && !historyResponse.getData().getResultData().isEmpty()) {
                log.info("最新一条数据:");
                InclineHistoryResponse.InclineRecord record = historyResponse.getData().getResultData().get(0);
                log.info("  时间: {}", record.getTm());
                log.info("  X角度: {}", record.getAngleX());
                log.info("  Y角度: {}", record.getAngleY());
                log.info("  Z角度: {}", record.getAngleZ());
                log.info("  合倾角: {}", record.getAngleXyz());
                log.info("  方向角: {}", record.getTrend());
                log.info("  温度: {}", record.getTemp());
            }
        }
        
        log.info("\n========== 测试完成 ==========");
    }
    
    /**
     * 测试手动刷新 Token
     */
    @Test
    public void testRefreshToken() {
        log.info("========== 测试手动刷新 Token ==========");
        
        // 手动刷新 Token
        boolean success = inclineApiService.refreshToken();
        log.info("Token 刷新结果: {}", success ? "成功" : "失败");
        log.info("当前 Token: {}", inclineApiService.getLoginToken());
        
        // 验证刷新后的 Token 是否可用
        try {
            SensorListRequest request = new SensorListRequest(10, 1);
            SensorListResponse response = inclineApiService.getSensorList(request);
            log.info("使用刷新后的 Token 查询传感器列表: {}", response.getStatus() == 200 ? "成功" : "失败");
            if (response.getData() != null) {
                log.info("查询到 {} 个传感器", response.getData().getTotal());
            }
        } catch (IOException e) {
            log.error("使用刷新后的 Token 查询失败", e);
        }
    }
}

