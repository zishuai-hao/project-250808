package com.zd.sdq.entity.dto.incline;

import lombok.Data;

import java.util.List;

/**
 * 传感器列表响应结果
 * @author hzs
 * @date 2025/11/13
 */
@Data
public class SensorListResponse {
    
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
    private SensorListData data;
    
    @Data
    public static class SensorListData {
        /**
         * 传感器记录列表
         */
        private List<SensorRecord> records;
        
        /**
         * 总记录数
         */
        private Integer total;
        
        /**
         * 每页记录数
         */
        private Integer size;
        
        /**
         * 当前页码
         */
        private Integer current;
        
        /**
         * 总页数
         */
        private Integer pages;
        
        private List<Object> orders;
        private Boolean optimizeCountSql;
        private Boolean hitCount;
        private Object countId;
        private Object maxLimit;
        private Boolean searchCount;
    }
    
    @Data
    public static class SensorRecord {
        /**
         * 传感器编号
         */
        private String sn;
        
        /**
         * 设备类型代码
         */
        private Integer tpCode;
    }
}

