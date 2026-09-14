package com.uav.platformservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * HDFS 上传服务。
 *
 * ⚠️ 演示方案：platform-service 在宿主机、HDFS 在容器，两者网络隔离，
 *    所以通过 `docker exec` 把文件内容经 stdin 送给容器内的 hdfs 命令。
 *    生产环境的正确做法：WebHDFS（REST）或 Hadoop Java 客户端。
 */
@Service
public class HdfsService {

    private static final Logger log = LoggerFactory.getLogger(HdfsService.class);
    private static final String CONTAINER = "hadoop-namenode";
    private static final String HDFS_BIN = "/opt/hadoop-3.2.1/bin/hdfs";
    private static final String BASE_DIR = "/uav/media";
    private static final DateTimeFormatter DAY= DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 上传本地文件到 HDFS。
     * HDFS 目录规范（契约 §7.1）：/uav/media/{deviceNo}/{yyyyMMdd}/{fileId}.jpg
     */
    public boolean upload(String localPath, String deviceNo, String fileId) {
        try {
            String day = java.time.LocalDate.now().format(DAY);
            String dir = BASE_DIR + "/" + deviceNo + "/" + day;
            String hdfsPath = dir + "/" + fileId + ".jpg";

            // ① 确保目录存在
            if (!execInContainer(HDFS_BIN, "dfs", "-mkdir", "-p", dir)) {
                log.error("[HDFS] 创建目录失败: {}", dir);
                return false;
            }

            // ② 上传：文件内容通过 stdin 传给容器内的 hdfs 命令
            byte[] data = Files.readAllBytes(Paths.get(localPath));
//            ProcessBuilder pb = new ProcessBuilder(
//                    "docker", "exec", "-i", CONTAINER,
//                    HDFS_BIN, "dfs", "-put", "-f", "-", hdfsPath);
            List<String> cmd = List.of("docker", "exec", "-i", CONTAINER,
                    HDFS_BIN, "dfs", "-put", "-f", "-", hdfsPath);
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (OutputStream os = p.getOutputStream()) {
                os.write(data);
            }
            String output = new String(p.getInputStream().readAllBytes());
            int code = p.waitFor();
            if (code != 0) {
                log.error("[HDFS] 上传失败，退出码 {}", code);
                return false;
            }

            log.info("[HDFS] 上传成功: {} ({} 字节)", hdfsPath, data.length);
            return true;

        } catch (Exception e) {
            log.error("[HDFS] 上传异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 在 NameNode 容器内执行 hdfs 命令（统一包一层 docker exec）。
     * 注意：stdin 立即关闭（这类命令不需要输入），否则子进程可能一直等待。
     */
    private boolean execInContainer(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("docker", "exec", "-i", CONTAINER));
        cmd.addAll(List.of(args));

        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getOutputStream().close();
        String output = new String(p.getInputStream().readAllBytes());
        int code = p.waitFor();

        if (code != 0) {
            log.error("[HDFS] 命令执行失败(退出码 {}): {}", code, output.trim());
        }
        return code == 0;
    }
}