package com.xiaoli.transfer.android;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import javax.net.ssl.*;

/** Per-install TLS identity. Peers pin the SHA-256 fingerprint advertised during discovery. */
public final class TlsIdentity {
    public static final String PREFS="quanlian_diagnostics";
    public static final String KEY_PLAIN="plain_mode";
    public static boolean plainMode(Context c){return (c.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 && c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getBoolean(KEY_PLAIN,false);}
    public static String plainFingerprint(){return "0000000000000000000000000000000000000000000000000000000000000000";}
    private TlsIdentity(){}
    public static String fingerprint(Context context) throws Exception {
        X509Certificate cert=InMemoryTlsIdentity.get(context).certificate;
        return hex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
    }
    private static SSLContext context(Context appContext, X509TrustManager trust, boolean useLocalIdentity) throws Exception {
        KeyManager[] keyManagers=null;
        if(useLocalIdentity) {
            InMemoryTlsIdentity identity=InMemoryTlsIdentity.get(appContext);
            keyManagers=new KeyManager[]{identity.keyManager};
            android.util.Log.i("QuanLianTLS","Server identity pinned in memory; keyAlgorithm="
                +identity.keyPair.getPrivate().getAlgorithm()+", keyBits=2048"
                +", certificateSha256="+hex(MessageDigest.getInstance("SHA-256").digest(identity.certificate.getEncoded())));
        }
        TrustManager[] managers=trust==null?null:new TrustManager[]{trust};
        SSLContext ctx=SSLContext.getInstance("TLSv1.2");
        ctx.init(keyManagers,managers,new SecureRandom());
        android.util.Log.i("QuanLianTLS","SSLContext algorithm="+ctx.getProtocol()
            +", provider="+ctx.getProvider().getName()+", role="+(useLocalIdentity?"SERVER":"CLIENT")
            +", enabledProtocols=[TLSv1.2]");
        return ctx;
    }
    public static ServerSocket serverSocket(Context context,int port) throws Exception {
        if(plainMode(context)){
            ServerSocket plain=new ServerSocket();plain.setReuseAddress(true);plain.setReceiveBufferSize(4*1024*1024);plain.bind(new InetSocketAddress("0.0.0.0",port));
            android.util.Log.w("QuanLianPlain","DIAGNOSTIC PLAIN MODE: listening on 0.0.0.0:"+plain.getLocalPort()+" WITHOUT TLS");
            return plain;
        }
        SSLServerSocket server=(SSLServerSocket)context(context,null,true).getServerSocketFactory().createServerSocket();
        server.setReuseAddress(true);
        // Request a larger TCP receive window for high-throughput local Wi-Fi transfers.
        server.setReceiveBufferSize(4*1024*1024);
        server.bind(new InetSocketAddress("0.0.0.0",port));
        enableTls12(server);
        return server;
    }
    /**
     * Bind an unconnected TCP socket to the Android Network whose route reaches
     * the peer. If Android does not expose that route (common on SoftAP hosts),
     * bind to the local IPv4 address on the interface sharing the peer subnet.
     * Never silently fall back to the mobile-data default route.
     */
    private static void bindToPeerNetwork(Context context, Socket socket, InetAddress peer) throws Exception {
        // First match the peer against real local IPv4 interface subnets. This is
        // essential on Android SoftAP owners: ConnectivityManager may expose a
        // cellular/default route before the tethering AP interface.
        if (peer instanceof Inet4Address) {
            ArrayList<InterfaceAddress> matches = new ArrayList<>();
            ArrayList<NetworkInterface> owners = new ArrayList<>();
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                try { if (!ni.isUp() || ni.isLoopback()) continue; } catch (Exception e) { continue; }
                for (InterfaceAddress entry : ni.getInterfaceAddresses()) {
                    InetAddress local = entry.getAddress();
                    int prefix = entry.getNetworkPrefixLength();
                    if (!(local instanceof Inet4Address) || prefix < 1 || prefix > 32) continue;
                    byte[] a = local.getAddress(), b = peer.getAddress();
                    boolean same = true;
                    for (int bit = 0; bit < prefix; bit++) {
                        int mask = 1 << (7 - (bit % 8));
                        if ((a[bit / 8] & mask) != (b[bit / 8] & mask)) { same = false; break; }
                    }
                    android.util.Log.d("QuanLianNetwork", "Interface candidate="+ni.getName()
                        +", local="+local.getHostAddress()+"/"+prefix+", peer="+peer.getHostAddress()
                        +", sameSubnet="+same);
                    if (same) { matches.add(entry); owners.add(ni); }
                }
            }
            if (!matches.isEmpty()) {
                int best = 0;
                for (int i = 1; i < matches.size(); i++) {
                    int p = matches.get(i).getNetworkPrefixLength();
                    int bp = matches.get(best).getNetworkPrefixLength();
                    String name = owners.get(i).getName().toLowerCase(Locale.ROOT);
                    String bestName = owners.get(best).getName().toLowerCase(Locale.ROOT);
                    boolean wifiLike = name.startsWith("wlan") || name.contains("ap") || name.contains("wifi");
                    boolean bestWifiLike = bestName.startsWith("wlan") || bestName.contains("ap") || bestName.contains("wifi");
                    if (p > bp || (p == bp && wifiLike && !bestWifiLike)) best = i;
                }
                InetAddress local = matches.get(best).getAddress();
                NetworkInterface ni = owners.get(best);
                socket.bind(new InetSocketAddress(local, 0));
                android.util.Log.i("QuanLianNetwork", "BOUND_BY_SUBNET interface="+ni.getName()
                    +", local="+local.getHostAddress()+", peer="+peer.getHostAddress());
                return;
            }
        }

        // Only use an explicitly Wi-Fi transport route as a fallback. Never bind
        // a peer on the hotspot subnet to cellular or VPN just because its
        // default route happens to match 0.0.0.0/0.
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network bestWifi = null;
        int bestScore = Integer.MIN_VALUE;
        if (cm != null) {
            for (Network network : cm.getAllNetworks()) {
                LinkProperties lp = cm.getLinkProperties(network);
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (lp == null || caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                for (RouteInfo route : lp.getRoutes()) {
                    if (!route.matches(peer)) continue;
                    int prefix = route.getDestination() == null ? 0 : route.getDestination().getPrefixLength();
                    int score = prefix;
                    if (score > bestScore) { bestWifi = network; bestScore = score; }
                    android.util.Log.d("QuanLianNetwork", "Wi-Fi route candidate peer="+peer.getHostAddress()
                        +", destination="+route.getDestination()+", score="+score);
                }
            }
        }
        if (bestWifi != null) {
            bestWifi.bindSocket(socket);
            android.util.Log.i("QuanLianNetwork", "BOUND_TO_WIFI_NETWORK peer="+peer.getHostAddress()
                +", routeScore="+bestScore);
            return;
        }
        throw new IOException("未找到与对方 "+peer.getHostAddress()+" 同网段的热点/Wi-Fi 接口；为避免误走移动数据，已停止连接");
    }

    // Safe because every server certificate is compared to the user-approved SHA-256 pin.
    @android.annotation.SuppressLint("CustomX509TrustManager")
    private static final class PinnedTrustManager implements X509TrustManager {
        private final byte[] expected;
        PinnedTrustManager(String fingerprint) throws GeneralSecurityException {
            this.expected = decodeHex(fingerprint.replace(":", ""));
        }
        private void checkPinned(X509Certificate[] chain) throws CertificateException {
            if (chain == null || chain.length == 0 || chain[0] == null)
                throw new CertificateException("对端没有提供有效的自签名设备证书");
            try {
                byte[] actual = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                if (!MessageDigest.isEqual(expected, actual))
                    throw new CertificateException("设备自签名证书 SHA-256 指纹不匹配");
                chain[0].checkValidity();
                android.util.Log.i("QuanLianTLS", "Pinned local self-signed certificate accepted; subject="
                    + chain[0].getSubjectX500Principal());
            } catch (CertificateException e) { throw e; }
            catch (GeneralSecurityException e) { throw new CertificateException("无法验证设备证书指纹", e); }
        }
        @Override public void checkServerTrusted(X509Certificate[] c, String a) throws CertificateException { checkPinned(c); }
        @Override public void checkClientTrusted(X509Certificate[] c, String a) throws CertificateException { throw new CertificateException("不启用客户端证书认证"); }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
    private static byte[] decodeHex(String value) throws GeneralSecurityException {
        if (value == null || !value.matches("(?i)[0-9a-f]{64}"))
            throw new GeneralSecurityException("无效的设备证书 SHA-256 指纹");
        byte[] out = new byte[32];
        for (int i=0;i<out.length;i++) out[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16);
        return out;
    }
    public static Socket connect(Context context,String host,int port,String expectedFingerprint) throws Exception {
        InetAddress peer=InetAddress.getByName(host);
        final long connectStarted=android.os.SystemClock.elapsedRealtime();
        String stage="DNS_RESOLVED";
        DiagnosticLog.info(context,"TCP_CONNECT_BEGIN","target="+peer.getHostAddress()+":"+port+", host="+host+", plain="+plainMode(context));
        android.util.Log.i("QuanLianSocket","CONNECT_BEGIN target="+peer.getHostAddress()+":"+port
            +", hostname="+host+", plain="+plainMode(context)+", androidApi="+android.os.Build.VERSION.SDK_INT);
        if(plainMode(context)){
            Socket plain=new Socket();
            try{bindToPeerNetwork(context,plain,peer);plain.connect(new InetSocketAddress(peer,port),8000);plain.setSoTimeout(12000);
                android.util.Log.w("QuanLianPlain","Connected WITHOUT TLS target="+peer.getHostAddress()+":"+port+", local="+plain.getLocalAddress().getHostAddress());
                return plain;
            }catch(Exception e){android.util.Log.e("QuanLianSocket","CONNECT_FAILED stage="+stage+", target="+peer.getHostAddress()+":"+port
                +", elapsedMs="+(android.os.SystemClock.elapsedRealtime()-connectStarted)+", exception="+e.getClass().getName()+": "+e.getMessage(),e);
                try{plain.close();}catch(Exception ignored){}throw e;}
        }
        if(expectedFingerprint==null||!expectedFingerprint.matches("(?i)[0-9a-f]{64}"))
            throw new SSLPeerUnverifiedException("缺少有效的设备自签名证书指纹");
        X509TrustManager selectedTrust = new PinnedTrustManager(expectedFingerprint);
        SSLContext sslContext=context(context,selectedTrust,false);
        SSLSocket socket=(SSLSocket)sslContext.getSocketFactory().createSocket();
        try{
            // Request larger kernel TCP windows; Android may clamp these to device limits.
            socket.setSendBufferSize(4*1024*1024);
            socket.setReceiveBufferSize(4*1024*1024);
            android.util.Log.i("QuanLianSocket","Requested TCP buffers send="+socket.getSendBufferSize()+", receive="+socket.getReceiveBufferSize());
            android.util.Log.d("QuanLianSocket","TLS socket created; target="+peer.getHostAddress()+":"+port);
            stage="TCP_BIND";
            bindToPeerNetwork(context,socket,peer);
            android.util.Log.i("QuanLianSocket","TCP_CONNECT_BEGIN target="+peer.getHostAddress()+":"+port);
            stage="TCP_CONNECT";
            socket.connect(new InetSocketAddress(peer,port),8000);
            DiagnosticLog.info(context,"TCP_CONNECTED","mode=TLS target="+peer.getHostAddress()+":"+port+", local="+socket.getLocalAddress().getHostAddress()+":"+socket.getLocalPort());
            android.util.Log.i("QuanLianSocket","TCP_CONNECTED mode=TLS target="+peer.getHostAddress()+":"+port
                +", local="+socket.getLocalAddress().getHostAddress()+":"+socket.getLocalPort()
                +", elapsedMs="+(android.os.SystemClock.elapsedRealtime()-connectStarted)
                +", SSLContext="+sslContext.getProtocol());
            socket.setSoTimeout(12000);
            enableTls12(socket);
            android.util.Log.i("QuanLianTLS","TLS_HANDSHAKE_BEGIN target="+peer.getHostAddress()+":"+port
                +", enabledProtocols="+Arrays.toString(socket.getEnabledProtocols()));
            stage="TLS_HANDSHAKE";
            DiagnosticLog.info(context,"TLS_HANDSHAKE_BEGIN","target="+peer.getHostAddress()+":"+port+", protocols="+Arrays.toString(socket.getEnabledProtocols()));
            socket.startHandshake();
            DiagnosticLog.info(context,"TLS_HANDSHAKE_SUCCESS","target="+peer.getHostAddress()+":"+port+", protocol="+socket.getSession().getProtocol()+", cipher="+socket.getSession().getCipherSuite());
            android.util.Log.i("QuanLianTLS","Handshake success; target="+peer.getHostAddress()+":"+port+", negotiated="+socket.getSession().getProtocol()+", cipher="+socket.getSession().getCipherSuite());
            return socket;
        }catch(Exception e){
            DiagnosticLog.error(context,"CONNECT_FAILED stage="+stage+" target="+peer.getHostAddress()+":"+port,e);
            android.util.Log.e("QuanLianSocket","CONNECT_FAILED stage="+stage+", target="+peer.getHostAddress()+":"+port
                +", elapsedMs="+(android.os.SystemClock.elapsedRealtime()-connectStarted)
                +", local="+(socket.getLocalAddress()==null?"unbound":socket.getLocalAddress().getHostAddress())
                +", SSLContext="+sslContext.getProtocol()+", exception="+e.getClass().getName()+": "+e.getMessage(),e);
            try{socket.close();}catch(Exception ignored){}throw e;
        }
    }
    /** Used only for first-use enrollment; caller must present the fingerprint and obtain user approval. */
    // First-use enrollment only: the UI displays this fingerprint and requires explicit user approval.
    @android.annotation.SuppressLint("TrustAllX509TrustManager")
    public static String inspectFingerprint(Context context,String host,int port) throws Exception {
        if(plainMode(context))return plainFingerprint();
        X509TrustManager trustAll=new X509TrustManager(){
            public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
            public void checkClientTrusted(X509Certificate[] c,String a){}
            public void checkServerTrusted(X509Certificate[] c,String a)throws CertificateException{if(c==null||c.length==0)throw new CertificateException("Missing peer certificate");}
        };
        SSLContext ctx=SSLContext.getInstance("TLSv1.2");ctx.init(null,new TrustManager[]{trustAll},new SecureRandom());
        try(SSLSocket socket=(SSLSocket)ctx.getSocketFactory().createSocket()){
            InetAddress peer=InetAddress.getByName(host);bindToPeerNetwork(context,socket,peer);socket.connect(new InetSocketAddress(peer,port),2500);socket.setSoTimeout(4000);enableTls12(socket);socket.startHandshake();
            java.security.cert.Certificate[] chain=socket.getSession().getPeerCertificates();return hex(MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded()));
        }
    }
    private static void enableTls12(SSLSocket s){s.setEnabledProtocols(new String[]{"TLSv1.2"});android.util.Log.i("QuanLianTLS","TLS socket protocols forced to TLSv1.2 only");}
    private static void enableTls12(SSLServerSocket s){s.setEnabledProtocols(new String[]{"TLSv1.2"});android.util.Log.i("QuanLianTLS","TLS server protocols forced to TLSv1.2 only");}
    private static String hex(byte[] bytes){StringBuilder b=new StringBuilder();for(byte x:bytes)b.append(String.format(Locale.ROOT,"%02x",x&255));return b.toString();}
}
