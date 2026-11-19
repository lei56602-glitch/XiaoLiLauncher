package com.xiaoli.transfer.android;

import android.util.Log;
import android.content.Context;
import org.json.JSONObject;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import android.os.Build;

/**
 * QuanLian discovery: one wildcard-bound UDP receiver handles subnet broadcasts,
 * while multicast memberships are added per eligible interface. This avoids
 * competing SO_REUSEADDR sockets on Android hotspot/Wi-Fi interfaces.
 */
public final class LanDiscovery {
 private static final String TAG="QuanLianDiscovery",APP="QUANLIAN",GROUP="224.0.0.167";
 private static final int VERSION=3,PORT=38889;
 public static final String PROBE="QUANLIAN_DISCOVERY_PROBE",PROBE_OK="QUANLIAN_DISCOVERY_OK";
 private LanDiscovery(){}

 public static final class Announcement {
  public final String id,name,fingerprint; public final InetAddress host; public final int port;
  Announcement(String i,String n,String fp,InetAddress h,int p){id=i;name=n;fingerprint=fp;host=h;port=p;}
 }

 public interface AnnouncementListener { void onAnnouncement(Announcement announcement); }

 public static final class Listener implements AutoCloseable {
  private final AtomicBoolean running=new AtomicBoolean(true);
  private MulticastSocket socket;
  private Thread thread;
  Listener(AnnouncementListener cb){
   try{
    socket=new MulticastSocket(null);
    socket.setReuseAddress(true);
    socket.bind(new InetSocketAddress(PORT));
    socket.setSoTimeout(1500);
    InetAddress group=InetAddress.getByName(GROUP);
    Enumeration<NetworkInterface> all=NetworkInterface.getNetworkInterfaces();
    while(all!=null&&all.hasMoreElements()){
     NetworkInterface ni=all.nextElement();
     try{if(!ni.isUp()||ni.isLoopback())continue;}catch(Exception e){continue;}
     boolean hasV4=false;
     for(InterfaceAddress a:ni.getInterfaceAddresses())if(a.getAddress() instanceof Inet4Address){hasV4=true;break;}
     if(!hasV4)continue;
     try{
      if(ni.supportsMulticast()){
       if (Build.VERSION.SDK_INT >= 24) socket.joinGroup(new InetSocketAddress(group,PORT),ni); else socket.joinGroup(group);
       Log.i(TAG,"Joined multicast on "+ni.getName()+" ("+ni.getIndex()+")");
      }else Log.i(TAG,"Multicast unavailable on "+ni.getName()+"; broadcast receive remains enabled");
     }catch(Exception e){Log.w(TAG,"Multicast join failed on "+ni.getName()+"; broadcast receive remains enabled",e);}
    }
    thread=new Thread(()->receiveLoop(socket,cb),"QuanLian-UDP-Listener");thread.setDaemon(true);thread.start();
    Log.i(TAG,"UDP discovery receiver bound to 0.0.0.0:"+PORT);
   }catch(Exception e){Log.e(TAG,"Cannot start UDP discovery receiver",e);close();}
  }
  private void receiveLoop(MulticastSocket s,AnnouncementListener cb){
   byte[] b=new byte[4096];
   while(running.get()&&s!=null&&!s.isClosed()){
    try{
     DatagramPacket p=new DatagramPacket(b,b.length);s.receive(p);
     JSONObject j=new JSONObject(new String(p.getData(),p.getOffset(),p.getLength(),StandardCharsets.UTF_8));
     if(!APP.equals(j.optString("app"))||j.optInt("v")!=VERSION)continue;
     String id=j.optString("id",""),name=j.optString("name",""),fp=j.optString("fp","");
     int port=j.optInt("port",-1);
     if(id.length()<8||id.length()>128||name.isEmpty()||name.length()>128||!fp.matches("(?i)[0-9a-f]{64}")||port<1||port>65535)continue;
     Log.i(TAG,"Received announcement from "+p.getAddress().getHostAddress()+", servicePort="+port+", name="+name);
     cb.onAnnouncement(new Announcement(id,name,fp,p.getAddress(),port));
    }catch(SocketTimeoutException ignored){}
    catch(Exception e){if(running.get())Log.w(TAG,"UDP discovery receive error",e);}
   }
  }
  public void close(){
   if(!running.compareAndSet(true,false))return;
   if(socket!=null){try{socket.close();}catch(Exception ignored){}}
   if(thread!=null)thread.interrupt();
  }
 }

 public static Listener listen(AnnouncementListener callback){return new Listener(callback);}

