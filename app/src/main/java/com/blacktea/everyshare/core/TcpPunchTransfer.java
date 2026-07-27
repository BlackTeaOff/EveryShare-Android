package com.blacktea.everyshare.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;

public class TcpPunchTransfer {
    private static final Logger log = LoggerFactory.getLogger(TcpPunchTransfer.class);
    private static final String FAKE_HTTP_HEADER = "GET / HTTP/1.1\r\nHost: speedtest.cn\r\n\r\n";

    /**
     * 💡 终极方案：通过公网反射接口获取 100% 准确、活跃的本机公网 IPv6 地址
     */
    /**
     * 💡 升级版：多重公网反射接口轮询（中国 + 全球稳定节点）
     */
    public static InetAddress getActivePublicIpv6() {
        // 依次尝试这三个全球最稳定的 IPv6 测速/查询接口
        String[] apis = {
                "https://6.ipw.cn",          // 节点1：国内极速
                "https://api6.ipify.org",    // 节点2：全球最大 IP 服务商
                "https://v6.ident.me"        // 节点3：经典轻量级服务
        };

        for (String api : apis) {
            try {
                java.net.URL url = new java.net.URL(api);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(2500); // 💡 每个接口只给 2.5 秒，超时立刻换下一个
                conn.setReadTimeout(2500);

                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()))) {
                    String ipStr = reader.readLine();
                    if (ipStr != null && !ipStr.trim().isEmpty()) {
                        log.info("🎯 通过公网反射接口 [{}] 成功定位本地 IPv6: {}", api, ipStr.trim());
                        return java.net.InetAddress.getByName(ipStr.trim());
                    }
                }
            } catch (Exception e) {
                log.warn("⚠️ 通过接口 [{}] 获取公网 IPv6 失败，正在尝试下一个...", api);
            }
        }

        log.error("❌ 所有公网反射接口均不可用或无网络，将启动本地网卡遍历兜底！");
        return null;
    }

    // 得到本机 IPv6 地址
    public static InetAddress getLocalPhysicalIPv6() throws SocketException {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface ni = interfaces.nextElement();
            if (ni.isLoopback() || !ni.isUp()) {
                continue;
            }

            String name = ni.getDisplayName().toLowerCase();
            if (name.contains("vmware") || name.contains("virtual") || name.contains("wsl")) {
                continue;
            }

            Enumeration<InetAddress> addresses = ni.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress addr = addresses.nextElement();
                if (addr instanceof Inet6Address && !addr.isLinkLocalAddress()) {
                    return addr;
                }
            }
        }
        return null;
    }

    // 打 TCP 防火墙
    public Socket connectByPunch(String remoteIp, int remotePort, int localPort) {
        int attempt = 1;
        long timeoutMs = 800;

        InetAddress localIp;
        localIp = getActivePublicIpv6();
        if (localIp == null) {
            log.error("未找到本机可用的公网 IPv6 地址");
            return null;
        }
        log.info("本地 IPv6 IP: [{}]:{}", localIp.getHostAddress(), localPort);

        // 开始碰撞
        while (true) {
            Socket socket = new Socket();
            try {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(localIp, localPort));

                log.info("尝试 {}: 发起同步连接...", attempt);
                socket.connect(new InetSocketAddress(remoteIp, remotePort), (int) timeoutMs);

                // 如果连上了就会到这, 没连上或者超时会到下面的 Exception
                log.info("TCP 连接成功!");

                OutputStream os = socket.getOutputStream();
                InputStream is = socket.getInputStream();

                // 向对面发送 FAKE_HEADER
                os.write(FAKE_HTTP_HEADER.getBytes());
                // 刷新流, 没够缓冲区大小也直接发送
                os.flush();

                 byte[] cleanBuffer = new byte[FAKE_HTTP_HEADER.length()];
                 // 读取并丢掉 Fake 报头
                 int read = is.read(cleanBuffer);

                 log.info("成功发送并交换 Fake_HEADER");
                 return socket;
            } catch (Exception e) {
                try {
                    // 没连上会到这里
                    // close 也会抛异常
                    socket.close();
                } catch (IOException ignored) {}
                attempt++;
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {}
            }
        }
    }

    // 在打通的 Socket 发送文件
    // 传入一个 listener 返回实时数据
    public void sendFile(Socket socket, File file, ProgressListener listener) {
        try (OutputStream os = socket.getOutputStream();
            InputStream is = socket.getInputStream();
            FileInputStream fis = new FileInputStream(file);
            ProgressInputStream progressInputStream = new ProgressInputStream(fis, file.getName(), file.length(), listener);) {

            String metaData = "PREPARE:" + file.getName() + ":" + file.length() + "\n";
            // 按照 UTF_8 编码转换为 Bytes
            os.write(metaData.getBytes(StandardCharsets.UTF_8));
            os.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String response = reader.readLine();

            if (!"ACCEPT".equals(response)) {
                log.warn("传输被拒绝");
                return;
            }

            log.info("接收端已同意, 开始上传数据");

            byte[] buffer = new byte[65536];
            int len;
            // 读多少, 写多少
            while ((len = progressInputStream.read(buffer)) != -1) {
                os.write(buffer, 0, len);
            }
            os.flush();
            log.info("文件 [{}] 发送成功", file.getName());
        } catch (Exception e) {
            log.error("发送文件发生异常", e);
        } finally {

        }
    }

    // 用打通的 Socket 接收文件
    public void receiveFile(Socket socket, String saveDir, ProgressListener listener) {
        try (InputStream is = socket.getInputStream();
            OutputStream os = socket.getOutputStream();) {

            // 读取元数据
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String metaData = reader.readLine();

            if (metaData == null || !metaData.startsWith("PREPARE:")) {
                os.write("REJECT\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
                return;
            }

            String[] parts = metaData.split(":");
            String fileName = parts[1];
            long fileSize = Long.parseLong(parts[2]);

            log.info("收到传输申请 | 文件名: {}, 大小: {} 字节", fileName, fileSize);

            // 暂时同意
            os.write("ACCEPT\n".getBytes(StandardCharsets.UTF_8));
            os.flush();

            File destFile = new File(saveDir, fileName);
            log.info("开始下载...");

            try (ProgressInputStream progressInputStream = new ProgressInputStream(is, fileName, fileSize, listener)) {
                Files.copy(progressInputStream, destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                log.info("文件 [{}] 接收成功!", fileName);
            }
        } catch (Exception e) {
            log.error("接收文件发生异常", e);
        }
    }

    private void closeSocket(Socket socket) {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }
}
