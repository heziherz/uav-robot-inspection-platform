package com.uav.devicesimulator.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 全局共享的调度线程池。
 *
 * 为什么必须共享（性能压测发现的头号设备侧瓶颈）：
 *   改造前每台设备在构造时各自 new 一个 4 线程池
 *   （DeviceSimulator 里的 Executors.newScheduledThreadPool(4)），
 *   于是线程数 = 设备数 × 4 —— 1000 台设备就是约 4000 个平台线程，
 *   光是线程栈就要 4 GB 虚拟内存，上下文切换开销先把机器拖垮，
 *   而不是 Kafka 或后端先到瓶颈。
 *
 *   改成共享池后，线程数恒为常数（与设备数无关），
 *   设备变多只是池内任务变多。
 *
 * ⚠ 关键点：这里创建的线程必须是**非守护线程**。
 *   本工程不是 Web 应用（不开端口），JVM 的存活完全依赖这些调度线程。
 *   一旦线程全变成守护线程，主线程结束后 JVM 会立刻退出，仿真直接停摆。
 */
@Configuration
public class SimulatorSchedulerConfig {

    private static final Logger log = LoggerFactory.getLogger(SimulatorSchedulerConfig.class);

    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService deviceScheduler(SimulatorSettings settings) {
        int threads = settings.getSchedulerThreads() > 0
                ? settings.getSchedulerThreads()
                : Math.max(4, Runtime.getRuntime().availableProcessors());

        AtomicInteger seq = new AtomicInteger();

        ScheduledExecutorService pool = Executors.newScheduledThreadPool(threads, runnable -> {
            Thread t = new Thread(runnable, "sim-sched-" + seq.getAndIncrement());
            // 非守护：保证有设备任务在跑时 JVM 不会退出（见类注释的 ⚠）
            t.setDaemon(false);
            return t;
        });

        log.info("共享调度池已创建：{} 个线程（设备数 {} 台不受其影响）",
                threads, settings.getTotalDevices());
        return pool;
    }
}
