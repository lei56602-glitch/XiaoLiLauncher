package com.xiaoli.transfer.android;

import android.app.*;
import android.annotation.SuppressLint;
import android.content.*;
import android.net.Uri;
import android.net.nsd.*;
import android.net.wifi.WifiManager;
import android.os.*;
import android.provider.MediaStore;
import android.provider.Settings;
import android.content.ContentValues;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public class ReceiveForegroundService extends Service {
    public static final String ACTION_INCOMING="com.quanlian.app.receive.INCOMING";
    public static final String ACTION_DECISION="com.quanlian.app.receive.DECISION";
    public static final String ACTION_STATUS="com.quanlian.app.receive.STATUS";
    public static final String ACTION_FILES_RECEIVED="com.xiaoli.launcher.quanlian.FILES_RECEIVED";
    public static final String EXTRA_ID="request_id", EXTRA_ACCEPT="accept", EXTRA_SENDER="sender", EXTRA_COUNT="count", EXTRA_TOTAL="total", EXTRA_PORT="port", EXTRA_TEXT="text", EXTRA_PROGRESS="progress", EXTRA_FILES="files_json";
    private static final String TYPE="_quanlian._tcp.", CHANNEL="quanlian_receive", INCOMING_CHANNEL="quanlian_incoming";
    private static final int NOTIFICATION_ID=2102, BUF=65536;
    public static final int DEFAULT_PORT=38890;
    private final ExecutorService clients=Executors.newCachedThreadPool();
    private final Set<Socket> activeSockets=java.util.Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());
    private PowerManager.WakeLock receiveWakeLock;
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile boolean running=true;
    private ServerSocket server;
    private NsdManager nsd;
    private NsdManager.RegistrationListener registration;
    private WifiManager.MulticastLock multicastLock;
    private volatile boolean udpAnnouncing;
    private Thread udpAnnounceThread;
    private BroadcastReceiver decisions;
    private final ConcurrentMap<String,PendingRequest> pendingRequests=new ConcurrentHashMap<>();
    private static class PendingRequest {
        final String id; final int notificationId; final CountDownLatch latch=new CountDownLatch(1); volatile boolean accepted;
        PendingRequest(String value){id=value;notificationId=incomingNotificationId(value);}
    }

    public static void start(Context c){
        Intent i=new Intent(c,ReceiveForegroundService.class);
        if(Build.VERSION.SDK_INT>=26)c.startForegroundService(i);else c.startService(i);
    }
    public static void decide(Context c,String id,boolean accept){
        Intent i=new Intent(ACTION_DECISION).setPackage(c.getPackageName());
        i.putExtra(EXTRA_ID,id);i.putExtra(EXTRA_ACCEPT,accept);c.sendBroadcast(i);
    }
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override public void onCreate(){
        super.onCreate();
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26){NotificationChannel ch=new NotificationChannel(CHANNEL,"文件接收",NotificationManager.IMPORTANCE_LOW);ch.setDescription("小李全联正在等待局域网文件");nm.createNotificationChannel(ch);}
        if(Build.VERSION.SDK_INT>=26){NotificationChannel incoming=new NotificationChannel(INCOMING_CHANNEL,"文件接收请求",NotificationManager.IMPORTANCE_HIGH);incoming.setDescription("提示附近设备发来的文件接收请求");incoming.enableVibration(true);nm.createNotificationChannel(incoming);}
        Notification.Builder notificationBuilder = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL)
            : new Notification.Builder(this);
        Notification n=notificationBuilder
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("小李全联 · 接收服务")
            .setContentText("正在等待附近设备发送文件")
            .setOngoing(true).setOnlyAlertOnce(true).build();
        if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION_ID,n,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(NOTIFICATION_ID,n);
        decisions=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
            PendingRequest p=pendingRequests.get(i.getStringExtra(EXTRA_ID));if(p!=null){p.accepted=i.getBooleanExtra(EXTRA_ACCEPT,false);p.latch.countDown();NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);if(manager!=null)manager.cancel(p.notificationId);}
        }};
        IntentFilter f=new IntentFilter(ACTION_DECISION);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(decisions,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(decisions,f);
        nsd=(NsdManager)getSystemService(NSD_SERVICE);
        try{WifiManager wm=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);multicastLock=wm.createMulticastLock("QuanLianReceive");multicastLock.setReferenceCounted(false);multicastLock.acquire();}catch(Exception ignored){}
        clients.execute(this::listen);
    }
    private void listen(){
        try{
            server=TlsIdentity.serverSocket(this,DEFAULT_PORT);
            int port=server.getLocalPort();
            DiagnosticLog.info(this,"LISTENER_READY","mode="+(TlsIdentity.plainMode(this)?"PLAIN":"TLS")+", bind="+server.getInetAddress().getHostAddress()+":"+port+", deviceId="+DeviceIdentity.id(this) );
            android.util.Log.i("QuanLianReceive","LISTENER_READY mode="+(TlsIdentity.plainMode(this)?"PLAIN":"TLS")
                +", bind="+server.getInetAddress().getHostAddress()+":"+port
                +", reuseAddress="+server.getReuseAddress()+", deviceId="+DeviceIdentity.id(this));
            publish(port);startUdpAnnouncing(port);
            Intent status=new Intent(ACTION_STATUS).setPackage(getPackageName());status.putExtra(EXTRA_PORT,port);status.putExtra(EXTRA_TEXT,"接收服务已启动 · 端口 "+port);sendBroadcast(status);
            while(running){
                long acceptStarted=android.os.SystemClock.elapsedRealtime();
                Socket s=server.accept();
                DiagnosticLog.info(this,"TCP_ACCEPTED","local="+s.getLocalAddress().getHostAddress()+":"+s.getLocalPort()+", peer="+s.getInetAddress().getHostAddress()+":"+s.getPort() );
                android.util.Log.i("QuanLianReceive","TCP_ACCEPTED local="+s.getLocalAddress().getHostAddress()+":"+s.getLocalPort()
                    +", peer="+s.getInetAddress().getHostAddress()+":"+s.getPort()
                    +", acceptWaitMs="+(android.os.SystemClock.elapsedRealtime()-acceptStarted)
                    +", mode="+(TlsIdentity.plainMode(this)?"PLAIN":"TLS"));
                s.setSoTimeout(180000);s.setTcpNoDelay(true);activeSockets.add(s);
                clients.execute(()->{try{receive(s);}catch(Exception e){
                    DiagnosticLog.error(this,"RECEIVE_SESSION_FAILED",e);
                    android.util.Log.e("QuanLianReceive","SESSION_FAILED peer="+s.getInetAddress().getHostAddress()+":"+s.getPort()
                        +", exception="+e.getClass().getName()+": "+e.getMessage(),e);
                    broadcastStatus("连接会话失败："+e.getClass().getSimpleName()+"："+e.getMessage(),port());
                }finally{activeSockets.remove(s);}});
            }
        }catch(Exception e){if(running){android.util.Log.e("QuanLianReceive","Receive listener failed",e);broadcastStatus("接收服务异常："+e.getClass().getSimpleName()+"："+e.getMessage(),-1);}}
    }
    private void startUdpAnnouncing(int port){
        udpAnnouncing=true;
        udpAnnounceThread=new Thread(()->{
            while(running&&udpAnnouncing){
                LanDiscovery.announce(this,DeviceIdentity.id(this),deviceName(),port);
                try{Thread.sleep(2000);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
            }
        },"QuanLian-UDP-Announcer");
        udpAnnounceThread.setDaemon(true);
        udpAnnounceThread.start();
    }

    private static int incomingNotificationId(String requestId){return 3000+(requestId.hashCode()&0x3fffffff);}
    private PendingIntent decisionIntent(String requestId,boolean accept){
        Intent action=new Intent(ACTION_DECISION).setPackage(getPackageName());
        action.putExtra(EXTRA_ID,requestId);action.putExtra(EXTRA_ACCEPT,accept);
        return PendingIntent.getBroadcast(this,incomingNotificationId(requestId)+(accept?1:2),action,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    private void showIncomingNotification(PendingRequest request,String sender,int count,long total){
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(manager==null)return;
        String summary="发送设备："+sender+" · 文件数量："+count+" 个 · 总大小："+sizeText(total);
        Intent launch=getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent content=launch==null?null:PendingIntent.getActivity(this,request.notificationId,launch,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder=Build.VERSION.SDK_INT>=26
            ?new Notification.Builder(this,INCOMING_CHANNEL):new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("收到文件传输请求")
            .setContentText(summary)
            .setStyle(new Notification.BigTextStyle().bigText(summary+"。点击下方按钮接收或拒绝。"))
            .setAutoCancel(true)
            .setDefaults(Notification.DEFAULT_ALL)
            .addAction(android.R.drawable.stat_sys_download_done,"接收",decisionIntent(request.id,true))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,"拒绝",decisionIntent(request.id,false));
        if(content!=null)builder.setContentIntent(content);
        manager.notify(request.notificationId,builder.build());
    }
    private String sizeText(long bytes){
        if(bytes<1024)return bytes+" B";
        if(bytes<1024*1024)return String.format(Locale.getDefault(),"%.1f KB",bytes/1024d);
        if(bytes<1024L*1024*1024)return String.format(Locale.getDefault(),"%.1f MB",bytes/(1024d*1024));
        return String.format(Locale.getDefault(),"%.2f GB",bytes/(1024d*1024*1024));
    }
    private String deviceName(){return DeviceIdentity.name(this);}
    private void publish(int port){
        try{NsdServiceInfo info=new NsdServiceInfo();info.setServiceName(deviceName());info.setServiceType(TYPE);info.setPort(port);info.setAttribute("id",DeviceIdentity.id(this));info.setAttribute("fp",TlsIdentity.fingerprint(this));
            registration=new NsdManager.RegistrationListener(){public void onServiceRegistered(NsdServiceInfo s){broadcastStatus("局域网设备广播已启动："+s.getServiceName(),port());}public void onRegistrationFailed(NsdServiceInfo s,int e){broadcastStatus("设备广播失败（"+e+"），正在重试",-1);main.postDelayed(()->{if(running)publish(port());},2500);}public void onServiceUnregistered(NsdServiceInfo s){}public void onUnregistrationFailed(NsdServiceInfo s,int e){}};
            nsd.registerService(info,NsdManager.PROTOCOL_DNS_SD,registration);
        }catch(Exception e){broadcastStatus("设备广播启动失败",-1);}
    }
    private void receive(Socket socket){
        acquireReceiveWakeLock();
        try(Socket s=socket;DataInputStream in=new DataInputStream(new BufferedInputStream(s.getInputStream()));DataOutputStream out=new DataOutputStream(new BufferedOutputStream(s.getOutputStream()))){
            if(s instanceof javax.net.ssl.SSLSocket){
                try{((javax.net.ssl.SSLSocket)s).startHandshake();android.util.Log.i("QuanLianTLS","Server-side handshake success peer="+s.getInetAddress().getHostAddress()+":"+s.getPort()+", protocol="+((javax.net.ssl.SSLSocket)s).getSession().getProtocol()+", cipher="+((javax.net.ssl.SSLSocket)s).getSession().getCipherSuite());}
                catch(Exception handshake){DiagnosticLog.error(this,"SERVER_TLS_HANDSHAKE_FAILED",handshake);android.util.Log.e("QuanLianTLS","Server-side TLS handshake failed peer="+s.getInetAddress().getHostAddress()+":"+s.getPort()+"; "+handshake.getClass().getName()+": "+handshake.getMessage(),handshake);throw handshake;}
            }else android.util.Log.w("QuanLianPlain","Accepted plain TCP session peer="+s.getInetAddress().getHostAddress()+":"+s.getPort()+" WITHOUT ENCRYPTION");
            String protocol=in.readUTF();DiagnosticLog.info(this,"APPLICATION_PROTOCOL_RECEIVED","protocol="+protocol+", peer="+socket.getInetAddress().getHostAddress());android.util.Log.i("QuanLianReceive","Received application protocol "+protocol+" from "+socket.getInetAddress().getHostAddress());if(LanDiscovery.PROBE.equals(protocol)){out.writeUTF(LanDiscovery.PROBE_OK);out.flush();return;}boolean batched="QUANLIAN4".equals(protocol);boolean resumable=batched||"QUANLIAN3".equals(protocol);if(!resumable&&!"QUANLIAN2".equals(protocol))throw new IOException("传输协议不匹配");
            String sender=in.readUTF();if(sender==null||sender.length()>128)throw new IOException("发送设备名称无效");String senderId=resumable?in.readUTF():"legacy";if(senderId.length()>128)throw new IOException("发送设备身份无效");int count=in.readInt();if(count<1||count>500)throw new IOException("文件数量无效");
            ArrayList<String> names=new ArrayList<>(),hashes=new ArrayList<>();ArrayList<Long> sizes=new ArrayList<>();long total=0;
            for(int x=0;x<count;x++){String name=in.readUTF();long size=in.readLong();String hash=in.readUTF();if(name==null||name.length()>255||size<0||!hash.matches("(?i)[0-9a-f]{64}")||total>Long.MAX_VALUE-size)throw new IOException("文件信息无效");names.add(name);sizes.add(size);hashes.add(hash);total+=size;}
            PendingRequest request=new PendingRequest(UUID.randomUUID().toString());pendingRequests.put(request.id,request);
            DiagnosticLog.info(this,"INCOMING_REQUEST_READY","sender="+sender+", files="+count+", bytes="+total );
            android.util.Log.i("QuanLianReceive","Valid transfer request from "+sender+"; files="+count+", bytes="+total+"; waiting for user decision");
            Intent event=new Intent(ACTION_INCOMING).setPackage(getPackageName());event.putExtra(EXTRA_ID,request.id);event.putExtra(EXTRA_SENDER,sender);event.putExtra(EXTRA_COUNT,count);event.putExtra(EXTRA_TOTAL,total);sendBroadcast(event);
            if(!getSharedPreferences("quanlian_runtime",MODE_PRIVATE).getBoolean("activity_visible",false))
                showIncomingNotification(request,sender,count,total);
            boolean accepted=request.latch.await(90,TimeUnit.SECONDS)&&request.accepted;
            pendingRequests.remove(request.id);
            NotificationManager requestNotifications=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);if(requestNotifications!=null)requestNotifications.cancel(request.notificationId);
            out.writeBoolean(accepted);out.flush();if(!accepted)return;
            File dir=new File(getFilesDir(),"QuanLianIncoming");if(!dir.exists()&&!dir.mkdirs())throw new IOException("无法创建临时目录");
            byte[] buf=new byte[BUF];
            JSONArray completedFiles = new JSONArray();
            for(int x=0;x<count;x++){
                StringBuilder clean=new StringBuilder();for(char ch:names.get(x).toCharArray())clean.append("\\\\/:*?\"<>|".indexOf(ch)>=0?"_":ch);String safe=clean.toString().trim();if(safe.isEmpty()||safe.equals(".")||safe.equals(".."))safe="received_file_"+x;
                File tmp;
                MessageDigest digest=MessageDigest.getInstance("SHA-256");
                long left=sizes.get(x);
                String completedKey=Integer.toHexString(senderId.hashCode())+"_"+hashes.get(x).toLowerCase(Locale.ROOT);
                if(resumable&&getSharedPreferences("completed_incoming",MODE_PRIVATE).getBoolean(completedKey,false)){
                    out.writeLong(sizes.get(x));out.flush();
                    String previousUri=getSharedPreferences("completed_incoming",MODE_PRIVATE).getString(completedKey+"_uri",null);
                    if(previousUri!=null){
                        JSONObject previous=new JSONObject();previous.put("name",safe);previous.put("uri",previousUri);
                        previous.put("size",sizes.get(x));previous.put("mime",mimeTypeForName(safe));completedFiles.put(previous);
                    }
                    continue;
                }
                if(resumable){
                    tmp=new File(dir,".quanlian_"+Integer.toHexString(senderId.hashCode())+"_"+hashes.get(x).toLowerCase(Locale.ROOT)+".part");
                    if(tmp.exists()&&tmp.length()>left)tmp.delete();
                    long offset=tmp.exists()?tmp.length():0;
                    try(InputStream prior=new FileInputStream(tmp)){byte[] old=new byte[BUF];int n;while((n=prior.read(old))!=-1)digest.update(old,0,n);}catch(FileNotFoundException ignored){}
                    out.writeLong(offset);out.flush();left-=offset;
                    try(FileOutputStream fos=new FileOutputStream(tmp,true)){
                        byte[] chunk=new byte[4*1024*1024];
                        if(batched){
                            // QUANLIAN4: durable sync and acknowledgement once per 16 MiB window.
                            final long ackWindow=16L*1024*1024;
                            while(left>0){
                                long batchBytes=0;
                                while(left>0&&batchBytes<ackWindow){
                                    int n=in.readInt();
                                    if(n<1||n>chunk.length||n>left)throw new IOException("续传数据块长度无效");
                                    in.readFully(chunk,0,n);fos.write(chunk,0,n);digest.update(chunk,0,n);
                                    left-=n;batchBytes+=n;
                                }
                                fos.getFD().sync();
                                out.writeLong(sizes.get(x)-left);out.flush();
                            }
                        }else{
                            // QUANLIAN3 compatibility: acknowledge every data chunk.
                            while(left>0){int n=in.readInt();if(n<1||n>chunk.length||n>left)throw new IOException("续传数据块长度无效");in.readFully(chunk,0,n);fos.write(chunk,0,n);digest.update(chunk,0,n);left-=n;fos.getFD().sync();out.writeLong(sizes.get(x)-left);out.flush();}
                        }
                    }
                }else{
                    tmp=new File(dir,".quanlian_"+UUID.randomUUID()+".part");
                    try(FileOutputStream fos=new FileOutputStream(tmp)){while(left>0){int n=in.read(buf,0,(int)Math.min(buf.length,left));if(n<0)throw new EOFException("文件未传完");fos.write(buf,0,n);digest.update(buf,0,n);left-=n;}fos.getFD().sync();}
                }
                if(!hex(digest.digest()).equalsIgnoreCase(hashes.get(x))){tmp.delete();throw new IOException("文件校验失败："+safe);}
                Uri saved;try{saved=saveFile(tmp,safe);}finally{if(tmp.exists())tmp.delete();}if(saved==null)throw new IOException("无法保存文件："+safe);
                addHistory(safe,sizes.get(x),sender,saved.toString());
                JSONObject receivedFile=new JSONObject();receivedFile.put("name",safe);receivedFile.put("uri",saved.toString());
                receivedFile.put("size",sizes.get(x));receivedFile.put("mime",mimeTypeForName(safe));completedFiles.put(receivedFile);
                if(resumable)getSharedPreferences("completed_incoming",MODE_PRIVATE).edit()
                    .putBoolean(completedKey,true).putString(completedKey+"_uri",saved.toString()).commit();
                broadcastStatus("正在接收第 "+(x+1)+"/"+count+" 个文件："+safe,port(),(int)(((x+1)*1000L)/count));
            }
            out.writeBoolean(true);out.flush();
            Intent filesReceived=new Intent(ACTION_FILES_RECEIVED).setPackage(getPackageName());
            filesReceived.putExtra(EXTRA_FILES,completedFiles.toString());sendBroadcast(filesReceived);
            broadcastStatus("接收完成，文件完整性校验通过",port());
        }catch(Exception e){DiagnosticLog.error(this,"RECEIVE_PROCESSING_FAILED",e);android.util.Log.e("QuanLianReceive","接收会话处理失败 mode="+(TlsIdentity.plainMode(this)?"PLAIN":"TLS"),e);broadcastStatus("接收失败："+e.getClass().getSimpleName()+"："+e.getMessage(),port());}
        finally{releaseReceiveWakeLock();}
    }
    private int port(){return server==null?-1:server.getLocalPort();}
    private String mimeTypeForName(String name){
        String extension = "";
        int dot = name == null ? -1 : name.lastIndexOf('.');
        if(dot >= 0 && dot < name.length()-1) extension = name.substring(dot+1).toLowerCase(Locale.ROOT);
        String mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return mime == null ? "application/octet-stream" : mime;
    }

    private Uri saveFile(File src,String name)throws Exception{
        android.content.SharedPreferences prefs=getSharedPreferences("ui_settings",MODE_PRIVATE);
        boolean autoSave=prefs.getBoolean("auto_save",true);
        String mime=java.net.URLConnection.guessContentTypeFromName(name);
        if(mime==null)mime="application/octet-stream";
        if(!autoSave){
            File dir=new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),"小李全联");
            if(!dir.exists()&&!dir.mkdirs())throw new IOException("无法创建应用内接收目录");
            File target=new File(dir,System.currentTimeMillis()+"_"+name);
            try(InputStream in=new FileInputStream(src);OutputStream out=new FileOutputStream(target)){copy(in,out);}
            return Uri.fromFile(target);
        }
        boolean saveAlbum=prefs.getBoolean("save_to_album",true);
        if(Build.VERSION.SDK_INT>=29&&saveAlbum&&(mime.startsWith("image/")||mime.startsWith("video/"))){
            boolean image=mime.startsWith("image/");
            Uri collection=image?MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY):MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);values.put(MediaStore.MediaColumns.MIME_TYPE,mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,(image?Environment.DIRECTORY_PICTURES:Environment.DIRECTORY_MOVIES)+"/小李全联");values.put(MediaStore.MediaColumns.IS_PENDING,1);
            Uri target=getContentResolver().insert(collection,values);if(target==null)throw new IOException("无法保存到系统相册");
            try(InputStream in=new FileInputStream(src);OutputStream out=getContentResolver().openOutputStream(target,"w")){if(out==null)throw new IOException("无法写入系统相册");copy(in,out);}
            catch(Exception e){try{getContentResolver().delete(target,null,null);}catch(Exception ignored){}throw e;}
            values.clear();values.put(MediaStore.MediaColumns.IS_PENDING,0);getContentResolver().update(target,values,null,null);return target;
        }
        String customTree=prefs.getString("save_tree_uri","");
        if(customTree!=null&&!customTree.isEmpty()){
            Uri tree=Uri.parse(customTree);
            String treeDocumentId=android.provider.DocumentsContract.getTreeDocumentId(tree);
            Uri parentDocument=android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,treeDocumentId);
            Uri target=android.provider.DocumentsContract.createDocument(getContentResolver(),parentDocument,mime,name);
            if(target==null)throw new IOException("无法在自定义目录创建文件");
            try(InputStream in=new FileInputStream(src);OutputStream out=getContentResolver().openOutputStream(target,"w")){
                if(out==null)throw new IOException("无法写入自定义保存目录");copy(in,out);
            }catch(Exception e){try{getContentResolver().delete(target,null,null);}catch(Exception ignored){}throw e;}
            return target;
        }
        if(Build.VERSION.SDK_INT>=29){
            ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);values.put(MediaStore.MediaColumns.MIME_TYPE,mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/小李全联");values.put(MediaStore.MediaColumns.IS_PENDING,1);
            Uri target=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);if(target==null)return null;
            try(InputStream in=new FileInputStream(src);OutputStream out=getContentResolver().openOutputStream(target,"w")){if(out==null)throw new IOException("无法打开下载目录");copy(in,out);}
            catch(Exception e){try{getContentResolver().delete(target,null,null);}catch(Exception ignored){}throw e;}
            values.clear();values.put(MediaStore.MediaColumns.IS_PENDING,0);getContentResolver().update(target,values,null,null);return target;
        }
        File dir=new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),"小李全联");
        if(!dir.exists()&&!dir.mkdirs())throw new IOException("无法创建下载目录");
        File target=new File(dir,System.currentTimeMillis()+"_"+name);
        try(InputStream in=new FileInputStream(src);OutputStream out=new FileOutputStream(target)){copy(in,out);}
        return Uri.fromFile(target);
    }
    private void copy(InputStream in,OutputStream out)throws IOException{byte[] buffer=new byte[1024*1024];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}
    private void addHistory(String name,long size,String peer,String uri){
        try{SharedPreferences p=getSharedPreferences("transfer_history",MODE_PRIVATE);JSONArray old=new JSONArray(p.getString("items","[]")),next=new JSONArray();JSONObject o=new JSONObject();o.put("direction","接收");o.put("name",name);o.put("size",size);o.put("peer",peer);o.put("time",System.currentTimeMillis());o.put("uri",uri);next.put(o);for(int i=0;i<old.length()&&i<199;i++)next.put(old.getJSONObject(i));p.edit().putString("items",next.toString()).apply();}catch(Exception ignored){}
    }
    private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(Locale.US,"%02x",x&255));return s.toString();}
    private void acquireReceiveWakeLock(){try{PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);if(pm!=null){if(receiveWakeLock==null){receiveWakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"QuanLian:Receive");receiveWakeLock.setReferenceCounted(true);}receiveWakeLock.acquire(6*60*60*1000L);}}catch(Exception ignored){}}
    private void releaseReceiveWakeLock(){try{if(receiveWakeLock!=null&&receiveWakeLock.isHeld())receiveWakeLock.release();}catch(RuntimeException ignored){}}
    private void broadcastStatus(String text,int port){broadcastStatus(text,port,-1);}
    private void broadcastStatus(String text,int port,int progress){Intent i=new Intent(ACTION_STATUS).setPackage(getPackageName());i.putExtra(EXTRA_TEXT,text);i.putExtra(EXTRA_PORT,port);if(progress>=0)i.putExtra(EXTRA_PROGRESS,progress);sendBroadcast(i);}
    @Override public int onStartCommand(Intent i,int flags,int id){return START_STICKY;}
    @Override public void onDestroy(){
        running=false;udpAnnouncing=false;if(udpAnnounceThread!=null)udpAnnounceThread.interrupt();try{if(server!=null)server.close();}catch(Exception ignored){}
        for(Socket socket:activeSockets){try{socket.close();}catch(Exception ignored){}}activeSockets.clear();releaseReceiveWakeLock();
        try{if(nsd!=null&&registration!=null)nsd.unregisterService(registration);}catch(Exception ignored){}
        try{if(multicastLock!=null&&multicastLock.isHeld())multicastLock.release();}catch(Exception ignored){}
        try{if(decisions!=null)unregisterReceiver(decisions);}catch(Exception ignored){}
        clients.shutdownNow();super.onDestroy();
    }
    @Override public IBinder onBind(Intent i){return null;}
}