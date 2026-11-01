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
