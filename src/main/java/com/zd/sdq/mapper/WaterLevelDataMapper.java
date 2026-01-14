package com.zd.sdq.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zd.sdq.entity.WaterLevelData;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 水位数据Mapper接口
 * 
 * @author hzs
 * @date 2025/12/05
 */
@Mapper
public interface WaterLevelDataMapper extends BaseMapper<WaterLevelData> {
    
    /**
     * 删除指定时间之前的数据
     * 
     * @param beforeTime 时间阈值
     * @return 删除的记录数
     */
    int deleteByDataTimeBefore(@Param("beforeTime") LocalDateTime beforeTime);
}

