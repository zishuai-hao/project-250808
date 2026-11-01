-- 创建水位数据表
CREATE TABLE IF NOT EXISTS water_level_data (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_code VARCHAR(100) NOT NULL,
    water_level REAL NOT NULL,
    data_time DATETIME NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    payload TEXT
);

-- 创建索引以提高查询性能
CREATE INDEX IF NOT EXISTS idx_water_level_data_time ON water_level_data(data_time);
CREATE INDEX IF NOT EXISTS idx_water_level_device_code ON water_level_data(device_code);
CREATE INDEX IF NOT EXISTS idx_water_level_device_time ON water_level_data(device_code, data_time);

