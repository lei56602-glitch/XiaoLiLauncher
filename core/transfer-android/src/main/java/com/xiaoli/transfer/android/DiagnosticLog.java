package com.xiaoli.transfer.android;
import android.content.Context;
import android.os.Build;
import android.util.Log;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
/** Persistent user-exportable diagnostics. Never records file contents or private keys. */
public final class DiagnosticLog {
 private static final String TAG="QuanLianDiag",FILE="quanlian_diagnostics.log"; private static final int MAX_BYTES=524288;
 private DiagnosticLog(){}
 public static synchronized void add(Context c,String level,String event,String detail){
  if(c==null)return;String line=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",Locale.US).format(new Date())+" ["+level+"] "+event+" "+(detail==null?"":detail.replace('\n',' ').replace('\r',' '));
  if(line.length()>6000)line=line.substring(0,6000)+" [truncated]";if("E".equals(level))Log.e(TAG,line);else if("W".equals(level))Log.w(TAG,line);else Log.i(TAG,line);
  File f=new File(c.getFilesDir(),FILE);try{if(f.exists()&&f.length()>MAX_BYTES){File old=new File(c.getFilesDir(),FILE+".old");if(old.exists())old.delete();if(!f.renameTo(old)){try(FileOutputStream o=new FileOutputStream(f,false)){o.write(("Log rotated "+line+"\n").getBytes("UTF-8"));}return;}}
   try(FileOutputStream o=new FileOutputStream(f,true)){o.write((line+"\n").getBytes("UTF-8"));o.getFD().sync();}
  }catch(Exception e){Log.e(TAG,"Unable to persist diagnostic log",e);}
 }
 public static void info(Context c,String e,String d){add(c,"I",e,d);}
 public static void warn(Context c,String e,String d){add(c,"W",e,d);}
 public static void error(Context c,String e,Throwable t){StringWriter w=new StringWriter();if(t!=null)t.printStackTrace(new PrintWriter(w));add(c,"E",e,w.toString());}
 public static synchronized String read(Context c){String version="unknown";int code=0;try{android.content.pm.PackageInfo info=c.getPackageManager().getPackageInfo(c.getPackageName(),0);version=info.versionName;if(android.os.Build.VERSION.SDK_INT>=28)code=(int)info.getLongVersionCode();else code=info.versionCode;}catch(Exception ignored){}StringBuilder b=new StringBuilder("小李全联故障诊断日志\nApp=").append(version).append(" (").append(code).append(")\nAndroid=").append(Build.VERSION.RELEASE).append(" API=").append(Build.VERSION.SDK_INT).append("\nDevice=").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n请同时导出发送端和接收端日志；日志不包含文件内容。\n========== LOG BEGIN ==========\n");append(b,new File(c.getFilesDir(),FILE+".old"));append(b,new File(c.getFilesDir(),FILE));return b.append("========== LOG END ==========\n").toString();}
 private static void append(StringBuilder b,File f){if(!f.exists())return;try(BufferedReader r=new BufferedReader(new InputStreamReader(new FileInputStream(f),"UTF-8"))){String s;while((s=r.readLine())!=null)b.append(s).append('\n');}catch(Exception e){b.append("[read failed ").append(e).append("]\n");}}
 public static synchronized void clear(Context c){new File(c.getFilesDir(),FILE).delete();new File(c.getFilesDir(),FILE+".old").delete();info(c,"DIAGNOSTICS_CLEARED","user cleared old logs");}
}