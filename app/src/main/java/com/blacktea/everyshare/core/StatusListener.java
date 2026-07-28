package com.blacktea.everyshare.core;

/**
 * 💡 传输通道建立过程中的状态监听器，用于将底层的实时进度显示在前台 UI 界面中
 */
public interface StatusListener {
    void onStatusUpdate(String status);
}