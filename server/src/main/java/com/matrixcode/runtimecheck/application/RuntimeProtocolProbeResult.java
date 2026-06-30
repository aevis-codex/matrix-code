package com.matrixcode.runtimecheck.application;

/**
 * 表示外部依赖协议级探测结果。
 *
 * <p>运行诊断对外只需要暴露低敏结论和摘要，不能把数据库密码、API Key 或 token 写入诊断结果。</p>
 */
public record RuntimeProtocolProbeResult(boolean passed, String detail) {

    public RuntimeProtocolProbeResult {
        detail = detail == null ? "" : detail.trim();
    }

    /**
     * 创建协议检查通过结果。
     *
     * @param detail 低敏通过说明。
     * @return 通过结果。
     */
    public static RuntimeProtocolProbeResult pass(String detail) {
        return new RuntimeProtocolProbeResult(true, detail);
    }

    /**
     * 创建协议检查失败结果。
     *
     * @param detail 低敏失败说明。
     * @return 失败结果。
     */
    public static RuntimeProtocolProbeResult fail(String detail) {
        return new RuntimeProtocolProbeResult(false, detail);
    }
}
