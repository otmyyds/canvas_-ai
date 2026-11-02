package com.picturecreate.doccanvas.service.ai.llm;

/**
 * 模型调用观测接口。
 * 网关在每次实际请求成功或失败后回调，调用方据此写入运行轨迹。
 */
@FunctionalInterface
public interface ModelCallListener {

    /** 处理一次模型请求轨迹；实现方自行保证不影响模型调用本身 */
    void onCall(ModelCallTrace trace);
}
