package com.uav.heartbeat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 第 2 步演示入口：
 * 同一个进程里同时扮演“设备仿真(生产者)”和“平台接入(消费者)”，
 * 验证 Kafka 心跳消息的生产-消费链路是否打通。
 */
@SpringBootApplication
@EnableKafka          // 开启 @KafkaListener
@EnableScheduling     // 开启 @Scheduled 定时
public class HeartbeatDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(HeartbeatDemoApplication.class, args);
    }
}
