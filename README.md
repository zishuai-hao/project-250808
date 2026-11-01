天津秦滨高速轻量化
程序基础框架：2天
设备信息导入以及转发协议配置：1天
设备数据解析：4天
程序部署调试：1天

CBXH-WLV-P01-001-02:41000:120300010001d769 x
JYHDQ-WLV-P01-001-02:41001:110300010001d75a ✅
DLJH-WLV-P01-001-02:41003:130300010001d6b8 x 
ZYXH-WLV-P01-001-02:41004:150300010001d6de x
YDXH-WLV-P01-001-02:41002:140300010001d70f ✅

倾角接口发送前数据留存：

- 启动时自动在现有 SQLite 数据库（默认 `./zd.db`）创建 `incline_forward_data` 表。
- INC、VIB 通道在进入转发缓冲区前分别保存一条快照。
- `device_code` 为测点，`remote_device_id` 为传感器 SN，`data_time` 为原始采集时间，`sample_time` 为转发构建时间戳，`create_time` 为入库时间。
- `payload` 是与转发 `push` 参数一致的 JSON，保存换算、舍入后的值及发送时间；`raw_data` 保存接口单条原始记录的 JSON。
- 入库失败时中止本轮转发，不推进该采集记录的查询进度，下一轮重试。部分通道已完成后其他通道失败，重试可能产生重复快照和转发。
- 快照表示发送前留存，不代表 MQTT 投递成功。每天北京时间 03:00 按 `create_time` 清理入库超过 45 天的快照，恰好位于阈值的记录保留。

```sql
SELECT device_code, remote_device_id, device_type, data_time, payload, raw_data
FROM incline_forward_data
ORDER BY id DESC
LIMIT 100;
```