 public static boolean verify(Context context,InetAddress host,int port,String fingerprint){
  return verifyDetailed(context,host,port,fingerprint)==null;
 }

 /** Returns null on success, otherwise a stage-specific diagnostic for the UI and Logcat. */
 public static String verifyDetailed(Context context,InetAddress host,int port,String fingerprint){
  if(host==null)return "对方 IP 地址为空";
  if(port<1||port>65535)return "对方公布的 TCP 端口无效："+port;
  if(!TlsIdentity.plainMode(context)&&(fingerprint==null||!fingerprint.matches("(?i)[0-9a-f]{64}")))return "广播中的证书指纹格式无效";
  try(Socket s=TlsIdentity.connect(context,host.getHostAddress(),port,fingerprint)){
   try{
    DataOutputStream out=new DataOutputStream(s.getOutputStream());DataInputStream in=new DataInputStream(s.getInputStream());
    out.writeUTF(PROBE);out.flush();
    String response=in.readUTF();
    if(!PROBE_OK.equals(response))return (TlsIdentity.plainMode(context)?"明文 TCP 已连接":"TLS 已建立")+"，但设备探测协议响应不匹配："+response;
    return null;
   }catch(Exception e){
    String detail=(TlsIdentity.plainMode(context)?"明文 TCP 已连接，但探测协议失败":"TLS 已建立，但探测协议失败")+" ["+e.getClass().getSimpleName()+"]: "+e.getMessage();
    Log.e(TAG,detail,e);return detail;
   }
  }catch(Exception e){
   String detail=(TlsIdentity.plainMode(context)?"明文 TCP 连接失败":"TCP/TLS 握手或证书校验失败")+" ["+e.getClass().getSimpleName()+"]: "+e.getMessage();
   Log.e(TAG,"Discovery verification failed for "+host+":"+port+"; "+detail,e);
   return detail;
  }
 }

 public static void announce(String id,String name,int port){try{announce(id,name,port,TlsIdentity.fingerprint());}catch(Exception e){Log.e(TAG,"TLS identity unavailable for discovery",e);}}

 public static void announce(String id,String name,int port,String fp){
  if(id==null||id.length()<8||name==null||name.isEmpty()||fp==null||!fp.matches("(?i)[0-9a-f]{64}")||port<1||port>65535)return;
  try{
   JSONObject j=new JSONObject();j.put("app",APP);j.put("v",VERSION);j.put("id",id);j.put("name",name);j.put("port",port);j.put("fp",fp);
   byte[] data=j.toString().getBytes(StandardCharsets.UTF_8);
   Enumeration<NetworkInterface> all=NetworkInterface.getNetworkInterfaces();boolean sent=false;
   while(all!=null&&all.hasMoreElements()){
    NetworkInterface ni=all.nextElement();
    try{if(!ni.isUp()||ni.isLoopback())continue;}catch(Exception e){continue;}
    boolean hasV4=false;for(InterfaceAddress a:ni.getInterfaceAddresses())if(a.getAddress() instanceof Inet4Address){hasV4=true;break;}
    if(!hasV4)continue;
    try(MulticastSocket s=new MulticastSocket()){
     s.setNetworkInterface(ni);s.setTimeToLive(1);s.setBroadcast(true);
     // Subnet-directed broadcast is attempted even when Android reports that
     // the AP interface does not support multicast.
     for(InterfaceAddress a:ni.getInterfaceAddresses()){
      if(!(a.getAddress() instanceof Inet4Address))continue;
      InetAddress b=a.getBroadcast();
      if(b!=null){try{s.send(new DatagramPacket(data,data.length,b,PORT));sent=true;Log.i(TAG,"Broadcast announcement sent on "+ni.getName()+" to "+b.getHostAddress());}catch(Exception e){Log.w(TAG,"Broadcast failed on "+ni.getName(),e);}}
     }
     try{
      if(ni.supportsMulticast()){
       s.send(new DatagramPacket(data,data.length,InetAddress.getByName(GROUP),PORT));sent=true;
       Log.i(TAG,"Multicast announcement sent on "+ni.getName());
      }
     }catch(Exception e){Log.w(TAG,"Multicast send failed on "+ni.getName(),e);}
    }catch(Exception e){Log.w(TAG,"Could not announce on "+ni.getName(),e);}
   }
   if(!sent)Log.w(TAG,"No IPv4 interface accepted discovery announcement");
  }catch(Exception e){Log.e(TAG,"Could not send UDP discovery announcement",e);}
 }
}
