package com.blacktea.everyshare.core;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

public class ProgressInputStream extends FilterInputStream {
    private final String fileName;
    private final long totalBytes;
    // 注入监听器
    // 每隔一会调用监听器
    private final ProgressListener listener;
    private long bytesRead = 0;
    private long lastUpdateTime = 0;
    private long bytesReadAtLastUpdate = 0;
    // 成员变量, 不用在函数内循环更新成0
    private double speedMbps = 0;

    // 从传入的InputStream读数据, 并计算数据
    public ProgressInputStream(InputStream in,String fileName, long totalBytes, ProgressListener listener) {
        // 构造父类 FilterInputStream
        super(in);
        this.fileName = fileName;
        this.totalBytes = totalBytes;
        this.listener = listener;
        this.lastUpdateTime = System.currentTimeMillis();
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b != -1) {
            bytesRead += 1;
            notifyProgress();
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int read = super.read(b, off, len);
        if (read != -1) {
            bytesRead += read;
            notifyProgress();
        }
        return read;
    }

    private void notifyProgress() {
        long now = System.currentTimeMillis();
        // 如果下载完了, bytesRead = totalBytes
        // 也不会return, 正常返回数据 (100 %)
        long duration = now - lastUpdateTime;
        if (duration < 500 && bytesRead < totalBytes) {
            return;
        }
        lastUpdateTime = now;
        long bytesInThisInterval = bytesRead - bytesReadAtLastUpdate;
        bytesReadAtLastUpdate = bytesRead;

        // 没读到数据就不计算速度
        if (duration > 0 && bytesInThisInterval > 0) {
            double speedBytesPerSec = (double) bytesInThisInterval / (duration / 1000.0);
            // Byte转换为MByte
            speedMbps = speedBytesPerSec / (1024 * 1024);
            // 还在传输的时候, 网络卡住, 才更新成0
            // 传输完了调用的情况不会把速度清零
        } else if (bytesRead < totalBytes) {
            speedMbps = 0;
        }

        if (listener != null) {
            listener.onProgress(fileName, bytesRead, totalBytes, speedMbps);
        }
    }
}
