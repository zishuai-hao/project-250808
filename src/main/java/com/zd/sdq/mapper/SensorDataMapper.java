package com.zd.sdq.mapper;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.zd.sdq.entity.SensorData;
import com.zd.sdq.entity.dto.SensorDataDTO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @author hzs
 * @date 2023/12/18
 */
public interface SensorDataMapper extends BaseMapper<SensorData> {

    List<SensorDataDTO> listData(@Param(Constants.WRAPPER) QueryWrapper<SensorDataDTO> wrapper);

    List<SensorDataDTO> listWsdjData(@Param(Constants.WRAPPER) QueryWrapper<SensorDataDTO> between);
}
