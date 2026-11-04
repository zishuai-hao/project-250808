package com.zd.sdq.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zd.sdq.entity.WaterLevelData;
import com.zd.sdq.entity.dto.DeviceReportSample;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 水位数据Mapper接口
 * 
 * @author hzs
 * @date 2025/12/05
 */
@Mapper
public interface WaterLevelDataMapper extends BaseMapper<WaterLevelData> {
    @Select("""
            SELECT s.* FROM water_level_data s
            WHERE s.device_code = #{deviceCode} AND s.data_time >= #{fromTime} AND s.data_time <= #{toTime}
                AND NOT EXISTS (
                    SELECT 1 FROM water_level_data newer
                    WHERE newer.device_code = s.device_code AND newer.data_time = s.data_time AND newer.id > s.id
                )
            ORDER BY s.data_time DESC, s.id DESC LIMIT #{limit}
            """)
    List<WaterLevelData> selectObservationHistory(@Param("deviceCode") String deviceCode,
                                                 @Param("fromTime") String fromTime,
                                                 @Param("toTime") String toTime,
                                                 @Param("limit") int limit);

    // 老记录没有原始水位快照，不能使用当前基准倒推，因此不参与上报。
    @Select("""
            SELECT s.device_code, d.device_type, d.mqtt_gateway_id, s.data_time, s.payload
            FROM water_level_data s
            JOIN s_device_info_ext d ON d.device_code = s.device_code
            WHERE d.enable = 1 AND d.device_type = 'WLV' AND s.payload IS NOT NULL
                AND s.data_time >= #{fromTime} AND s.data_time <= #{toTime}
                AND NOT EXISTS (
                    SELECT 1 FROM water_level_data newer
                    WHERE newer.device_code = s.device_code AND newer.payload IS NOT NULL
                        AND newer.data_time <= #{toTime}
                        AND (newer.data_time > s.data_time
                            OR (newer.data_time = s.data_time AND newer.id > s.id))
                )
            ORDER BY s.device_code
            """)
    List<DeviceReportSample> selectLatestForReport(@Param("fromTime") String fromTime,
                                                  @Param("toTime") String toTime);

    
    /**
     * 删除指定时间之前的数据
     * 
     * @param beforeTime 时间阈值
     * @return 删除的记录数
     */
    int deleteByDataTimeBefore(@Param("beforeTime") LocalDateTime beforeTime);
}

