# 雷达水位仪数据读取功能

## 概述

本模块提供了雷达水位仪数据的读取、解析和存储功能。支持从文件、字节数组和十六进制字符串中解析雷达水位仪数据。

## 功能特性

- 支持雷达水位仪数据文件监控
- 支持多种数据格式解析（文件、字节数组、十六进制字符串）
- 支持TCP客户端和服务器模式
- 支持发送查询指令和解析响应
- 数据有效性验证
- 异步数据保存
- 可配置的数据范围验证

## 主要组件

### 1. 数据实体类

- `RadarWaterLevelData`: 雷达水位仪数据实体类
  - `waterLevel`: 水位值 (mm)
  - `temperature`: 温度值 (°C)
  - `signalStrength`: 信号强度 (dB)
  - `status`: 设备状态
  - `location`: 设备位置
  - `collectTime`: 采集时间

### 2. 协议解析类

- `RadarWaterLevelProtocol`: 雷达水位仪协议解析类
  - 支持二进制数据解析
  - 自动转换为实体对象

### 3. 服务类

- `RadarWaterLevelService`: 雷达水位仪数据读取服务
  - 文件数据解析
  - 字节数据解析
  - 十六进制字符串解析
  - 数据保存
  - 数据验证

- `RadarWaterLevelFileMonitor`: 雷达水位仪文件监控服务
  - 自动监控指定目录
  - 文件变化检测
  - 自动数据解析和保存

### 4. 配置类

- `RadarWaterLevelConfig`: 雷达水位仪配置类
  - 监控间隔配置
  - 监控路径配置
  - 数据范围配置
  - TCP连接配置

### 5. TCP服务类

- `RadarWaterLevelTcpClient`: 雷达水位仪TCP客户端
  - 连接雷达水位仪设备
  - 发送查询指令
  - 解析响应数据
  - 定时查询功能

- `RadarWaterLevelTcpServer`: 雷达水位仪TCP服务器
  - 模拟雷达水位仪设备
  - 处理客户端请求
  - 生成模拟数据
  - 多客户端支持

## 使用方法

### 1. 配置文件

在 `application.yml` 中添加配置：

```yaml
radar:
  water:
    level:
      monitor:
        interval: 1000  # 监控间隔(毫秒)
        path: ./data/radar  # 监控路径
      file-extension: .dat  # 数据文件扩展名
      min-water-level: -10000  # 最小水位值(mm)
      max-water-level: 10000   # 最大水位值(mm)
      min-temperature: -50      # 最小温度值(°C)
      max-temperature: 100      # 最大温度值(°C)
      min-signal-strength: -100 # 最小信号强度(dB)
      max-signal-strength: 0    # 最大信号强度(dB)
      tcp:
        host: localhost  # TCP客户端目标主机
        port: 4023       # TCP客户端目标端口
        timeout: 5000    # TCP连接超时时间(毫秒)
        query-interval: 60000  # 定时查询间隔(毫秒)
        server:
          port: 4024     # TCP服务器监听端口
          max-connections: 10  # 最大连接数
```

### 2. 数据解析示例

```java
@Autowired
private RadarWaterLevelService radarWaterLevelService;

// 解析文件
List<RadarWaterLevelData> dataList = radarWaterLevelService.parseDataFile("path/to/data.dat");

// 解析十六进制字符串
RadarWaterLevelData data = radarWaterLevelService.parseHexString("010203040506...");

// 解析字节数组
byte[] bytes = ...;
RadarWaterLevelData data = radarWaterLevelService.parseDataBytes(bytes);

// 验证数据
boolean isValid = radarWaterLevelService.validateData(data);

// 保存数据
radarWaterLevelService.saveData(data);
radarWaterLevelService.saveBatchData(dataList);
```

### 3. TCP客户端使用

```java
@Autowired
private RadarWaterLevelTcpClient tcpClient;

// 连接设备
boolean connected = tcpClient.connect();

// 查询水位数据
RadarWaterLevelData data = tcpClient.queryWaterLevel();

// 发送自定义指令
byte[] response = tcpClient.sendCustomCommand("010300000001840A");

// 检查连接状态
boolean isConnected = tcpClient.isConnected();

// 断开连接
tcpClient.disconnect();
```

### 4. TCP服务器使用

```java
@Autowired
private RadarWaterLevelTcpServer tcpServer;

// 启动服务器
tcpServer.start();

// 检查服务器状态
boolean isRunning = tcpServer.isRunning();

// 停止服务器
tcpServer.stop();
```

### 5. REST API使用

```bash
# 查询水位数据
POST /api/radar-water-level/query

# 发送自定义指令
POST /api/radar-water-level/send-command?hexCommand=010300000001840A

# 启动TCP客户端
POST /api/radar-water-level/client/start

# 启动TCP服务器
POST /api/radar-water-level/server/start

# 获取连接状态
GET /api/radar-water-level/client/status
GET /api/radar-water-level/server/status
```

## 数据格式

### 二进制数据格式

雷达水位仪数据采用二进制格式，包含以下字段：

1. 头部信息 (30字节)
   - 设备ID (14字节)
   - 设备类型 (1字节)
   - 数据包计数 (2字节)
   - 数据包索引 (2字节)
   - 其他头部信息 (11字节)

2. 数据体
   - 水位值 (4字节浮点数)
   - 温度值 (4字节浮点数)
   - 信号强度 (4字节浮点数)
   - 设备状态 (2字节整数)
   - 设备位置 (字符串，最多32字节)
   - 采集时间 (4字节时间戳)

### 十六进制字符串格式

数据以十六进制字符串形式传输，需要先转换为字节数组再解析。

### TCP通信协议

#### 查询指令格式
- 设备地址 (1字节)
- 功能码 (1字节) - 0x03表示查询水位
- 数据长度 (2字节)
- 数据内容 (N字节)
- CRC校验 (2字节)

#### 响应数据格式
- 设备地址 (1字节)
- 功能码 (1字节)
- 数据长度 (1字节)
- 水位数据 (4字节浮点数)
- 温度数据 (4字节浮点数)
- CRC校验 (2字节)

## 错误处理

- 数据长度不足时会记录错误日志
- 数据范围超出配置范围时会记录警告日志
- 解析失败时会记录详细错误信息
- 文件不存在时会记录错误日志

## 日志配置

系统会记录以下类型的日志：

- INFO: 数据解析成功、文件处理成功
- WARN: 数据验证失败、数据范围异常
- ERROR: 解析失败、文件不存在、保存失败

## 测试

运行测试类 `RadarWaterLevelTest` 来验证功能：

```bash
mvn test -Dtest=RadarWaterLevelTest
```

## 注意事项

1. 确保监控目录存在且有读写权限
2. 数据文件格式必须符合协议规范
3. 配置的数据范围要符合实际设备参数
4. 建议定期清理已处理的文件
5. 监控服务启动后会自动处理新文件 