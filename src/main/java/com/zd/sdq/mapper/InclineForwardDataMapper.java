package com.zd.sdq.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zd.sdq.entity.InclineForwardData;
import com.zd.sdq.entity.dto.DeviceReportSample;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface InclineForwardDataMapper extends BaseMapper<InclineForwardData> {
    @Select("""
            SELECT * FROM incline_forward_data
            WHERE device_code = #{deviceCode} AND device_type = 'INC' AND data_time <= #{dataTime}
            ORDER BY data_time DESC, id DESC LIMIT 1
            """)
    InclineForwardData selectPreviousIncline(@Param("deviceCode") String deviceCode,
                                            @Param("dataTime") String dataTime);

    @Select("""
            SELECT s.device_code, s.device_type, s.remote_device_id, d.mqtt_gateway_id, s.data_time, s.payload
            FROM incline_forward_data s
            JOIN s_device_info_ext d ON d.device_code = s.device_code
                AND d.device_type = s.device_type AND d.remote_device_id = s.remote_device_id
            WHERE d.enable = 1 AND d.device_type IN ('INC', 'VIB')
                AND s.data_time >= #{fromTime} AND s.data_time <= #{toTime}
                AND NOT EXISTS (
                    SELECT 1 FROM incline_forward_data newer
                    WHERE newer.device_code = s.device_code
                        AND newer.device_type = s.device_type
                        AND newer.remote_device_id = s.remote_device_id
                        AND newer.data_time <= #{toTime}
                        AND (newer.data_time > s.data_time
                            OR (newer.data_time = s.data_time AND newer.id > s.id))
                )
            ORDER BY s.device_code
            """)
    List<DeviceReportSample> selectLatestForReport(@Param("fromTime") String fromTime,
                                                  @Param("toTime") String toTime);

    @Select("""
            SELECT s.* FROM incline_forward_data s
            WHERE s.device_code = #{deviceCode} AND s.remote_device_id = #{remoteDeviceId}
                AND s.device_type = #{deviceType}
                AND s.data_time >= #{fromTime} AND s.data_time <= #{toTime}
                AND NOT EXISTS (
                    SELECT 1 FROM incline_forward_data newer
                    WHERE newer.device_code = s.device_code AND newer.remote_device_id = s.remote_device_id
                        AND newer.device_type = s.device_type AND newer.data_time = s.data_time AND newer.id > s.id
                )
            ORDER BY s.data_time DESC, s.id DESC LIMIT #{limit}
            """)
    List<InclineForwardData> selectSimulationHistory(@Param("deviceCode") String deviceCode,
                                                    @Param("remoteDeviceId") String remoteDeviceId,
                                                    @Param("deviceType") String deviceType,
                                                    @Param("fromTime") String fromTime,
                                                    @Param("toTime") String toTime,
                                                    @Param("limit") int limit);

    @Delete("DELETE FROM incline_forward_data WHERE create_time < #{beforeTime}")
    int deleteCreatedBefore(@Param("beforeTime") String beforeTime);
}
