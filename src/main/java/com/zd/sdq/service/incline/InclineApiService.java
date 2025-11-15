package com.zd.sdq.service.incline;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zd.sdq.config.InclineApiConfig;
import com.zd.sdq.entity.dto.incline.*;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 倾角传感器 API 服务类
 * @author hzs
 * @date 2025/11/13
 */
@Slf4j
@Service
@Data
@RequiredArgsConstructor
public class InclineApiService {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final InclineApiConfig config;
    private  OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    /**
     * 当前登录的 token
     */
    private String loginToken;

    @PostConstruct
    private void start() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }
    
    /**
     * 用户登录
     * @param accountName 用户名
     * @param password 密码
     * @return 登录响应
     * @throws IOException IO异常
     */
    public LoginResponse login(String accountName, String password) throws IOException {
        LoginRequest request = new LoginRequest(accountName, password);
        String json = objectMapper.writeValueAsString(request);
        
        RequestBody body = RequestBody.create(json, JSON);
        Request httpRequest = new Request.Builder()
                .url(config.getLoginUrl())
                .post(body)
                .build();
        
        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("登录失败: " + response);
            }

            assert response.body() != null;
            String responseBody = response.body().string();
            LoginResponse loginResponse = objectMapper.readValue(responseBody, LoginResponse.class);
            
            // 保存 token
            if (loginResponse.getSuccess() && loginResponse.getData() != null) {
                this.loginToken = loginResponse.getData().getLoginToken();
                log.info("登录成功，Token: {}", this.loginToken);
            }
            
            return loginResponse;
        }
    }
    
    /**
     * 获取传感器列表
     * @param request 查询请求参数
     * @return 传感器列表响应
     * @throws IOException IO异常
     */
    public SensorListResponse getSensorList(SensorListRequest request) throws IOException {
        if (StrUtil.isBlank(loginToken)) {
            throw new IllegalStateException("请先登录获取 token");
        }
        
        String json = objectMapper.writeValueAsString(request);
        
        RequestBody body = RequestBody.create(json, JSON);
        Request httpRequest = new Request.Builder()
                .url(config.getSensorListUrl())
                .addHeader("Authorization", loginToken)
                .post(body)
                .build();
        
        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("获取传感器列表失败: " + response);
            }

            assert response.body() != null;
            String responseBody = response.body().string();
            return objectMapper.readValue(responseBody, SensorListResponse.class);
        }
    }
    
    /**
     * 获取倾角历史数据
     * @param request 查询请求参数
     * @return 历史数据响应
     * @throws IOException IO异常
     */
    public InclineHistoryResponse getHistoryData(InclineHistoryRequest request) throws IOException {
        if (StrUtil.isBlank(loginToken)) {
            throw new IllegalStateException("请先登录获取 token");
        }
        
        String json = objectMapper.writeValueAsString(request);
        
        RequestBody body = RequestBody.create(json, JSON);
        Request httpRequest = new Request.Builder()
                .url(config.getHistoryDataUrl())
                .addHeader("Authorization", loginToken)
                .post(body)
                .build();
        
        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("获取历史数据失败: " + response);
            }

            assert response.body() != null;
            String responseBody = response.body().string();
            return objectMapper.readValue(responseBody, InclineHistoryResponse.class);
        }
    }
    
    /**
     * 自动刷新 Token 定时任务
     * 根据配置的刷新间隔定期刷新 token
     */
    @Scheduled(fixedRateString = "#{@inclineApiConfig.tokenRefreshInterval * 60000}", initialDelay = 60000)
    public void refreshTokenScheduled() {
        try {
            log.info("开始自动刷新倾角传感器 API Token...");
            LoginResponse response = login(config.getUsername(), config.getPassword());
            
            if (response.getSuccess()) {
                log.info("倾角传感器 API Token 刷新成功");
            } else {
                log.error("倾角传感器 API Token 刷新失败: {}", response.getMsg());
            }
        } catch (Exception e) {
            log.error("自动刷新倾角传感器 API Token 时发生异常", e);
        }
    }
    
    /**
     * 手动刷新 Token
     * @return 是否刷新成功
     */
    public boolean refreshToken() {
        try {
            LoginResponse response = login(config.getUsername(), config.getPassword());
            return response.getSuccess();
        } catch (IOException e) {
            log.error("手动刷新 Token 失败", e);
            return false;
        }
    }

}

