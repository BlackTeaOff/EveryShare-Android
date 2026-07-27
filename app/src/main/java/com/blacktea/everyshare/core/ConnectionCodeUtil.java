package com.blacktea.everyshare.core;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Base64;

public class ConnectionCodeUtil {

    public static class ConnectionInfo {
        public String ip;
        public int port;
    }

    public static String generateCode(String ipv6Str, int port) throws UnknownHostException {
        InetAddress inetAddress = InetAddress.getByName(ipv6Str);
        byte[] ipBytes = inetAddress.getAddress(); // 16 字节

        ByteBuffer buffer = ByteBuffer.allocate(18); // 16 字节 IP + 2 字节端口
        buffer.put(ipBytes);
        buffer.putShort((short) port); // 写入 2 字节 Short [1]

        String base64 = Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
        return "everyshare://" + base64;
    }

    public static ConnectionInfo parseCode(String code) {
        if (code == null || !code.startsWith("everyshare://")) {
            throw new IllegalArgumentException("互传码格式无效");
        }
        try {
            String base64 = code.substring("everyshare://".length()).trim();
            byte[] bytes = Base64.getUrlDecoder().decode(base64);

            if (bytes.length != 18) {
                throw new IllegalArgumentException("互传码长度不正确");
            }

            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            byte[] ipBytes = new byte[16];
            buffer.get(ipBytes);

            // 使用 Short.toUnsignedInt 将 2 字节有符号 short 还原为 32 位无符号整数端口 [1]
            int port = Short.toUnsignedInt(buffer.getShort());

            ConnectionInfo info = new ConnectionInfo();
            info.ip = InetAddress.getByAddress(ipBytes).getHostAddress();
            info.port = port;
            return info;
        } catch (Exception e) {
            throw new IllegalArgumentException("解析互传码失败", e);
        }
    }
}