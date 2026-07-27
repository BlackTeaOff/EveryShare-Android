package com.blacktea.everyshare.core;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Base64;

public class ConnectionCodeUtil {

    // 连接码结构体
    public static class ConnectionInfo {
        public String ip;
        public int port;
    }

    // 把 IPv6 地址编码成 Base64
    public static String generateCode(String ipv6Str, int port) throws UnknownHostException {
        InetAddress inetAddress = InetAddress.getByName(ipv6Str);
        byte[] ipBytes = inetAddress.getAddress(); // 16 字节

        // Java NIO 高效处理, 存储和读写原始字节数据的内存缓冲区
        ByteBuffer buffer = ByteBuffer.allocate(20);
        buffer.put(ipBytes); // 16 字节
        buffer.putInt(port); // 4 字节

        String base64 = Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
        return "everyshare://" + base64;
    }

    // 把 Base64 互传码还原为 IP 和端口
    public static ConnectionInfo parseCode(String code) {
        if (code == null || !code.startsWith("everyshare://")) {
            throw new IllegalArgumentException("互传码格式无效");
        }
        try {
            String base64 = code.substring("everyshare://".length()).trim();
            byte[] bytes = Base64.getUrlDecoder().decode(base64);

            if (bytes.length != 20) {
                throw new IllegalArgumentException("互传码长度不正确");
            }

            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            byte[] ipBytes = new byte[16];
            // ByteBuffer 的 get 方法
            // 从里面取出特定大小的数据
            // 里面有指针, 取出数据时会移动
            buffer.get(ipBytes); // 提取 16 字节 IP
            int port = buffer.getInt();

            ConnectionInfo info = new ConnectionInfo();
            // getHostAddress 返回该 IP 地址的文本字符串表现形式
            // getByAddress 是直接解析二进制的 IP (因为网络传输的是二进制)
            // getByName 是解析字符串的 IP
            info.ip = InetAddress.getByAddress(ipBytes).getHostAddress();
            info.port = port;
            return info;
        } catch (Exception e) {
            throw new IllegalArgumentException("解析互传码失败", e);
        }
    }
}
