package com.uav.devicesimulator.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 设备端的存储客户端 —— 设备自己把影像上传到 HDFS。
 *
 * 【为什么由设备上传，而不是把路径发给平台】
 *   改造前：设备只发本地路径，由平台读文件再上传。
 *   这隐含了「设备与平台共享文件系统」这一前提 —— 现实中不成立，
 *   且把设备端的实现细节（Windows 绝对路径）泄漏进了跨系统契约。
 *
 * 【路径由平台决定，设备只是执行者】
 *   本类的 upload() **不再自己拼接路径**，而是接收平台签发的 storageRef。
 *   存储布局规则完全掌握在平台手里，设备无权决定写到哪。
 *
 * 【与平台侧 HdfsService 的分工】
 *   写入方向 → 设备负责（本类）；读取方向 → 平台负责（前端预览、下载）。
 */
@Component
public class StorageClient {

    private static final Logger log = LoggerFactory.getLogger(StorageClient.class);

    /** 仿真端在宿主机运行，通过映射端口访问 HDFS（默认 http://localhost:9870） */
    @Value("${hdfs.webhdfs-base}")
    private String webhdfsBase;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * 上传本地文件到 HDFS 的指定路径。
     *
     * @param localPath  设备本地文件路径
     * @param storageRef 平台签发的存储引用（如 /uav/media/DOG-001/20260919/IR-xxx.jpg）
     * @return 上传成功返回 true
     */
    public boolean upload(String localPath, String storageRef) {
        try {
            // 目录由 storageRef 推导，不再自行拼接
            String dir = storageRef.substring(0, storageRef.lastIndexOf('/'));

            // ① 创建目录（op=MKDIRS）—— 目录已存在时同样返回 200
            if (!mkdirs(dir)) {
                log.error("[存储] 创建目录失败: {}", dir);
                return false;
            }

            byte[] data = Files.readAllBytes(Paths.get(localPath));

            // ② 请求创建文件（op=CREATE），期望得到 307 重定向
            HttpRequest createReq = HttpRequest.newBuilder()
                    .uri(URI.create(webhdfsBase + "/webhdfs/v1" + storageRef + "?op=CREATE&overwrite=true"))
                    .PUT(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> createResp = httpClient.send(createReq, HttpResponse.BodyHandlers.ofString());
            if (createResp.statusCode() != 307) {
                log.error("[存储] CREATE 未返回重定向: {} - {}",
                        createResp.statusCode(), createResp.body());
                return false;
            }

            // ③ 手动跟随重定向，把真实数据 PUT 到 DataNode
            //    注意：Java HttpClient 默认 followRedirects=NEVER，必须手动处理
            String location = createResp.headers().firstValue("Location").orElse(null);
            if (location == null) {
                log.error("[存储] 响应缺少 Location 头");
                return false;
            }

            HttpRequest putReq = HttpRequest.newBuilder()
                    .uri(URI.create(location))
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(data))
                    .build();

            HttpResponse<String> putResp = httpClient.send(putReq, HttpResponse.BodyHandlers.ofString());
            if (putResp.statusCode() != 201) {
                log.error("[存储] 数据上传失败: {} - {}", putResp.statusCode(), putResp.body());
                return false;
            }

            log.info("[存储] 上传成功: {} ({} 字节)", storageRef, data.length);
            return true;

        } catch (Exception e) {
            log.error("[存储] 上传异常: {}", e.getMessage());
            return false;
        }
    }

    /** WebHDFS 创建目录（op=MKDIRS），成功返回 200 */
    private boolean mkdirs(String dir) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(webhdfsBase + "/webhdfs/v1" + dir + "?op=MKDIRS"))
                .PUT(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        return resp.statusCode() == 200;
    }
}
