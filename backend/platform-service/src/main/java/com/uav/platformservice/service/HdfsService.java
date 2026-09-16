package com.uav.platformservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * HDFS 上传服务（WebHDFS REST 版）。
 *
 * 相比之前的 docker exec 命令行方案，WebHDFS 的优势：
 *   ① 不依赖 docker CLI（容器内也能正常工作）
 *   ② 真实的 HTTP 协议对接，可控制副本/权限等参数
 *   ③ 标准 HTTP 状态码，便于排查问题
 *
 * 注意：容器化后 platform-service 与 HDFS 同处一个 docker 网络，
 *      只需把地址配置成服务名（hadoop-namenode:9870）即可，无需任何地址重写。
 */
@Service
public class HdfsService {

    private static final Logger log = LoggerFactory.getLogger(HdfsService.class);
    private static final String BASE_DIR = "/uav/media";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Value("${hdfs.webhdfs-base}")
    private String webhdfsBase;          // 本地：http://localhost:9870  容器：http://hadoop-namenode:9870

    private final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * 上传本地文件到 HDFS。
     * 目录规范（契约 §7.1）：/uav/media/{deviceNo}/{yyyyMMdd}/{fileId}.jpg
     */
    public boolean upload(String localPath, String deviceNo, String fileId) {
        try {
            String day = LocalDate.now().format(DAY);
            String dir = BASE_DIR + "/" + deviceNo + "/" + day;
            String hdfsPath = dir + "/" + fileId + ".jpg";

            // ① 创建目录（WebHDFS: op=MKDIRS）
            if (!mkdirs(dir)) {
                log.error("[HDFS] 创建目录失败: {}", dir);
                return false;
            }

            // ② 上传文件：CREATE 会返回 307 重定向到 DataNode，再把数据 PUT 过去
            byte[] data = Files.readAllBytes(Paths.get(localPath));

            HttpRequest createReq = HttpRequest.newBuilder()
                    .uri(URI.create(webhdfsBase + "/webhdfs/v1" + hdfsPath + "?op=CREATE&overwrite=true"))
                    .PUT(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> createResp = httpClient.send(createReq, HttpResponse.BodyHandlers.ofString());
            if (createResp.statusCode() != 307) {
                log.error("[HDFS] CREATE 未返回重定向: {} - {}", createResp.statusCode(), createResp.body());
                return false;
            }

            String location = createResp.headers().firstValue("Location")
                    .orElseThrow(() -> new IllegalStateException("响应缺少 Location 头"));

            HttpRequest putReq = HttpRequest.newBuilder()
                    .uri(URI.create(location))
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(data))
                    .build();

            HttpResponse<String> putResp = httpClient.send(putReq, HttpResponse.BodyHandlers.ofString());
            if (putResp.statusCode() != 201) {
                log.error("[HDFS] 数据上传失败: {} - {}", putResp.statusCode(), putResp.body());
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
     * 从 HDFS 读取文件内容（WebHDFS op=OPEN）。
     * 与上传对称：NameNode 可能返回 307 重定向到 DataNode，需要手动跟随。
     *
     * @return 文件字节内容；失败返回 null
     */
    public byte[] download(String hdfsPath) {
        try {
            HttpRequest openReq = HttpRequest.newBuilder()
                    .uri(URI.create(webhdfsBase + "/webhdfs/v1" + hdfsPath + "?op=OPEN"))
                    .GET()
                    .build();

            HttpResponse<byte[]> resp = httpClient.send(openReq, HttpResponse.BodyHandlers.ofByteArray());

            // 直接返回内容
            if (resp.statusCode() == 200) {
                return resp.body();
            }

            // 307：跟随重定向到 DataNode 再取
            if (resp.statusCode() == 307) {
                String location = resp.headers().firstValue("Location").orElse(null);
                if (location == null) {
                    return null;
                }
                HttpRequest dataReq = HttpRequest.newBuilder()
                        .uri(URI.create(location))
                        .GET()
                        .build();
                HttpResponse<byte[]> dataResp = httpClient.send(dataReq, HttpResponse.BodyHandlers.ofByteArray());
                return dataResp.statusCode() == 200 ? dataResp.body() : null;
            }

            log.error("[HDFS] 读取失败，状态码 {}", resp.statusCode());
            return null;

        } catch (Exception e) {
            log.error("[HDFS] 读取异常 {}: {}", hdfsPath, e.getMessage());
            return null;
        }
    }

    /** WebHDFS 创建目录（op=MKDIRS） */
    private boolean mkdirs(String dir) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(webhdfsBase + "/webhdfs/v1" + dir + "?op=MKDIRS"))
                .PUT(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        return resp.statusCode() == 200;
    }
}
