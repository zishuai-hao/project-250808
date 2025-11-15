package com.zd.sdq.entity.dto.incline;

import lombok.Data;

/**
 * 登录请求参数
 * @author hzs
 * @date 2025/11/13
 */
@Data
public class LoginRequest {
    
    /**
     * 平台登录用户名
     */
    private String accountName;
    
    /**
     * 平台登录对应的密码
     */
    private String password;
    
    /**
     * 验证码，默认1234
     */
    private String code = "1234";
    
    public LoginRequest() {
    }
    
    public LoginRequest(String accountName, String password) {
        this.accountName = accountName;
        this.password = password;
        this.code = "1234";
    }
}

