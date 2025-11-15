# 倾角传感器 Token 自动刷新功能说明

## 功能概述

倾角传感器 API 服务已集成 Token 自动刷新功能，确保在长时间运行过程中 Token 始终保持有效，避免因 Token 过期导致的 API 调用失败。

## 功能特性

1. **自动刷新**：系统会根据配置的时间间隔自动刷新 Token
2. **可配置间隔**：可以在配置文件中自定义刷新间隔时间
3. **手动刷新**：提供手动刷新 Token 的方法，方便测试和特殊场景使用
4. **异常处理**：自动刷新失败时会记录错误日志，不影响系统正常运行
5. **启动延迟**：应用启动后 1 分钟才开始执行首次刷新，避免启动阶段资源竞争

## 配置说明

在 `application.yml` 配置文件中添加以下配置：

```yaml
incline:
  api:
    base-url: https://www.zhtk-iot.com:8543/api  # API 基础地址
    username: dxkj                                # 用户名
    password: dxkj250808                          # 密码
    connect-timeout: 30                           # 连接超时时间（秒）
    read-timeout: 30                              # 读取超时时间（秒）
    write-timeout: 30                             # 写入超时时间（秒）
    token-refresh-interval: 120                   # Token刷新间隔（分钟），默认2小时
```

### 配置参数说明

| 参数名称 | 类型 | 默认值 | 说明 |
|---------|------|--------|------|
| base-url | String | https://www.zhtk-iot.com:8543/api | API 服务的基础地址 |
| username | String | dxkj | 登录用户名 |
| password | String | dxkj250808 | 登录密码 |
| connect-timeout | Integer | 30 | HTTP 连接超时时间（秒） |
| read-timeout | Integer | 30 | HTTP 读取超时时间（秒） |
| write-timeout | Integer | 30 | HTTP 写入超时时间（秒） |
| token-refresh-interval | Integer | 120 | Token 自动刷新间隔（分钟） |

## 使用方式

### 1. 自动刷新（推荐）

系统启动后会自动执行 Token 刷新任务，无需手动干预。刷新日志示例：

```
2025-11-13 10:00:00.000 INFO  --- 开始自动刷新倾角传感器 API Token...
2025-11-13 10:00:01.234 INFO  --- 登录成功，Token: eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
2025-11-13 10:00:01.235 INFO  --- 倾角传感器 API Token 刷新成功
```

### 2. 手动刷新

在需要立即刷新 Token 的场景下，可以调用手动刷新方法：

```java
@Autowired
private InclineApiService inclineApiService;

public void refreshTokenManually() {
    boolean success = inclineApiService.refreshToken();
    if (success) {
        log.info("Token 刷新成功");
    } else {
        log.error("Token 刷新失败");
    }
}
```

### 3. 获取当前 Token

可以通过以下方式获取当前使用的 Token：

```java
String currentToken = inclineApiService.getLoginToken();
```

## 刷新策略

1. **定时刷新**：
   - 首次刷新：应用启动后 1 分钟
   - 后续刷新：按配置的 `token-refresh-interval` 间隔执行
   - 默认间隔：120 分钟（2 小时）

2. **刷新流程**：
   - 使用配置的用户名和密码调用登录接口
   - 获取新的 Token 并自动更新到内存
   - 后续 API 调用自动使用新的 Token

3. **异常处理**：
   - 刷新失败时记录错误日志
   - 不会中断正常的业务流程
   - 下次定时任务会继续尝试刷新

## 测试方法

项目提供了完整的测试用例，位于 `src/test/java/com/zd/sdq/InclineApiTest.java`：

### 1. 测试手动刷新

```bash
# 运行手动刷新测试
mvn test -Dtest=InclineApiTest#testRefreshToken
```

### 2. 测试自动刷新

启动应用后，观察日志输出，验证定时任务是否按预期执行。

## 注意事项

1. **配置安全性**：
   - 建议将敏感信息（用户名、密码）配置在环境变量或加密配置中
   - 不要将包含敏感信息的配置文件提交到版本控制系统

2. **刷新间隔设置**：
   - 建议设置的刷新间隔短于 Token 的实际过期时间
   - 过短的刷新间隔会增加 API 调用频率，建议不低于 30 分钟
   - 过长的刷新间隔可能导致 Token 过期，建议不超过 4 小时

3. **网络异常**：
   - 如果网络不稳定，可能导致刷新失败
   - 系统会在下次定时任务时自动重试
   - 建议关注日志中的异常信息

4. **并发安全**：
   - Token 存储在内存中，单实例应用无需考虑并发问题
   - 集群部署时，每个实例独立维护自己的 Token

## 日志监控

关键日志级别和内容：

| 日志级别 | 内容 | 说明 |
|---------|------|------|
| INFO | 开始自动刷新倾角传感器 API Token... | 刷新任务开始 |
| INFO | 登录成功，Token: xxx | 登录成功并获取到新 Token |
| INFO | 倾角传感器 API Token 刷新成功 | 刷新成功 |
| ERROR | 倾角传感器 API Token 刷新失败: xxx | 刷新失败（业务错误） |
| ERROR | 自动刷新倾角传感器 API Token 时发生异常 | 刷新失败（系统异常） |

## 版本历史

- **v1.0.0** (2025-11-13)
  - 初始版本
  - 支持自动刷新和手动刷新
  - 支持配置刷新间隔
  - 完整的异常处理和日志记录

## 技术实现

- **框架**：Spring Boot 3.x
- **定时任务**：Spring `@Scheduled` 注解
- **HTTP 客户端**：OkHttp 3.x
- **配置管理**：Spring `@ConfigurationProperties`

## 相关文件

- 配置类：`src/main/java/com/zd/sdq/config/InclineApiConfig.java`
- 服务类：`src/main/java/com/zd/sdq/service/incline/InclineApiService.java`
- 测试类：`src/test/java/com/zd/sdq/InclineApiTest.java`
- 配置文件：`src/main/resources/application.yml`

