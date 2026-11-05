CREATE TABLE IF NOT EXISTS incline_forward_data (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_code VARCHAR(100) NOT NULL,
    remote_device_id VARCHAR(100) NOT NULL,
    device_type VARCHAR(20) NOT NULL,
    data_time DATETIME NOT NULL,
    sample_time BIGINT NOT NULL,
    payload TEXT NOT NULL,
    raw_data TEXT NOT NULL,
    create_time DATETIME NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_incline_forward_device_time
    ON incline_forward_data(device_code, data_time);
CREATE INDEX IF NOT EXISTS idx_incline_forward_sn_time
    ON incline_forward_data(remote_device_id, data_time);
CREATE INDEX IF NOT EXISTS idx_incline_forward_create_time
    ON incline_forward_data(create_time);

-- 记录已确认丢弃的振动点，滚动回查和重启时不能将其重新当作缺口补录。
CREATE TABLE IF NOT EXISTS incline_vibration_filtered (
    device_code TEXT NOT NULL,
    remote_device_id TEXT NOT NULL,
    data_time TEXT NOT NULL,
    PRIMARY KEY (device_code, remote_device_id, data_time)
);
