package com.zd.sdq.entity.dto.incline;

import lombok.Data;

/**
 * 登录响应结果
 * @author hzs
 * @date 2025/11/13
 */
@Data
public class LoginResponse {
    
    /**
     * 状态码
     */
    private Integer status;
    
    /**
     * 消息
     */
    private String msg;
    
    /**
     * 是否成功
     */
    private Boolean success;
    
    /**
     * 数据
     */
    private LoginData data;
    
    @Data
    public static class LoginData {
        /**
         * 结果
         */
        private String result;
        
        /**
         * 登录令牌
         */
        private String loginToken;
    }
}

