package com.zd.sdq.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zd.sdq.entity.MqttClientConfigEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MqttConfigEntityMapper extends BaseMapper<MqttClientConfigEntity> {
}
