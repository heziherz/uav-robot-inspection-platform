package com.uav.devicesimulator.client;

import com.uav.devicesimulator.common.DeviceTokenUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设备访问平台接口的客户端。
 *
 * 【为什么设备需要调用平台接口】
 *   "平台校验通过后，设备才允许往 HDFS 传影像" —— 这条规则要求设备在**上传之前**
 *   先向平台申请授权。平台在此环节完成设备身份校验，并**决定影像的存储路径**。
 *
 * 【走哪条网络路径】
 *   设备经 **Nginx 网关**访问平台（http://localhost/api），而不是直连 platform-service 的 8080 ——
 *   因为 8080 只 expose 未映射到宿主机，且"平台只暴露唯一 Web 入口"本就是架构原则。
 *
 * 【身份如何证明】
 *   HMAC 设备令牌（详见 DeviceTokenUtil）。设备没有"登录"概念，也不该持有用户的 JWT。
 */
@Component
public class PlatformClient {

    private static final Logger log = LoggerFactory.getLogger(PlatformClient.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** 平台网关地址（本地开发为 http://localhost/api） */
    @Value("${platform.base-url}")
    private String baseUrl;

    /** 与平台侧一致的预共享密钥 */
    @Value("${device.token.secret}")
    private String deviceSecret;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * 申请影像上传授权。
     *
     * @return 平台签发的存储引用（如 /uav/media/DOG-001/20260919/IR-xxx.jpg）；
     *         未获授权或请求失败返回 null（调用方据此跳过本次上传）
     */
    public String requestUploadAuth(String deviceNo, String fileId, String fileType) {
        try {
            // 每次请求【现算】令牌：时间戳取自当前时刻，因此不存在"令牌过期要续期"
            long ts = DeviceTokenUtil.nowSeconds();
            String token = DeviceTokenUtil.issue(deviceNo, ts, deviceSecret);

            Map<String, String> body = new LinkedHashMap<>();
            body.put("fileId", fileId);
            body.put("fileType", fileType);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/media/upload-auth"))
                    .header("Content-Type", "application/json")
                    .header("X-Device-No", deviceNo)
                    .header("X-Device-Ts", String.valueOf(ts))
                    .header("X-Device-Token", token)
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .build();

            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) {
                log.warn("[{}] 上传授权被拒绝（{}）: {}", deviceNo, resp.statusCode(), resp.body());
                return null;
            }

            Map<String, Object> root = MAPPER.readValue(resp.body(), Map.class);
            Object storageRef = root.get("storageRef");
            if (storageRef == null) {
                log.warn("[{}] 授权响应缺少 storageRef", deviceNo);
                return null;
            }
            return storageRef.toString();

        } catch (Exception e) {
            log.error("[{}] 申请上传授权异常: {}", deviceNo, e.getMessage());
            return null;
        }
    }
}
