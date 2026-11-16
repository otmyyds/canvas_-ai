package com.picturecreate.doccanvas.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.picturecreate.doccanvas.common.Result;

/** 安全过滤器链测试用的探针接口，只存在于测试源码 */
@RestController
public class SecurityProbeController {

    @GetMapping("/api/security-probe")
    Result<String> probe() {
        return Result.success("ok");
    }
}
