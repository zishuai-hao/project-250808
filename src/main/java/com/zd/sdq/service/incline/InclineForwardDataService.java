package com.zd.sdq.service.incline;

import cn.hutool.core.date.DateUtil;
import com.company.cbf.starter.data.entity.MqttData;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zd.sdq.entity.DeviceInfoExt;
import com.zd.sdq.entity.InclineForwardData;
import com.zd.sdq.entity.dto.incline.InclineHistoryResponse;
import com.zd.sdq.mapper.InclineForwardDataMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
@RequiredArgsConstructor
public class InclineForwardDataService {
    private final InclineForwardDataMapper mapper;
    private final ObjectMapper objectMapper;
    private final DataSource dataSource;

    @PostConstruct
    public void initTable() {
        // 仅初始化新增快照表，不启用全局 SQL 初始化或改动现有业务表。
        new ResourceDatabasePopulator(new ClassPathResource("db/create_incline_forward_table.sql"))
                .execute(dataSource);
    }

    /** 每天北京时间 03:00 清理入库超过 45 天的快照。 */
    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Shanghai")
    public void cleanupOldData() {
        // 与 create_time 的入库格式及时区一致，避免 SQLite 字符串比较出现 T 分隔符差异。
        String cutoffTime = LocalDateTime.now().minusDays(45)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        try {
            int deletedCount = mapper.deleteCreatedBefore(cutoffTime);
            log.info("清理入库超过45天的倾角采集数据完成，删除 {} 条，时间阈值: {}", deletedCount, cutoffTime);
        } catch (Exception e) {
            log.error("清理倾角采集历史数据失败，时间阈值: {}", cutoffTime, e);
        }
    }

    public void saveCollectedData(DeviceInfoExt device, String remoteDeviceId,
                                  InclineHistoryResponse.InclineRecord record, MqttData mqttData) {
        InclineForwardData snapshot = new InclineForwardData();
        snapshot.setDeviceCode(device.getDeviceCode());
        snapshot.setRemoteDeviceId(remoteDeviceId);
        snapshot.setDeviceType(device.getDeviceType().name());
        snapshot.setDataTime(record.getTm());
        snapshot.setSampleTime(mqttData.getSampleTime());
        snapshot.setCreateTime(DateUtil.now());
        try {
            snapshot.setPayload(objectMapper.writeValueAsString(mqttData.getValue()));
            snapshot.setRawData(objectMapper.writeValueAsString(record));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化采集数据失败，测点: " + device.getDeviceCode(), e);
        }
        if (mapper.insert(snapshot) != 1) {
            throw new IllegalStateException("保存采集数据失败，测点: " + device.getDeviceCode());
        }
    }
}
