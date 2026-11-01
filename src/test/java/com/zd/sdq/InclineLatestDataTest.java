package com.zd.sdq;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zd.sdq.config.InclineApiConfig;
import com.zd.sdq.entity.dto.incline.InclineHistoryRequest;
import com.zd.sdq.entity.dto.incline.InclineHistoryResponse;
import com.zd.sdq.entity.dto.incline.LoginResponse;
import com.zd.sdq.service.incline.InclineApiService;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 手动查询真实接口，不启动 Spring 应用、数据库、TCP 监听或 MQTT 转发。
 *
 * <pre>
 * mvn -Dtest=InclineLatestDataTest -Dincline.live=true test
 * mvn -Dtest=InclineLatestDataTest -Dincline.live=true \
 *     -Dincline.sn=1524020747 -Dincline.hours=72 -Dincline.limit=50 test
 * </pre>
 * 默认读取 application.yml 中的接口配置；可通过 INCLINE_API_BASE_URL、
 * INCLINE_API_USERNAME、INCLINE_API_PASSWORD 环境变量覆盖。
 * hours 为查询窗口，limit 为倒序查询的最新记录条数，仅查询第一页。
 */
@EnabledIfSystemProperty(named = "incline.live", matches = "true")
public class InclineLatestDataTest {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Test
    void queryLatestData() throws Exception {
        String sn = System.getProperty("incline.sn", "1524020752").trim();
        assertTrue(sn.matches("[0-9]+"), "incline.sn 必须是数字传感器编号");
        int hours = Integer.parseInt(System.getProperty("incline.hours", "24"));
        int limit = Integer.parseInt(System.getProperty("incline.limit", "20"));
        assertTrue(hours > 0, "incline.hours 必须大于 0");
        assertTrue(limit > 0, "incline.limit 必须大于 0");

        InclineApiConfig config = loadConfig();
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(config.getConnectTimeout(), TimeUnit.SECONDS)
                .readTimeout(config.getReadTimeout(), TimeUnit.SECONDS)
                .writeTimeout(config.getWriteTimeout(), TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .build();
        InclineApiService api = new InclineApiService(config, mapper);
        api.setHttpClient(client);

        // 现有服务会打印登录密码和 token；此测试不输出这两类日志。
        Logger serviceLogger = (Logger) LoggerFactory.getLogger(InclineApiService.class);
        Logger httpLogger = (Logger) LoggerFactory.getLogger("httpDataLog");
        Level serviceLevel = serviceLogger.getLevel();
        Level httpLevel = httpLogger.getLevel();
        serviceLogger.setLevel(Level.OFF);
        httpLogger.setLevel(Level.OFF);
        try {
            LoginResponse login = api.login(config.getUsername(), config.getPassword());
            assertNotNull(login, "登录响应为空");
            assertEquals(Boolean.TRUE, login.getSuccess(), "接口登录失败");
            assertNotNull(api.getLoginToken(), "登录成功但未返回 token");
            assertFalse(api.getLoginToken().isBlank(), "登录成功但 token 为空");

            LocalDateTime end = LocalDateTime.now(ZONE).truncatedTo(ChronoUnit.SECONDS);
            LocalDateTime start = end.minusHours(hours);
            InclineHistoryRequest request = new InclineHistoryRequest(
                    sn, "1051", start.format(TIME_FORMAT), end.format(TIME_FORMAT));
            request.setSortMode("desc");
            request.setPagesize(limit);
            request.setCurpagenum(1);
            System.out.printf("SN=%s，查询范围=%s ~ %s（Asia/Shanghai），最新 %d 条%n",
                    sn, request.getStartTm(), request.getEndTm(), limit);

            InclineHistoryResponse response = api.getHistoryData(request);
            assertNotNull(response, "历史数据响应为空");
            assertEquals(Integer.valueOf(1), response.getStatus(), "历史数据接口返回失败");
            assertNotNull(response.getData(), "历史数据响应缺少 data");
            Path output = Path.of("target", "incline-latest", sn + ".json");
            Files.createDirectories(output.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), response);
            System.out.println("查询结果已保存：" + output.toAbsolutePath());

            List<InclineHistoryResponse.InclineRecord> records = response.getData().getResultData();
            assertNotNull(records, "历史数据响应缺少 ResultData");
            assertFalse(records.isEmpty(), "查询窗口内无数据，可增大 -Dincline.hours 或检查 SN");
            System.out.printf("窗口内总记录数=%s，本次返回=%d%n", response.getData().getCount(), records.size());
            System.out.println("采集时间,X角度(°),Y角度(°),Z角度(°),温度(℃),accMaxZ(接口值),accMaxZ(m/s²，按mG换算),零点模式,运行状态,报警状态");
            LocalDateTime previous = end;
            for (InclineHistoryResponse.InclineRecord record : records) {
                assertNotNull(record.getTm(), "记录缺少采集时间");
                LocalDateTime time = LocalDateTime.parse(record.getTm(), TIME_FORMAT);
                assertFalse(time.isBefore(start) || time.isAfter(end), "返回数据不在请求时间范围内：" + record.getTm());
                assertFalse(time.isAfter(previous), "接口未按采集时间倒序返回：" + record.getTm());
                previous = time;
                String acceleration = record.getAccMaxZ() == null ? "null"
                        : String.format(Locale.ROOT, "%.6f", record.getAccMaxZ() * 0.00980665);
                System.out.printf("%s,%s,%s,%s,%s,%s,%s,%s,%s,%s%n",
                        record.getTm(), record.getAngleX(), record.getAngleY(), record.getAngleZ(),
                        record.getTemp(), record.getAccMaxZ(), acceleration,
                        record.getZero(), record.getRunStatus(), record.getAlarmStatus());
            }
        } finally {
            serviceLogger.setLevel(serviceLevel);
            httpLogger.setLevel(httpLevel);
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdown();
        }
    }

    private InclineApiConfig loadConfig() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = Objects.requireNonNull(yaml.getObject(), "无法读取 application.yml");
        InclineApiConfig config = new InclineApiConfig();
        config.setBaseUrl(environmentOrProperty("INCLINE_API_BASE_URL", properties, "incline.api.base-url"));
        config.setUsername(environmentOrProperty("INCLINE_API_USERNAME", properties, "incline.api.username"));
        config.setPassword(environmentOrProperty("INCLINE_API_PASSWORD", properties, "incline.api.password"));
        return config;
    }

    private String environmentOrProperty(String name, Properties properties, String key) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            value = properties.getProperty(key);
        }
        assertNotNull(value, "缺少配置：" + key);
        assertFalse(value.isBlank(), "配置为空：" + key);
        return value;
    }
}
