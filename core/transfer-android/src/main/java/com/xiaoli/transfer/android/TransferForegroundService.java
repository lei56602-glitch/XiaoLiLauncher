package com.xiaoli.transfer.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.content.ContentValues;
import android.provider.MediaStore;
import android.provider.Settings;
import android.os.Environment;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import android.os.PowerManager;

public class TransferForegroundService extends Service {
    private static final String CHANNEL_ID = "quanlian_transfer";
    private static final int NOTIFICATION_ID = 2101;
    private static final String ACTION_START = "com.quanlian.app.action.TRANSFER_START";
    private static final String ACTION_UPDATE = "com.quanlian.app.action.TRANSFER_UPDATE";
    private static final String ACTION_STOP = "com.quanlian.app.action.TRANSFER_STOP";
    public static final String ACTION_SEND = "com.quanlian.app.action.TRANSFER_SEND";
    public static final String ACTION_CANCEL = "com.quanlian.app.action.TRANSFER_CANCEL";
    public static final String ACTION_PROGRESS = "com.quanlian.app.action.TRANSFER_PROGRESS";
    public static final String ACTION_RESULT = "com.quanlian.app.action.TRANSFER_RESULT";
    public static final String EXTRA_PROGRESS = "transfer_progress";
    public static final String EXTRA_SUCCESS = "transfer_success";
    public static final String EXTRA_CANCELLED = "transfer_cancelled";
    public static final String EXTRA_ERROR = "transfer_error";
    public static final String EXTRA_TEXT = "notification_text";
    private PowerManager.WakeLock wakeLock;
    private String currentText = "正在进行文件传输…";
    private BroadcastReceiver commandReceiver;
    private final ExecutorService transferExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean transferRunning = new AtomicBoolean(false);
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private final AtomicReference<Socket> activeSocket = new AtomicReference<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final int BUF = 65536;

    public static void start(Context context, String text) {
        Intent intent = new Intent(context, TransferForegroundService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_TEXT, text);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    public static void sendFiles(Context context, ArrayList<Uri> uris, ArrayList<String> names, String host, int port, String peer, String fingerprint) {
        Intent intent = new Intent(context, TransferForegroundService.class);
        intent.setAction(ACTION_SEND);
        intent.putParcelableArrayListExtra("uris", uris);
        intent.putStringArrayListExtra("names", names);
        intent.putExtra("host", host);
        intent.putExtra("port", port);
        intent.putExtra("peer", peer);
        intent.putExtra("fingerprint", fingerprint);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    public static void cancelTransfer(Context context) {
        Intent intent = new Intent(ACTION_CANCEL);
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);
    }

    public static void update(Context context, String text) {
        Intent intent = new Intent(ACTION_UPDATE);
        intent.setPackage(context.getPackageName());
        intent.putExtra(EXTRA_TEXT, text);
        context.sendBroadcast(intent);
    }

    public static void stop(Context context) {
        Intent intent = new Intent(ACTION_STOP);
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);
    }

