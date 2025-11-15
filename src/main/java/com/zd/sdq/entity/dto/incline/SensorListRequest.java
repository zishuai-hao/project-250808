package com.zd.sdq.entity.dto.incline;

import lombok.Data;

/**
 * 传感器列表查询请求
 * @author hzs
 * @date 2025/11/13
 */
@Data
public class SensorListRequest {
    
    /**
     * 每页记录数
     */
    private String pagesize = "10";
    
    /**
     * 当前页码
     */
    private String curpagenum = "1";
    
    /**
     * 传感器地址
     */
    private String snAddress = "";
    
    /**
     * 设备类型
     */
    private String tpCode = "";
    
    /**
     * 设备状态
     */
    private String devState = "";
    
    /**
     * 排序方式：desc-降序，asc-升序
     */
    private String sortMode = "desc";
    
    public SensorListRequest() {
    }
    
    public SensorListRequest(Integer pagesize, Integer curpagenum) {
        this.pagesize = String.valueOf(pagesize);
        this.curpagenum = String.valueOf(curpagenum);
    }
}

