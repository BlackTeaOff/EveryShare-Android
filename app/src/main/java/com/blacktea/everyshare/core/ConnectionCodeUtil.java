package com.blacktea.everyshare.core;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Base64;

public class ConnectionCodeUtil {

    public static class ConnectionInfo {
        public String ip;
        public int port;
        public int role; // 💡 0 代表发送端 (Sender)，1 代表接收端 (Receiver)
    }

    /**
     * 💡 组装：将 16 字节 IP + 2 字节端口 + 1 字节角色压缩为 26 位无填充 Base64 [1]
     */
    public static String generateCode(String ipv6Str, int port, int role) throws UnknownHostException {
        InetAddress inetAddress = InetAddress.getByName(ipv6Str);
        byte[] ipBytes = inetAddress.getAddress(); // 16 字节

        ByteBuffer buffer = ByteBuffer.allocate(19); // 16 字节 IP + 2 字节端口 + 1 字节角色 [1]
        buffer.put(ipBytes);
        buffer.putShort((short) port); // 2 字节 [1]
        buffer.put((byte) role);       // 1 字节

        String base64 = Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
        return "everyshare://" + base64;
    }

    /**
     * 💡 拆包：解析 19 字节数据，还原所有属性
     */
    public static ConnectionInfo parseCode(String code) {
        if (code == null || !code.startsWith("everyshare://")) {
            throw new IllegalArgumentException("互传码格式无效");
        }
        try {
            String base64 = code.substring("everyshare://".length()).trim();
            byte[] bytes = Base64.getUrlDecoder().decode(base64);

            if (bytes.length != 19) {
                throw new IllegalArgumentException("互传码长度不正确");
            }

            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            byte[] ipBytes = new byte[16];
            buffer.get(ipBytes);

            int port = Short.toUnsignedInt(buffer.getShort()); // 还原 2 字节端口 [1]
            int role = Byte.toUnsignedInt(buffer.get());       // 还原 1 字节角色

            ConnectionInfo info = new ConnectionInfo();
            info.ip = InetAddress.getByAddress(ipBytes).getHostAddress();
            info.port = port;
            info.role = role;
            return info;
        } catch (Exception e) {
            throw new IllegalArgumentException("解析互传码失败", e);
        }
    }
}