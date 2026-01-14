package com.zd.sdq.service;

import com.zd.sdq.mapper.WaterLevelDataMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 水位数据清理服务
 * 自动删除48小时前的数据
 * 
 * @author hzs
 * @date 2025/12/05
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WaterLevelDataCleanupService {
    
    private final WaterLevelDataMapper waterLevelDataMapper;
    
    /**
     * 定时任务：每小时执行一次，删除48小时前的数据
     */
    @Scheduled(fixedRate = 60 * 60 * 1000) // 每小时执行一次
    public void cleanupOldData() {
        try {
            // 计算48小时前的时间
            LocalDateTime cutoffTime = LocalDateTime.now().minusHours(48);
            
            // 删除48小时前的数据
            int deletedCount = waterLevelDataMapper.deleteByDataTimeBefore(cutoffTime);
            
            if (deletedCount > 0) {
                log.info("成功删除 {} 条48小时前的水位数据（删除时间阈值: {}）", deletedCount, cutoffTime);
            } else {
                log.debug("没有需要删除的旧数据（时间阈值: {}）", cutoffTime);
            }
        } catch (Exception e) {
            log.error("清理旧水位数据失败: {}", e.getMessage(), e);
        }
    }
}

