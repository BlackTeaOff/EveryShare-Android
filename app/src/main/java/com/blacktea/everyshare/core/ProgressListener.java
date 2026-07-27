package com.blacktea.everyshare.core;

public interface ProgressListener {
    // ProgressInputStream 调用这个接口
    // 传入这几个参数
    // 交给实现它的函数按自己的逻辑打印进度信息
    // 不同地方的逻辑不一样
    void onProgress(String fileName, long bytesRead, long totalBytes, double speedMbps);
}
