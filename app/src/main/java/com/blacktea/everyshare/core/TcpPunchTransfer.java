package com.blacktea.everyshare.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

public class TcpPunchTransfer {
    private static final Logger log = LoggerFactory.getLogger(TcpPunchTransfer.class);
    private static final String FAKE_HTTP_HEADER = "GET / HTTP/1.1\r\nHost: %s\r\n\r\n";

    // 不关流的输入流包装器，防止 try-with-resources 自动关闭底层 Socket [1, 2]
    private static class NonCloseableInputStream extends FilterInputStream {
        public NonCloseableInputStream(InputStream in) {
            super(in);
        }
        @Override
        public void close() throws IOException {
            // 保持静默，不关闭底层的网络 Socket 流 [1]
        }
    }

    // 不关流的输出流包装器，重写 write 方法，防止速度阻断 [1, 2]
    private static class NonCloseableOutputStream extends FilterOutputStream {
        public NonCloseableOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void write(byte[] b) throws IOException {
            out.write(b);
        }

        @Override
        public void close() throws IOException {
            out.flush(); // 仅执行冲刷，不关闭底层 Socket 流 [1]
        }
    }

    public static InetAddress getPublicIpv6FromApi(String apiUrl) {
        try {
            AppLogger.info("[IP] 正在通过 API [{}] 请求公网 IPv6...", apiUrl);
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(2500);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String ipStr = reader.readLine();
                if (ipStr != null && !ipStr.trim().isEmpty()) {
                    String trimmedIp = ipStr.trim();
                    // 💡 打印彩色成功日志 [2.1.2]
                    AppLogger.info("[IP] [OK] 成功从 [{}] 获取 IP: {}", apiUrl, trimmedIp);
                    return InetAddress.getByName(trimmedIp);
                }
            }
        } catch (Exception e) {
            // 💡 打印失败日志，帮你排查是否是 DNS 未就绪 [2.1.2]
            AppLogger.info("[IP] [WARN] API [{}] 请求失败: {}", apiUrl, e.getMessage());
        }
        return null;
    }

    public static InetAddress getActivePublicIpv6() {
        String[] apis = { "https://api6.ipify.org", "https://v6.ident.me" };
        for (String api : apis) {
            try {
                URL url = new URL(api);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(2500);
                conn.setReadTimeout(2500);
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String ipStr = reader.readLine();
                    if (ipStr != null && !ipStr.trim().isEmpty()) {
                        AppLogger.info("[OK] Public IPv6 retrieved: {}", ipStr.trim());
                        return InetAddress.getByName(ipStr.trim());
                    }
                }
            } catch (Exception e) {
                AppLogger.info("[WARN] Failed to get IPv6 via [{}], trying next...", api);
            }
        }
        return null;
    }

    public static InetAddress getLocalPhysicalIPv6() throws SocketException {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface ni = interfaces.nextElement();
            if (ni.isLoopback() || !ni.isUp()) continue;
            String name = ni.getDisplayName().toLowerCase();
            if (name.contains("vmware") || name.contains("virtual") || name.contains("wsl")) continue;

            Enumeration<InetAddress> addresses = ni.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress addr = addresses.nextElement();
                if (addr instanceof Inet6Address && !addr.isLinkLocalAddress()) {
                    String ip = addr.getHostAddress().toLowerCase();
                    if (ip.startsWith("2") || ip.startsWith("3")) {
                        return addr;
                    }
                }
            }
        }
        return null;
    }

    public static List<String> getLocalIPv6List() {
        List<String> list = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                String name = ni.getDisplayName().toLowerCase();
                if (name.contains("vmware") || name.contains("virtual") || name.contains("wsl")) continue;

                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet6Address && !addr.isLinkLocalAddress()) {
                        list.add(addr.getHostAddress().toLowerCase());
                    }
                }
            }
        } catch (Exception ignored) {}
        return list;
    }

    private double alignTimeByUdpWithIp(InetAddress localIp, String remoteIp, int remotePort, int localPort, boolean isMaster, StatusListener statusListener) {
        // 💡 状态对齐：点击连接开始握手时，首先显示为“等待对方连接”，避免任何抢跑闪烁
        if (statusListener != null) statusListener.onStatusUpdate("等待对方连接");
        AppLogger.info("[UDP] Starting sync and time alignment...");
        double slaveWaitTimeMs = 0;

        try (DatagramSocket udpSocket = new DatagramSocket(null)) {
            udpSocket.setReuseAddress(true);
            udpSocket.bind(new InetSocketAddress(localIp, localPort));
            udpSocket.setSoTimeout(300);

            InetAddress remoteAddress = InetAddress.getByName(remoteIp);
            byte[] sendBuf = "UDP_HELLO".getBytes(StandardCharsets.UTF_8);
            byte[] receiveBuf = new byte[1024];
            DatagramPacket receivePacket = new DatagramPacket(receiveBuf, receiveBuf.length);

            boolean handshakeConnected = false;
            long lastSendTime = 0;

            while (!handshakeConnected) {
                if (Thread.currentThread().isInterrupted()) return 0;

                long now = System.currentTimeMillis();
                if (now - lastSendTime > 300) {
                    AppLogger.info("[UDP] Sending UDP_HELLO handshakes to [{}]...", remoteIp);
                    udpSocket.send(new DatagramPacket(sendBuf, sendBuf.length, remoteAddress, remotePort));
                    lastSendTime = now;
                }
                try {
                    udpSocket.receive(receivePacket);
                    String msg = new String(receivePacket.getData(), 0, receivePacket.getLength(), StandardCharsets.UTF_8);
                    if (msg.equals("UDP_HELLO") || msg.equals("UDP_HELLO_ACK")) {
                        byte[] ack = "UDP_HELLO_ACK".getBytes(StandardCharsets.UTF_8);
                        udpSocket.send(new DatagramPacket(ack, ack.length, remoteAddress, remotePort));
                        handshakeConnected = true;

                        // 💡 状态对齐：一旦两端 UDP 手动对齐，安全无闪烁切入“时延校准中”！
                        if (statusListener != null) statusListener.onStatusUpdate("正在进行 UDP 时延校准...");
                        AppLogger.info("[UDP] Handshake successful, bidirectional channel established!");
                    }
                } catch (SocketTimeoutException ignored) {}
            }

            if (isMaster) {
                AppLogger.info("[UDP] Master starting RTT measurement...");
                List<Double> rttSamples = new ArrayList<>();
                byte[] ping = "PING_RTT".getBytes(StandardCharsets.UTF_8);

                for (int i = 0; i < 3; i++) {
                    if (Thread.currentThread().isInterrupted()) return 0;
                    long t1 = System.nanoTime();
                    AppLogger.info("[UDP] Sending RTT probe #{}...", i + 1);
                    udpSocket.send(new DatagramPacket(ping, ping.length, remoteAddress, remotePort));
                    try {
                        udpSocket.receive(receivePacket);
                        String msg = new String(receivePacket.getData(), 0, receivePacket.getLength(), StandardCharsets.UTF_8);
                        if (msg.equals("PONG_RTT")) {
                            long t2 = System.nanoTime();
                            rttSamples.add((t2 - t1) / 1_000_000.0);
                        }
                    } catch (SocketTimeoutException ignored) {}
                    Thread.sleep(80);
                }

                double rtt = rttSamples.isEmpty() ? 40.0 : rttSamples.stream().mapToDouble(Double::doubleValue).average().orElse(40.0);
                double oneWayDelay = rtt / 2.0;
                double targetDelay = 200.0;
                double masterWait = targetDelay;
                double slaveWait = targetDelay - oneWayDelay;

                AppLogger.info("[UDP] Calibration done: RTT = {}ms", String.format("%.1f", rtt));
                if (statusListener != null) {
                    statusListener.onStatusUpdate("时延校准完成: RTT = " + String.format("%.1f", rtt) + "ms");
                }

                String syncMsg = "START_TCP_DELAY:" + slaveWait;
                byte[] syncBytes = syncMsg.getBytes(StandardCharsets.UTF_8);
                for (int i = 0; i < 3; i++) {
                    udpSocket.send(new DatagramPacket(syncBytes, syncBytes.length, remoteAddress, remotePort));
                    Thread.sleep(10);
                }

                return masterWait;

            } else {
                AppLogger.info("[UDP] Slave waiting for Master's command...");
                udpSocket.setSoTimeout(3000);

                while (true) {
                    if (Thread.currentThread().isInterrupted()) return 0;
                    try {
                        udpSocket.receive(receivePacket);
                        String msg = new String(receivePacket.getData(), 0, receivePacket.getLength(), StandardCharsets.UTF_8);

                        if (msg.equals("PING_RTT")) {
                            byte[] pong = "PONG_RTT".getBytes(StandardCharsets.UTF_8);
                            udpSocket.send(new DatagramPacket(pong, pong.length, remoteAddress, remotePort));
                        } else if (msg.startsWith("START_TCP_DELAY:")) {
                            slaveWaitTimeMs = Double.parseDouble(msg.split(":")[1]);
                            break;
                        }
                    } catch (SocketTimeoutException e) {
                        AppLogger.info("[UDP] Timeout waiting for command");
                        break;
                    }
                }
                return slaveWaitTimeMs;
            }

        } catch (Exception e) {
            AppLogger.error("[UDP] Sync failed", e);
        }
        return 0;
    }

    public Socket connectByPunch(String remoteIp, int remotePort, int localPort, boolean isMaster, boolean useUdpSync, String fakeHttpHost, StatusListener statusListener) {
        InetAddress localIp = getActivePublicIpv6();
        if (localIp == null) {
            try {
                localIp = getLocalPhysicalIPv6();
            } catch (Exception ignored) {}
        }

        if (localIp == null) {
            if (statusListener != null) statusListener.onStatusUpdate("错误: 未找到公网 IPv6 地址");
            AppLogger.info("[ERROR] Failed to start: No valid public IPv6 found.");
            return null;
        }

        if (useUdpSync) {
            double waitTimeMs = alignTimeByUdpWithIp(localIp, remoteIp, remotePort, localPort, isMaster, statusListener);
            if (waitTimeMs > 0) {
                try { Thread.sleep((long) waitTimeMs); } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            try { Thread.sleep(50); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        if (statusListener != null) statusListener.onStatusUpdate("正在进行 TCP 碰撞打洞...");
        AppLogger.info("==================================================");
        AppLogger.info("      ⏰ TCP Punching: START ({} mode) ⏰", useUdpSync ? "UDP-Aligned" : "Direct-Collision");
        AppLogger.info("==================================================");

        int attempt = 1;
        long timeoutMs = 800;

        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                AppLogger.info("[TCP] Connection canceled by user.");
                return null;
            }

            Socket socket = new Socket();
            try {
                socket.setSendBufferSize(1500 * 1024);
                socket.setReceiveBufferSize(1500 * 1024);
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(localIp, localPort));

                AppLogger.info("[TCP] Attempt {}: Connecting to [{}]:{}...", attempt, remoteIp, remotePort);
                socket.connect(new InetSocketAddress(remoteIp, remotePort), (int) timeoutMs);

                if (statusListener != null) statusListener.onStatusUpdate("打通成功！正在清洗混淆数据...");
                AppLogger.info("[SUCCESS] TCP punch established successfully!");

                OutputStream os = socket.getOutputStream();
                InputStream is = socket.getInputStream();

                String fakeHeader = String.format(FAKE_HTTP_HEADER, fakeHttpHost);
                os.write(fakeHeader.getBytes(StandardCharsets.UTF_8));
                os.flush();

                // 💡 完美有限状态机：严格单字节匹配并有序清洗 \r\n\r\n 报头结束符并安全洗涤 [2.1.2]
                int state = 0;
                while (true) {
                    int b = is.read();
                    if (b == -1) throw new IOException("Connection closed prematurely");

                    if (state == 0 && b == '\r') {
                        state = 1;
                    } else if (state == 1 && b == '\n') {
                        state = 2;
                    } else if (state == 2 && b == '\r') {
                        state = 3;
                    } else if (state == 3 && b == '\n') {
                        break;
                    } else {
                        state = (b == '\r') ? 1 : 0;
                    }
                }

                AppLogger.info("[DPI] Successfully exchanged and cleaned FakeHTTP headers.");
                if (statusListener != null) statusListener.onStatusUpdate("通道就绪！");
                return socket;
            } catch (Exception e) {
                try {
                    socket.close();
                } catch (IOException ignored) {}
                attempt++;
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
    }

    public void sendFile(Socket socket, InputStream fileStream, String fileName, long fileSize, ProgressListener listener) {
        try (OutputStream os = new NonCloseableOutputStream(socket.getOutputStream());
             InputStream is = new NonCloseableInputStream(socket.getInputStream());
             ProgressInputStream progressInputStream = new ProgressInputStream(fileStream, fileName, fileSize, listener)) {

            String metaData = "PREPARE:" + fileName + ":" + fileSize + "\n";
            os.write(metaData.getBytes(StandardCharsets.UTF_8));
            os.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String response = reader.readLine();

            if (response == null) {
                throw new IOException("Connection lost: peer is offline");
            }

            if (!"ACCEPT".equals(response)) {
                AppLogger.info("[WARN] Receiver rejected file: {}", fileName);
                throw new IOException("File rejected by receiver");
            }

            AppLogger.info("[OK] Receiver accepted. Starting upload...");

            byte[] buffer = new byte[65536];
            int len;
            while ((len = progressInputStream.read(buffer)) != -1) {
                os.write(buffer, 0, len);
            }
            os.flush();
            AppLogger.info("[SUCCESS] File [{}] sent successfully", fileName);
        } catch (Exception e) {
            AppLogger.error("Error occurred while sending file: " + fileName, e);
            throw new RuntimeException(e);
        }
    }

    public void receiveFile(Socket socket, String saveDir, ProgressListener listener) {
        File destFile = null;
        String fileName = "unknown";
        try (InputStream is = new NonCloseableInputStream(socket.getInputStream());
             OutputStream os = new NonCloseableOutputStream(socket.getOutputStream())) {

            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String metaData = reader.readLine();

            if (metaData == null) {
                throw new IOException("Connection lost: peer is offline");
            }

            if ("HEARTBEAT".equals(metaData)) {
                return;
            }

            if (!metaData.startsWith("PREPARE:")) {
                os.write("REJECT\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
                return;
            }

            String[] parts = metaData.split(":");
            fileName = parts[1];
            long fileSize = Long.parseLong(parts[2]);

            AppLogger.info("[OK] Received transfer request | Name: {}, Size: {} bytes", fileName, fileSize);

            os.write("ACCEPT\n".getBytes(StandardCharsets.UTF_8));
            os.flush();

            destFile = new File(saveDir, fileName);
            AppLogger.info("Downloading...");

            try (ProgressInputStream progressInputStream = new ProgressInputStream(is, fileName, fileSize, listener);
                 FileOutputStream fos = new FileOutputStream(destFile)) {

                byte[] buffer = new byte[65536];
                long bytesRemaining = fileSize;
                while (bytesRemaining > 0) {
                    int maxToRead = (int) Math.min(buffer.length, bytesRemaining);
                    int read = progressInputStream.read(buffer, 0, maxToRead);
                    if (read == -1) {
                        throw new IOException("Connection broken prematurely; file incomplete");
                    }
                    fos.write(buffer, 0, read);
                    bytesRemaining -= read;
                }
                fos.flush();
                AppLogger.info("[SUCCESS] File [{}] received successfully!", fileName);
            }
        } catch (Exception e) {
            AppLogger.error("Error occurred while receiving file: " + fileName, e);
            if (destFile != null && destFile.exists()) {
                destFile.delete();
                AppLogger.info("[CLEANUP] Deleted incomplete file: {}", fileName);
            }
            throw new RuntimeException(e);
        }
    }
}