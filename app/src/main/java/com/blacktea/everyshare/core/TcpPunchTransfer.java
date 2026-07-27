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
    private static final String FAKE_HTTP_HEADER = "GET / HTTP/1.1\r\nHost: speedtest.cn\r\n\r\n";

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

    /**
     * 💡 核心新增：获取本机所有真实物理 IPv6 字符串列表，用于进行 NAT6 检测
     */
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

    private double alignTimeByUdp(String remoteIp, int remotePort, int localPort, boolean isMaster) {
        AppLogger.info("[UDP] Starting sync and time alignment...");
        double slaveWaitTimeMs = 0;

        try (DatagramSocket udpSocket = new DatagramSocket(null)) {
            udpSocket.setReuseAddress(true);

            InetAddress localIp = getActivePublicIpv6();
            if (localIp == null) return 0; // 💡 没公网 IP 直接退出

            udpSocket.bind(new InetSocketAddress(localIp, localPort));
            udpSocket.setSoTimeout(300);

            InetAddress remoteAddress = InetAddress.getByName(remoteIp);
            byte[] sendBuf = "UDP_HELLO".getBytes(StandardCharsets.UTF_8);
            byte[] receiveBuf = new byte[1024];
            DatagramPacket receivePacket = new DatagramPacket(receiveBuf, receiveBuf.length);

            boolean handshakeConnected = false;
            long lastSendTime = 0;

            while (!handshakeConnected) {
                long now = System.currentTimeMillis();
                if (now - lastSendTime > 300) {
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
                        AppLogger.info("[UDP] Handshake successful, bidirectional channel established!");
                    }
                } catch (SocketTimeoutException ignored) {}
            }

            if (isMaster) {
                AppLogger.info("[UDP] Master starting RTT measurement...");
                List<Double> rttSamples = new ArrayList<>();
                byte[] ping = "PING_RTT".getBytes(StandardCharsets.UTF_8);

                for (int i = 0; i < 3; i++) {
                    long t1 = System.nanoTime();
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

                String syncMsg = "START_TCP_DELAY:" + slaveWait;
                byte[] syncBytes = syncMsg.getBytes(StandardCharsets.UTF_8);
                for (int i = 0; i < 3; i++) {
                    udpSocket.send(new DatagramPacket(syncBytes, syncBytes.length, remoteAddress, remotePort));
                    Thread.sleep(10);
                }

                AppLogger.info("[UDP] Master countdown: {}ms...", String.format("%.1f", masterWait));
                return masterWait;

            } else {
                AppLogger.info("[UDP] Slave waiting for Master's command...");
                udpSocket.setSoTimeout(3000);

                while (true) {
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
                AppLogger.info("[UDP] Slave countdown: {}ms...", String.format("%.1f", slaveWaitTimeMs));
                return slaveWaitTimeMs;
            }

        } catch (Exception e) {
            AppLogger.error("[UDP] Sync failed", e);
        }
        return 0;
    }

    public Socket connectByPunch(String remoteIp, int remotePort, int localPort, boolean isMaster, boolean useUdpSync) {
        if (useUdpSync) {
            double waitTimeMs = alignTimeByUdp(remoteIp, remotePort, localPort, isMaster);
            if (waitTimeMs > 0) {
                try { Thread.sleep((long) waitTimeMs); } catch (InterruptedException ignored) {}
            }
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        }

        AppLogger.info("==================================================");
        AppLogger.info("      ⏰ TCP Punching: START ({} mode) ⏰", useUdpSync ? "UDP-Aligned" : "Direct-Collision");
        AppLogger.info("==================================================");

        int attempt = 1;
        long timeoutMs = 800;
        InetAddress localIp;
        localIp = getActivePublicIpv6();
        if (localIp == null) {
            log.error("Failed to retrieve public IPv6");
            return null;
        }

        while (true) {
            Socket socket = new Socket();
            try {
                socket.setSendBufferSize(1500 * 1024);
                socket.setReceiveBufferSize(1500 * 1024);
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(localIp, localPort));

                AppLogger.info("Attempt {}: Initiating synchronized connection...", attempt);
                socket.connect(new InetSocketAddress(remoteIp, remotePort), (int) timeoutMs);
                AppLogger.info("[SUCCESS] TCP punch established successfully!");

                OutputStream os = socket.getOutputStream();
                InputStream is = socket.getInputStream();

                os.write(FAKE_HTTP_HEADER.getBytes(StandardCharsets.UTF_8));
                os.flush();

                int targetLength = FAKE_HTTP_HEADER.length();
                byte[] cleanBuffer = new byte[targetLength];
                int totalRead = 0;
                while (totalRead < targetLength) {
                    int read = is.read(cleanBuffer, totalRead, targetLength - totalRead);
                    if (read == -1) throw new IOException("Connection closed prematurely");
                    totalRead += read;
                }

                AppLogger.info("[DPI] Successfully exchanged and cleaned FakeHTTP headers.");
                return socket;
            } catch (Exception e) {
                try {
                    socket.close();
                } catch (IOException ignored) {}
                attempt++;
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {}
            }
        }
    }

    public void sendFile(Socket socket, InputStream fileStream, String fileName, long fileSize, ProgressListener listener) {
        try (OutputStream os = socket.getOutputStream();
             InputStream is = socket.getInputStream();
             ProgressInputStream progressInputStream = new ProgressInputStream(fileStream, fileName, fileSize, listener)) {

            String metaData = "PREPARE:" + fileName + ":" + fileSize + "\n";
            os.write(metaData.getBytes(StandardCharsets.UTF_8));
            os.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String response = reader.readLine();

            if (!"ACCEPT".equals(response)) {
                AppLogger.info("[WARN] Receiver rejected file: {}", fileName);
                return;
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
            AppLogger.error("Error occurred while sending file", e);
        }
    }

    public void receiveFile(Socket socket, String saveDir, ProgressListener listener) {
        try (InputStream is = socket.getInputStream();
             OutputStream os = socket.getOutputStream()) {

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

            AppLogger.info("[OK] Received transfer request | Name: {}, Size: {} bytes", fileName, fileSize);

            os.write("ACCEPT\n".getBytes(StandardCharsets.UTF_8));
            os.flush();

            File destFile = new File(saveDir, fileName);
            AppLogger.info("Downloading...");

            try (ProgressInputStream progressInputStream = new ProgressInputStream(is, fileName, fileSize, listener)) {
                Files.copy(progressInputStream, destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                AppLogger.info("[SUCCESS] File [{}] received successfully!", fileName);
            }
        } catch (Exception e) {
            AppLogger.error("Error occurred while receiving file", e);
        }
    }
}