package com.zd.sdq.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zd.sdq.entity.InclineForwardData;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface InclineForwardDataMapper extends BaseMapper<InclineForwardData> {
    @Delete("DELETE FROM incline_forward_data WHERE create_time < #{beforeTime}")
    int deleteCreatedBefore(@Param("beforeTime") String beforeTime);
}
