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

倾角固定基准：

- 在 `/device-status.html` 的 INC 设备行点击“设置基准”，可手动填写 X/Y 基准，或点击“使用最新原始值”后保存。界面会显示所选原始数据的时间和状态，便于核对后设置。
- 基准按 INC 设备编号独立保存，单位为度。设置后，后续转发及状态页 `angleX`、`angleY` 使用“原始角度 − 对应轴基准”；未设置或清除基准时使用原始角度。温度、VIB、水位不参与此扣减。
- 状态页的 `rawAngleX`、`rawAngleY` 保留原始值；发送前快照的 `raw_data` 也保留原始记录，`payload` 留存实际转发的扣减结果。原始记录不会被改写，基准不会随每次采集自动滚动。
- 基准保存在现有设备表 `s_device_info_ext` 的 `incline_baseline` 字段中，SQLite 使用 `TEXT` 存储 JSON。应用启动时自动补充该字段，不修改原有水位 `baseline` 字段。每次采集读取最新基准，无需重启；修改后从下一条采集数据生效，不重算历史记录。
- 每台设备的一行保存该设备的基准，字段为 `NULL` 时不扣减。JSON 包含 `angleX`、`angleY` 和 `updatedAt`（UTC 设置时间），例如：

```json
{
  "angleX": 1.5,
  "angleY": -2.56,
  "updatedAt": "2026-11-01T00:00:00Z"
}
```

接口（仅已配置的 INC 设备允许设置或清除）：

- `GET /api/incline-baselines`：查询全部固定基准。
- `PUT /api/incline-baselines/{deviceCode}`，JSON 请求体 `{"angleX":1.5,"angleY":-2.56}`：创建或替换该设备的基准，两个轴均必填，零值有效。
- `DELETE /api/incline-baselines/{deviceCode}`：清除该设备基准，后续恢复转发原始角度。
