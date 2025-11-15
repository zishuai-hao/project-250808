package com.zd.sdq.entity.dto.incline;

import lombok.Data;

/**
 * 倾角历史数据查询请求
 * @author hzs
 * @date 2025/11/13
 */
@Data
public class InclineHistoryRequest {
    
    /**
     * 传感器编号
     */
    private String sn;
    
    /**
     * 设备类型，如：1051
     */
    private String tp;
    
    /**
     * 数据类型：qj-倾角数据
     */
    private String tpc = "qj";
    
    /**
     * 开始时间，格式：yyyy-MM-dd HH:mm:ss
     */
    private String startTm;
    
    /**
     * 结束时间，格式：yyyy-MM-dd HH:mm:ss
     */
    private String endTm;
    
    /**
     * 排序方式：asc-升序，desc-降序
     */
    private String sortMode = "desc";
    
    /**
     * 每页记录数
     */
    private Integer pagesize = 10;
    
    /**
     * 当前页码
     */
    private Integer curpagenum = 1;
    
    public InclineHistoryRequest() {
    }
    
    public InclineHistoryRequest(String sn, String tp, String startTm, String endTm) {
        this.sn = sn;
        this.tp = tp;
        this.startTm = startTm;
        this.endTm = endTm;
    }
}

