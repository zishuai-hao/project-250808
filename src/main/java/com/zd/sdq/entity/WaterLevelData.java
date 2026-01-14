package com.zd.sdq.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 水位数据实体类
 * 
 * @author hzs
 * @date 2025/12/05
 */
@Data
@TableName(value = "water_level_data")
@NoArgsConstructor
@AllArgsConstructor
public class WaterLevelData {
    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * 设备编码
     */
    private String deviceCode;
    
    /**
     * 水位值（米）
     */
    private Double waterLevel;
    
    /**
     * 数据时间戳
     */
    private String dataTime;
    
    /**
     * 创建时间
     */
    private String createTime;
}

