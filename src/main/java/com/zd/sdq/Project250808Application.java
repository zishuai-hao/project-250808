package com.zd.sdq;


import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;


/**
 * SDQ数据推送应用
 *
 * @author hzs
 * @date 2023/11/29
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties
@Slf4j
public class Project250808Application {
    public static void main(String[] args) {
        SpringApplication.run(Project250808Application.class, args);
    }
}
