package com.zd.sdq.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zd.sdq.entity.DeviceInfoExt;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DeviceInfoExtMapper extends BaseMapper<DeviceInfoExt> {
    @Select("SELECT incline_baseline FROM s_device_info_ext WHERE device_code = #{deviceCode} AND device_type = 'INC'")
    String getInclineBaselineJson(@Param("deviceCode") String deviceCode);

    @Update("UPDATE s_device_info_ext SET incline_baseline = #{json} WHERE device_code = #{deviceCode} AND device_type = 'INC'")
    int updateInclineBaselineJson(@Param("deviceCode") String deviceCode, @Param("json") String json);
}
