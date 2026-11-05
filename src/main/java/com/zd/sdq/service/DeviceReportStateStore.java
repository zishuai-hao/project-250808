package com.zd.sdq.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.UUID;

@Service
public class DeviceReportStateStore {
    public enum Source {
        INCLINE("incline_forward_data"), WATER("water_level_data");
        private final String table;
        Source(String table) { this.table = table; }
    }
    private final JdbcTemplate jdbc;

    public DeviceReportStateStore(DataSource source) { jdbc = new JdbcTemplate(source); }

    /** 保留旧记录和旧快照；旧版本没有可验证的发送结果，初始为 PENDING。 */
    public static void migrate(DataSource source, Source table) {
        var jdbc = new JdbcTemplate(source);
        var existing = jdbc.queryForList("PRAGMA table_info(" + table.table + ")").stream()
                .map(column -> column.get("name")).toList();
        var columns = new LinkedHashMap<String, String>();
        columns.put("report_status", "TEXT NOT NULL DEFAULT 'PENDING'");
        columns.put("report_time", "TEXT");
        columns.put("report_attempts", "INTEGER NOT NULL DEFAULT 0");
        columns.put("report_attempted_at", "BIGINT");
        columns.put("report_sent_at", "BIGINT");
        columns.put("report_error", "TEXT");
        columns.put("report_token", "TEXT");
        columns.forEach((name, definition) -> {
            if (!existing.contains(name)) jdbc.execute("ALTER TABLE " + table.table + " ADD COLUMN " + name + " " + definition);
        });
    }

    /** 按测点、传感器和小时原子校验最新值并抢占；过期 SENDING 两分钟后可恢复。 */
    public String claim(Source source, long id, String reportTime, long now) {
        String token = UUID.randomUUID().toString();
        String samePoint = source == Source.INCLINE
                ? "peer.device_code = candidate.device_code AND peer.remote_device_id = candidate.remote_device_id AND peer.device_type = candidate.device_type"
                : "peer.device_code = candidate.device_code AND peer.payload IS NOT NULL";
        // 查询候选与发送之间可能插入迟到数据，必须在同一条 UPDATE 中重新判断。
        // 已成功的同时间记录也拦截，不能只依靠行 ID 去重。
        int count = jdbc.update("""
                UPDATE %s AS candidate
                 SET report_status = 'SENDING', report_time = ?, report_attempts = report_attempts + 1,
                     report_attempted_at = ?, report_error = NULL, report_token = ?
                 WHERE candidate.id = ?
                     AND candidate.data_time > datetime(?, '-1 hour') AND candidate.data_time <= ?
                     AND (candidate.report_status IN ('PENDING', 'FAILED')
                         OR (candidate.report_status = 'SENDING' AND candidate.report_attempted_at <= ?))
                     AND NOT EXISTS (
                         SELECT 1 FROM %s peer
                         WHERE %s
                             AND peer.data_time > datetime(?, '-1 hour') AND peer.data_time <= ?
                             AND (
                                 peer.data_time > candidate.data_time
                                 OR (peer.data_time = candidate.data_time AND peer.id > candidate.id)
                                 OR (peer.report_status = 'SENT' AND peer.data_time >= candidate.data_time)
                                 OR (peer.id <> candidate.id AND peer.report_status = 'SENDING'
                                     AND peer.report_attempted_at > ?)
                             )
                     )
                """.formatted(source.table, source.table, samePoint),
                reportTime, now, token, id, reportTime, reportTime, now - 120_000,
                reportTime, reportTime, now - 120_000);
        return count == 1 ? token : null;
    }

    public void complete(Source source, long id, String token, String error, long now) {
        jdbc.update("UPDATE " + source.table + """
                 SET report_status = ?, report_sent_at = ?, report_error = ?, report_token = NULL
                 WHERE id = ? AND report_status = 'SENDING' AND report_token = ?
                """, error == null ? "SENT" : "FAILED", error == null ? now : null,
                error == null ? null : error.substring(0, Math.min(error.length(), 1000)), id, token);
    }
}
