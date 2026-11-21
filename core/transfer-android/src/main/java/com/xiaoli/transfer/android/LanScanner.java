package com.xiaoli.transfer.android;

import android.content.Context;
import android.net.wifi.WifiManager;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * IPv4 LAN fallback scanner. Android hotspot owner devices may not expose
 * their AP-side subnet through WifiManager.getDhcpInfo(), so enumerate active
 * interface addresses as the primary source and use DhcpInfo as a fallback.
 */
public final class LanScanner {
    public interface AnnouncementConsumer {
        void accept(LanDiscovery.Announcement announcement);
    }
    public interface StatusConsumer {
        void accept(String status);
    }
    private LanScanner() {}

    public static void scan(Context context, int port,
            AnnouncementConsumer found, StatusConsumer status) {
        final int targetPort = port > 0 ? port : ReceiveForegroundService.DEFAULT_PORT;
        ExecutorService pool = Executors.newFixedThreadPool(32);
        try {
            Set<Integer> targets = collectTargets(context, status);
            if (targets.isEmpty()) {
                status.accept("无法读取当前 Wi-Fi/热点的 IPv4 网段");
                return;
            }
            status.accept("正在扫描局域网 " + targets.size() + " 个地址…");
            CountDownLatch done = new CountDownLatch(targets.size());
            for (int candidate : targets) {
                pool.execute(() -> {
                    try {
                        InetAddress address = InetAddress.getByAddress(new byte[] {
                            (byte)(candidate >>> 24), (byte)(candidate >>> 16),
                            (byte)(candidate >>> 8), (byte)candidate
                        });
                        String fp = TlsIdentity.inspectFingerprint(context, address.getHostAddress(), targetPort);
                        if (LanDiscovery.verify(context, address, targetPort, fp)) {
                            String id = "manual:" + address.getHostAddress() + ":" + targetPort;
                            found.accept(new LanDiscovery.Announcement(id,
                                "小李全联设备 " + address.getHostAddress(),
                                fp, address, targetPort));
                        }
                    } catch (Exception ignored) {
                    } finally {
                        done.countDown();
                    }
                });
            }
            boolean completed = done.await(35, TimeUnit.SECONDS);
            status.accept(completed ? "局域网主动扫描完成" : "扫描超时，已显示目前发现的设备");
        } catch (Exception e) {
            status.accept("主动扫描失败：" + e.getMessage());
        } finally {
            pool.shutdownNow();
        }
    }

    private static Set<Integer> collectTargets(Context context, StatusConsumer status) {
        Set<Integer> targets = new LinkedHashSet<>();
        Set<Integer> localAddresses = new HashSet<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                try {
                    if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                } catch (Exception ignored) { continue; }
                for (InterfaceAddress entry : ni.getInterfaceAddresses()) {
                    InetAddress raw = entry.getAddress();
                    if (!(raw instanceof Inet4Address) || raw.isLoopbackAddress()) continue;
                    int ip = toInt(raw.getAddress());
                    localAddresses.add(ip);
                    short prefix = entry.getNetworkPrefixLength();
                    if (prefix < 16 || prefix > 30) continue;
                    addSubnetTargets(targets, ip, prefix);
                }
            }
        } catch (Exception e) {
            status.accept("读取网络接口失败，尝试 Wi-Fi DHCP 信息");
        }

        // Some Android builds expose DHCP data even when interface enumeration is restricted.
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                android.net.DhcpInfo dhcp = wifi.getDhcpInfo();
                if (dhcp != null && dhcp.ipAddress != 0 && dhcp.netmask != 0) {
                    int ip = Integer.reverseBytes(dhcp.ipAddress);
                    int mask = Integer.reverseBytes(dhcp.netmask);
                    int prefix = Integer.bitCount(mask);
                    if (prefix >= 16 && prefix <= 30) {
                        localAddresses.add(ip);
                        addSubnetTargets(targets, ip, prefix);
                    }
                }
            }
        } catch (Exception ignored) {}

        targets.removeAll(localAddresses);
        return targets;
    }

    private static void addSubnetTargets(Set<Integer> targets, int ip, int prefix) {
        int mask = (int)(0xffffffffL << (32 - prefix));
        int network = ip & mask;
        int broadcast = network | ~mask;
        long hosts = (broadcast & 0xffffffffL) - (network & 0xffffffffL) - 1;
        if (hosts < 1) return;
        int first = network + 1;
        if (hosts > 1022) {
            first = Math.max(network + 1, Math.min(ip - 511, broadcast - 1022));
            hosts = 1022;
        }
        for (int i = 0; i < hosts; i++) targets.add(first + i);
    }

    private static int toInt(byte[] bytes) {
        return ((bytes[0] & 255) << 24) | ((bytes[1] & 255) << 16)
            | ((bytes[2] & 255) << 8) | (bytes[3] & 255);
    }
}
