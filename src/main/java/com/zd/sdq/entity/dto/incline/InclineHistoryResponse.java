package com.zd.sdq.entity.dto.incline;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 倾角历史数据响应结果
 * @author hzs
 * @date 2025/11/13
 */
@Data
public class InclineHistoryResponse {
    
    /**
     * 状态码
     */
    private Integer status;
    
    /**
     * 消息
     */
    private String msg;
    
    /**
     * 数据
     */
    private InclineHistoryData data;
    
    @Data
    public static class InclineHistoryData {
        /**
         * 历史数据记录列表
         */
        @JsonProperty("ResultData")
        private List<InclineRecord> resultData;
        
        /**
         * 数据类型：qj-倾角数据
         */
        private String tpc;
        
        /**
         * 总记录数
         */
        private Integer count;
        
        /**
         * 每页记录数
         */
        private Integer pagesize;
        
        /**
         * 当前页码
         */
        private Integer curpagenum;
        
        /**
         * 设备类型
         */
        private String tp;
    }
    
    /**
     * 倾角数据记录
     */
    @Data
    public static class InclineRecord {
        /**
         * 时间
         */
        private String tm;
        
        /**
         * X方向角度
         */
        private Double angleX;
        
        /**
         * Y方向角度
         */
        private Double angleY;
        
        /**
         * Z方向角度
         */
        private Double angleZ;
        
        /**
         * 合倾角：XY轴所形成的平面与水平面的夹角
         */
        private Double angleXyz;
        
        /**
         * 方向角：倾斜合方向与X轴正方向夹角
         */
        private Double trend;
        
        /**
         * 方位角：X轴在水平面的投影与磁北的夹角
         */
        private Double azi;
        
        /**
         * 重力加速度在X轴分量
         */
        private Double accX;
        
        /**
         * 重力加速度在Y轴分量
         */
        private Double accY;
        
        /**
         * 重力加速度在Z轴分量
         */
        private Double accZ;
        
        /**
         * X轴加速度最大值：一个采样周期内X轴方向加速度的最大变化量
         */
        private Double accMaxX;
        
        /**
         * Y轴加速度最大值：一个采样周期内Y轴方向加速度的最大变化量
         */
        private Double accMaxY;
        
        /**
         * Z轴加速度最大值：一个采样周期内Z轴方向加速度的最大变化量
         */
        private Double accMaxZ;
        
        /**
         * 传感器的温度
         */
        private Double temp;
        
        /**
         * 温度自适应使能
         */
        private Integer tempKalman;
        
        /**
         * 零点模式：0-绝对零点，1-相对零点
         */
        private Integer zero;
        
        /**
         * 方位/方向：0-trend，1-azi
         */
        private Integer aziOrTrend;
        
        /**
         * 设置方位角偏移角度
         */
        private Double aziOffset;
        
        /**
         * 角度阈值
         */
        private Double angleTh;
        
        /**
         * 加速度触发中断
         */
        private Integer accTrig;
        
        /**
         * 加速度触发阈值
         */
        private Integer accTh;
        
        /**
         * 加速度持续时间
         */
        private Integer accTimeTh;
        
        /**
         * 报警状态：0-正常，1-曾经有报警，2-加速度触发报警，3-角度触发加密报警
         */
        private Integer alarmStatus;
        
        /**
         * 运行状态：0-正常，1-故障
         */
        private Integer runStatus;
    }
}

