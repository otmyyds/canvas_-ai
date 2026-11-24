package com.picturecreate.doccanvas.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.picturecreate.doccanvas.common.Result;

/**
 * 运行时能力信息：前端据此隐藏 AI、资料上传、分享、图片上传等入口。
 * 不返回任何密钥或内部配置。
 */
@RestController
@RequestMapping("/api/meta")
public class MetaController {

    @Value("${app.version:1.0.0}")
    private String version;
    @Value("${app.features.ai-enabled:true}")
    private boolean aiEnabled;
    @Value("${app.features.source-upload-enabled:true}")
    private boolean sourceUploadEnabled;
    @Value("${app.features.image-upload-enabled:true}")
    private boolean imageUploadEnabled;
    @Value("${app.features.share-enabled:true}")
    private boolean shareEnabled;
    @Value("${app.features.registration-enabled:true}")
    private boolean registrationEnabled;
    @Value("${app.features.setup-enabled:false}")
    private boolean setupEnabled;

    @GetMapping
    public Result<MetaResponse> meta() {
        return Result.success(new MetaResponse(version, aiEnabled, sourceUploadEnabled, imageUploadEnabled,
                shareEnabled, registrationEnabled, setupEnabled));
    }

    public record MetaResponse(
            String version,
            boolean aiEnabled,
            boolean sourceUploadEnabled,
            boolean imageUploadEnabled,
            boolean shareEnabled,
            boolean registrationEnabled,
            boolean setupEnabled) {
    }
}