    @Override @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag") public void onCreate() {
        super.onCreate();
        createChannel();
        commandReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                if (ACTION_CANCEL.equals(intent.getAction())) {
                    cancelRequested.set(true);
                    Socket socket = activeSocket.get();
                    if (socket != null) try { socket.close(); } catch (Exception ignored) { }
                } else if (ACTION_STOP.equals(intent.getAction())) {
                    stopForeground(true);
                    releaseWakeLock();
                    stopSelf();
                } else if (ACTION_UPDATE.equals(intent.getAction())) {
                    String value = intent.getStringExtra(EXTRA_TEXT);
                    if (value != null) currentText = value;
                    NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(currentText));
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_UPDATE);
        filter.addAction(ACTION_CANCEL);
        filter.addAction(ACTION_STOP);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(commandReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(commandReceiver, filter);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (intent != null && intent.hasExtra(EXTRA_TEXT)) currentText = intent.getStringExtra(EXTRA_TEXT);
        if (ACTION_SEND.equals(action)) currentText = "正在准备发送文件…";
        if (ACTION_START.equals(action) || ACTION_SEND.equals(action)) acquireWakeLock();
        Notification notification = buildNotification(currentText);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        if (ACTION_SEND.equals(action)) {
            if (!transferRunning.compareAndSet(false, true)) {
                publishResult(false, false, "已有传输任务正在运行");
            } else {
                cancelRequested.set(false);
                final ArrayList<Uri> uris = intent.getParcelableArrayListExtra("uris");
                final ArrayList<String> names = intent.getStringArrayListExtra("names");
                final String host = intent.getStringExtra("host");
                final int port = intent.getIntExtra("port", -1);
                final String peer = intent.getStringExtra("peer");
                final String fingerprint = intent.getStringExtra("fingerprint");
                transferExecutor.execute(() -> executeSend(uris, names, host, port, peer, fingerprint, startId));
            }
        }
        return START_NOT_STICKY;
    }

    private static class SendFile {
        final Uri uri; final String name; final long size; final String hash;
        SendFile(Uri uri, String name, long size, String hash) { this.uri=uri; this.name=name; this.size=size; this.hash=hash; }
    }

    private static final class UnsupportedPeerProtocolException extends IOException {
        UnsupportedPeerProtocolException(IOException cause) {
            super("接收设备不支持当前传输协议", cause);
        }
    }

    private void executeSend(ArrayList<Uri> uris, ArrayList<String> names, String host, int port, String peer, String fingerprint, int startId) {
        boolean cancelled = false;
        String failure = null;
        try {
            if (uris == null || names == null || uris.isEmpty() || uris.size() != names.size()) throw new IOException("待发送文件列表无效");
            if (host == null || port < 1 || port > 65535) throw new IOException("接收设备地址无效");
            ArrayList<SendFile> files = new ArrayList<>();
            byte[] buffer = new byte[BUF];
            long totalBytes = 0;
            for (int i=0; i<uris.size(); i++) {
                if (cancelRequested.get()) throw new IOException("已取消发送");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long length = 0;
                try (InputStream input = getContentResolver().openInputStream(uris.get(i))) {
                    if (input == null) throw new IOException("无法读取文件：" + names.get(i));
                    int n;
                    while ((n=input.read(buffer))!=-1) {
                        if (cancelRequested.get()) throw new IOException("已取消发送");
                        digest.update(buffer,0,n); length += n;
                    }
                }
                if (totalBytes > Long.MAX_VALUE-length) throw new IOException("文件总大小超出限制");
                totalBytes += length;
                files.add(new SendFile(uris.get(i),names.get(i),length,hex(digest.digest())));
            }
            if (cancelRequested.get()) throw new IOException("已取消发送");
            DiagnosticLog.info(this,"SEND_CONNECT_BEGIN","peer="+peer+", host="+host+", port="+port+", fingerprint="+fingerprint+", files="+files.size());
            updateStatus("文件准备完成，正在连接 " + peer + "…", 0);
            try {
                sendSession(files, host, port, peer, fingerprint, totalBytes, "QUANLIAN4");
            } catch (UnsupportedPeerProtocolException incompatibleV4) {
                // Retry only when the peer closes before its acceptance response; never replay a partially sent transfer.
                DiagnosticLog.info(this, "PROTOCOL_FALLBACK", "QUANLIAN4 rejected before acceptance; retrying QUANLIAN3");
                try {
                    sendSession(files, host, port, peer, fingerprint, totalBytes, "QUANLIAN3");
                } catch (UnsupportedPeerProtocolException incompatibleV3) {
                    DiagnosticLog.info(this, "PROTOCOL_FALLBACK", "QUANLIAN3 rejected before acceptance; retrying QUANLIAN2");
                    sendSession(files, host, port, peer, fingerprint, totalBytes, "QUANLIAN2");
                }
            }
            for (int i=0;i<files.size();i++) addHistory("发送",files.get(i).name,files.get(i).size,peer,uris.get(i).toString());
        } catch (Exception e) {
            cancelled = cancelRequested.get();
            failure = e.getMessage()==null ? "传输中断" : e.getMessage();
            DiagnosticLog.error(this,"SEND_EXCEPTION",e);
        } finally {
            activeSocket.set(null);
            transferRunning.set(false);
            releaseWakeLock();
            DiagnosticLog.info(this,"SEND_FINISHED","success="+(failure==null)+", cancelled="+cancelled+", error="+failure);
            publishResult(failure==null, cancelled, failure);
            mainHandler.post(() -> { stopForeground(true); stopSelf(startId); });
        }
    }

    private void sendSession(ArrayList<SendFile> files, String host, int port, String peer,
                             String fingerprint, long totalBytes, String protocol) throws IOException {
        boolean legacy = "QUANLIAN2".equals(protocol);
        boolean batched = "QUANLIAN4".equals(protocol);
        boolean resumable = !legacy;
        try (Socket socket = TlsIdentity.connect(this, host, port, fingerprint)) {
            activeSocket.set(socket);
            socket.setSoTimeout(180000);
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            out.writeUTF(protocol);
            out.writeUTF(deviceName());
            if (!legacy) out.writeUTF(DeviceIdentity.id(this));
            out.writeInt(files.size());
            for (SendFile file : files) {
                out.writeUTF(file.name);
                out.writeLong(file.size);
                out.writeUTF(file.hash);
            }
            out.flush();
            // Only this first response is eligible for protocol fallback.
            final boolean accepted;
            try {
                accepted = in.readBoolean();
            } catch (EOFException peerClosedBeforeAcceptance) {
                throw new UnsupportedPeerProtocolException(peerClosedBeforeAcceptance);
            }
            if (!accepted) throw new IOException("对方拒绝接收或接收确认超时");
            long sent = 0, lastUi = 0, started = System.currentTimeMillis();
            int index = 0;
            for (SendFile file : files) {
                index++;
                updateStatus("正在发送第 " + index + "/" + files.size() + " 个文件：" + file.name,
                    totalBytes == 0 ? 0 : (int)(sent * 1000 / totalBytes));
                long offset = resumable ? in.readLong() : 0L;
                if (offset < 0 || offset > file.size) throw new IOException("接收端续传位置无效");
                sent += offset;
                try (InputStream input = new BufferedInputStream(getContentResolver().openInputStream(file.uri))) {
                    if (input == null) throw new IOException("无法重新读取文件：" + file.name);
                    long skipped = 0;
                    while (skipped < offset) {
                        long n = input.skip(offset - skipped);
                        if (n <= 0) {
                            if (input.read() == -1) throw new EOFException("无法定位续传位置");
                            n = 1;
                        }
                        skipped += n;
                    }
                    byte[] chunk = new byte[batched || resumable ? 4 * 1024 * 1024 : BUF];
                    long remaining = file.size - offset;
                    final long ackWindow = 16L * 1024 * 1024;
                    while (remaining > 0) {
                        if (cancelRequested.get()) throw new IOException("已取消发送");
                        if (batched) {
                            long batchBytes = 0;
                            while (remaining > 0 && batchBytes < ackWindow) {
                                int wanted = (int)Math.min(chunk.length, remaining), n = 0;
                                while (n < wanted) {
                                    int read = input.read(chunk, n, wanted - n);
                                    if (read < 0) throw new EOFException("本地文件读取中断");
                                    n += read;
                                }
                                out.writeInt(n);
                                out.write(chunk, 0, n);
                                offset += n; remaining -= n; sent += n; batchBytes += n;
                                if (cancelRequested.get()) throw new IOException("已取消发送");
                            }
                            out.flush();
                            long ack = in.readLong();
                            if (ack != offset) throw new IOException("接收端批量确认位置不一致");
                        } else if (resumable) {
                            int wanted = (int)Math.min(chunk.length, remaining), n = 0;
                            while (n < wanted) {
                                int read = input.read(chunk, n, wanted - n);
                                if (read < 0) throw new EOFException("本地文件读取中断");
                                n += read;
                            }
                            out.writeInt(n);
                            out.write(chunk, 0, n);
                            out.flush();
                            offset += n; remaining -= n; sent += n;
                            long ack = in.readLong();
                            if (ack != offset) throw new IOException("接收端分块确认位置不一致");
                        } else {
                            int n = input.read(chunk, 0, (int)Math.min(chunk.length, remaining));
                            if (n < 0) throw new EOFException("本地文件读取中断");
                            out.write(chunk, 0, n);
                            remaining -= n; sent += n;
                        }
                        long now = System.currentTimeMillis();
                        if (now - lastUi > 250 || sent == totalBytes) {
                            int progress = totalBytes == 0 ? 1000 : (int)Math.min(1000, sent * 1000.0 / totalBytes);
                            long elapsed = Math.max(1, now - started);
                            long speed = (long)(sent * 1000.0 / elapsed);
                            long remainingSeconds = speed > 0 ? (totalBytes - sent) / speed : 0;
                            updateStatus("正在发送：" + sizeText(sent) + " / " + sizeText(totalBytes)
                                + " · " + sizeText(speed) + "/秒 · 剩余约 " + etaText(remainingSeconds), progress);
                            lastUi = now;
                        }
                    }
                    out.flush();
                }
            }
            if (!in.readBoolean()) throw new IOException("接收端未确认完成或文件校验失败");
        } finally {
            activeSocket.set(null);
        }
    }

    private void updateStatus(String status, int progress) {
        currentText=status;
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(manager!=null) manager.notify(NOTIFICATION_ID,buildNotification(status));
        Intent event=new Intent(ACTION_PROGRESS).setPackage(getPackageName());
        event.putExtra(EXTRA_TEXT,status); event.putExtra(EXTRA_PROGRESS,progress);
        sendBroadcast(event);
    }

    private void publishResult(boolean success, boolean cancelled, String error) {
        Intent event=new Intent(ACTION_RESULT).setPackage(getPackageName());
        event.putExtra(EXTRA_SUCCESS,success); event.putExtra(EXTRA_CANCELLED,cancelled);
        if(error!=null) event.putExtra(EXTRA_ERROR,error);
        sendBroadcast(event);
    }

    private String deviceName() { return DeviceIdentity.name(this); }

    private String sizeText(long value) {
        if(value<1024) return value+" B";
        if(value<1048576) return String.format(Locale.getDefault(),"%.1f KB",value/1024d);
        if(value<1073741824) return String.format(Locale.getDefault(),"%.1f MB",value/1048576d);
        return String.format(Locale.getDefault(),"%.2f GB",value/1073741824d);
    }

    private String etaText(long seconds) {
        if(seconds<60) return seconds+" 秒";
        if(seconds<3600) return (seconds/60)+" 分钟 "+(seconds%60)+" 秒";
        return (seconds/3600)+" 小时 "+((seconds%3600)/60)+" 分钟";
    }

    private static String hex(byte[] bytes) {
        StringBuilder value=new StringBuilder(bytes.length*2);
        for(byte b:bytes) value.append(String.format(Locale.US,"%02x",b&0xff));
        return value.toString();
    }

    private void addHistory(String direction,String name,long size,String peer,String uri) {
        try {
            android.content.SharedPreferences prefs=getSharedPreferences("transfer_history",MODE_PRIVATE);
            JSONArray old=new JSONArray(prefs.getString("items","[]")), next=new JSONArray();
            JSONObject item=new JSONObject();
            item.put("direction",direction); item.put("name",name); item.put("size",size);
            item.put("peer",peer); item.put("time",System.currentTimeMillis()); item.put("uri",uri);
            next.put(item);
            for(int i=0;i<old.length()&&i<199;i++) next.put(old.getJSONObject(i));
            prefs.edit().putString("items",next.toString()).apply();
        } catch(Exception ignored) { }
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && (wakeLock == null || !wakeLock.isHeld())) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QuanLian:FileTransfer");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(6 * 60 * 60 * 1000L);
            }
        } catch (Exception ignored) { }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (RuntimeException ignored) { }
        wakeLock = null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "文件传输", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("显示小李全联正在进行的文件传输状态");
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("小李全联 · 文件传输")
                .setContentText(text == null ? "正在进行文件传输…" : text)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    @Override public void onDestroy() {
        cancelRequested.set(true);
        Socket active=activeSocket.get(); if(active!=null) try { active.close(); } catch(Exception ignored) { }
        transferExecutor.shutdownNow();
        try { if (commandReceiver != null) unregisterReceiver(commandReceiver); } catch (Exception ignored) { }
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override public void onTimeout(int startId, int foregroundServiceType) {
        releaseWakeLock();
        stopForeground(true);
        stopSelf(startId);
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
