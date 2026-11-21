package com.xiaoli.quanlian;

import com.xiaoli.transfer.android.DeviceIdentity;
import com.xiaoli.transfer.android.DiagnosticLog;
import com.xiaoli.transfer.android.LanDiscovery;
import com.xiaoli.transfer.android.LanScanner;
import com.xiaoli.transfer.android.ReceiveForegroundService;
import com.xiaoli.transfer.android.TlsIdentity;
import com.xiaoli.transfer.android.TransferForegroundService;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.nsd.*;
import android.net.wifi.WifiManager;
import android.content.ContentValues;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class QuanLianActivity extends Activity {
 public static final String EXTRA_WALLPAPER_MODE = "com.xiaoli.quanlian.EXTRA_WALLPAPER_MODE";
 private boolean wallpaperMode = false;
 private static final int PICK=1001, REQ_NOTIFICATIONS=1002, BUF=65536;
 private static final String TYPE="_quanlian._tcp.";
 private final ArrayList<Uri> uris=new ArrayList<>();
 private final ArrayList<String> names=new ArrayList<>();
 private final ArrayList<Long> sizes=new ArrayList<>();
 private final Map<String,Device> devices=new LinkedHashMap<>();
 private final ArrayDeque<Device> pendingRecipients=new ArrayDeque<>();
 private boolean batchSending=false;
 private final ExecutorService workers=Executors.newCachedThreadPool();
 private NsdManager nsd; private NsdManager.RegistrationListener registration; private LanDiscovery.Listener udpDiscovery;
 private NsdManager.DiscoveryListener discovery; private WifiManager.MulticastLock lock; private final android.os.Handler discoveryHandler=new android.os.Handler(android.os.Looper.getMainLooper());
 private ServerSocket server; private volatile boolean alive=true; private volatile int port=-1; private int page=0;
 private LinearLayout deviceList; private TextView deviceStatus, receiveStatus;
 private ProgressDialog sendProgress; private BroadcastReceiver sendReceiver, receiveReceiver; private Device retryDevice;
 static class Device { String name,key,fingerprint; InetAddress host; int port; long udpLastSeen; boolean udpSeen,nsdSeen; Device(String n,String k,InetAddress h,int p,String fp){name=n;key=k;host=h;port=p;fingerprint=fp;} }
 private int dp(float x){return (int)(x*getResources().getDisplayMetrics().density+.5f);}
 private boolean isTvMode(){return getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;}
 private int pageBackground(){return isDarkTheme()?0xff101827:0xfff5f8ff;}
 private boolean isDarkTheme(){String mode=getSharedPreferences("ui_settings",MODE_PRIVATE).getString("theme","system");if("dark".equals(mode))return true;if("light".equals(mode))return false;return (getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;}
 private int surfaceColor(){return isDarkTheme()?0xff1b2739:Color.WHITE;}
 private int accentColor(){String c=getSharedPreferences("ui_settings",MODE_PRIVATE).getString("accent","system");if("green".equals(c))return 0xff159b75;if("purple".equals(c))return 0xff7956c8;if("orange".equals(c))return 0xffe58a28;return 0xff2878f5;}
 private void applyWindowTheme(){boolean dark=isDarkTheme();int bg=pageBackground();getWindow().setStatusBarColor(bg);getWindow().setNavigationBarColor(bg);getWindow().getDecorView().setSystemUiVisibility(dark?0:(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));}

 private GradientDrawable shape(int c,int r){GradientDrawable d=new GradientDrawable();d.setColor(c);d.setCornerRadius(dp(r));return d;}
 private android.graphics.drawable.Drawable focusBackground(int normal,int focused,int radius){android.graphics.drawable.StateListDrawable states=new android.graphics.drawable.StateListDrawable();GradientDrawable on=shape(focused,radius);on.setStroke(dp(2),0xff72c5ff);GradientDrawable off=shape(normal,radius);states.addState(new int[]{android.R.attr.state_focused},on);states.addState(new int[]{android.R.attr.state_pressed},on);states.addState(new int[]{},off);return states;}
 private String ui(String s){
  android.content.SharedPreferences prefs=getSharedPreferences("ui_settings",MODE_PRIVATE);String language=prefs.getString("language",prefs.getBoolean("english",false)?"en":"system");boolean english="en".equals(language)||("system".equals(language)&&Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT).startsWith("en"));if(!english)return s;
  String[][] m={
   {"首页","Home"},{"设置","Settings"},{"记录","History"},{"工具","Tools"},{"关于","About"},{"传输记录","Transfer history"},{"故障诊断","Diagnostics"},{"关于我们","About us"},
   {"让文件传输更简单","File transfer made simple"},{"快速 · 安全 · 简单","Fast · Secure · Simple"},{"发送文件","Send files"},{"选择内容并发送","Select files and send"},{"接收文件","Receive files"},{"等待附近设备发送","Ready to receive"},{"附近设备","Nearby devices"},{"刷新","Refresh"},{"选择文件","Choose files"},{"图片、视频、文档","Photos, videos, documents"},{"查看传输历史","View transfer history"},{"连接与运行状态","Connection and system status"},{"传输体验","Transfer experience"},{"高速 · 稳定","Fast · Stable"},{"安全保护","Security"},{"TLS 加密","TLS encrypted"},{"局域网直连优先  ·  无广告","Direct LAN transfer · No ads"},
   {"中英文","Language"},{"语言与显示","Language & Display"},{"字体大小","Font size"},{"简体中文","简体中文"},{"英文","English"},{"小","Small"},{"标准","Medium"},{"大","Large"},{"保存","Save"},{"返回首页","Back to Home"},{"选择接收设备","Choose receiver"},{"继续添加文件","Add more files"},{"传输记录","Transfer history"},{"故障诊断与日志导出","Diagnostics & Logs"},{"正在等待连接","Waiting for connection"},{"使用方法","How to use"},{"清空历史记录","Clear history"},{"取消","Cancel"},{"清空","Clear"},
   {"接收","Receive"},{"发送","Send"},{"添加文件，然后点击目标设备即可发送","Add files, then tap a device to send"},{"文件预览","File preview"},{"添加文件","Add files"},{"已添加 ","Added "},{" 个文件 · "," files · "},{"还没有文件，点击「添加文件」开始选择","No files yet. Tap “Add files” to get started"},{"目标设备","Target devices"},{"刷新设备","Refresh devices"},{"正在搜索附近设备…","Searching for nearby devices…"},{"发现 ","Found "},{" 台附近设备"," nearby devices"},{"暂未发现设备。请让双方打开小李全联并连接同一 Wi-Fi。","No devices found. Open Xiao Li Quan Lian on both devices and connect to the same Wi-Fi."},{"点击设备发送","Tap a device to send"},{"请先添加要发送的文件","Please add files before sending"},{"已收藏设备","Device added to favorites"},{"收藏设备","Favorite devices"},{"暂无收藏设备","No favorite devices"},{"删除","Remove"},{"发送方：","Sender: "},{"文件总数：","Files: "},{"文件总大小：","Total size: "},{"接受","Accept"},{"拒绝","Decline"},{"设置已保存","Settings saved"},{"接收文件请求","Incoming file request"},
   {"大小未知","Unknown size"},{"文件","File"},{"已接收","Received"},{"已发送","Sent"},{"打开文件","Open file"},{"暂时无法打开，请到手机文件管理的 Download/小李全联 查找","Unable to open. Check Download/Xiao Li Quan Lian in your file manager."},
   {"操作步骤","Steps"},{"两台手机都安装带有此功能的版本。","Install this version on both phones."},{"复制完整日志","Copy full log"},{"分享 / 导出日志","Share / Export logs"},{"清空旧日志","Clear old logs"},{"日志已复制，可以直接粘贴发送","Logs copied. You can paste and send them."},{"旧日志已清空","Old logs cleared"},{"注意：两台手机都要导出。只有发送端日志，无法完整判断接收端是否收到连接。","Note: Export logs from both phones. Sender logs alone cannot confirm whether the receiver got the connection."},
   {"正在准备文件并计算校验值…","Preparing files and calculating checksums…"},{"小李全联正在发送","Xiao Li Quan Lian is sending"},{"发送成功","Sent successfully"},{"发送失败","Send failed"},{"重新发送","Retry"},{"知道了","Got it"},{"正在连接","Connecting"},{"已取消","Cancelled"},{"传输完成","Transfer complete"},{"发送成功！","Transfer successful!"},
   {"通用","General"},
   {"主题","Theme"},
   {"跟随系统","Follow system"},
   {"浅色","Light"},
   {"深色","Dark"},
   {"颜色","Color"},
   {"蓝色","Blue"},
   {"绿色","Green"},
   {"紫色","Purple"},
   {"橙色","Orange"},
   {"语言","Language"},
   {"动画效果","Animations"},
   {"字体大小","Font size"},
   {"接收","Receive"},
   {"自动保存","Auto save"},
   {"保存目录：","Save folder: "},
   {"已选择自定义目录","Custom folder selected"},
   {"保存到相册（图片、视频）","Save images and videos to gallery"},
   {"自动完成传输任务","Auto-accept transfers"},
   {"保存到历史记录","Save transfer history"},
   {"设备","Device"},
   {"设备名称","Device name"},
   {"保存设备名称","Save device name"},
   {"其他","Other"},
   {"关于页面","About"},
   {"捐赠入口","Donate"},
   {"隐私政策","Privacy policy"},
   {"高级设置","Advanced settings"},
   {"保存通用与接收设置","Save settings"},
   {"文件","File"},
   {"媒体","Media"},
   {"剪贴板","Clipboard"},
   {"文本","Text"},
   {"文件夹","Folder"},
   {"应用","Apps"},
   {"发送方式","Send method"},
   {"单个接收者","Single recipient"},
   {"多个接收者","Multiple recipients"},
   {"通过连接分享","Share via connection"},
   {"选择多个接收者","Choose multiple recipients"},
   {"添加到发送列表","Add to send queue"},
   {"已保存 ","Saved "},
   {"个接收设备"," recipients"},
   {"输入附近设备显示的名称","Name shown to nearby devices"},
   {"感谢支持","Thanks for your support"},
   {"无法打开文件：","Unable to open file: "},
   {"‹  返回首页","‹  Back to Home"},
   {"小李全联","Xiao Li Quan Lian"},
   {"选择要分享的内容","Choose what to share"},
   {"+ 添加文件","+ Add files"},
   {"设备保持在线，收到请求后由你决定是否接受","Stay available and choose whether to accept incoming files"},
   {"正在等待附近设备发送","Waiting for nearby devices"},
   {"暂时没有传输记录","No transfer history yet"},
   {"UDP 在线","Online via UDP"},
   {"正在重新搜索局域网设备…","Searching the local network again…"},
   {"接收服务已启动","Receive service started"},
   {"正在启动接收服务…","Starting receive service…"},
   {"故障诊断与日志","Diagnostics and logs"},
   {"手动连接设备","Connect manually"},
   {"发送方式设置","Send method settings"},
   {"通知权限未开启，传输仍可尝试进行，但系统可能不会正常显示传输通知。","Notifications are disabled. Transfers may still work, but system transfer notifications may not appear."},
   {"文件仅在设备间通过局域网传输，不会上传到开发者服务器。设备发现使用局域网广播，传输连接使用应用内 TLS 身份验证。传输历史仅保存在本机，可在应用内清除。","Files are transferred directly between devices over the local network and are not uploaded to the developer's server. Device discovery uses LAN broadcasts and transfer connections use the app's TLS identity verification. Transfer history is stored locally and can be cleared in the app."},
   {"小李全联是一款局域网文件传输工具。\n版本：1.0.1\n无广告，不上传文件内容。","Xiao Li Quan Lian is a local-network file transfer app.\nVersion: 1.0.1\nNo ads. File contents are not uploaded."},
   {"感谢支持。当前版本尚未配置可验证的收款渠道，因此不会展示虚构的二维码或支付链接。","Thank you for your support. No verified payment channel is configured in this version, so no fake QR code or payment link is shown."},
   {"连接失败后，发送端和接收端分别导出","Export logs from both sender and receiver after a connection failure"},
   {"保存目录：","Save folder: "},
   {"关闭后，文件会保存在应用专属目录，不会自动写入公共下载目录。","When disabled, files stay in the app-specific folder instead of the public Downloads folder."},
   {"开启后会自动接受附近设备发来的文件请求，请仅在可信网络中启用。","When enabled, incoming requests from nearby devices are accepted automatically. Use only on trusted networks."},
   {"1. 两台手机都安装带有此功能的版本。\n2. 先分别打开本页面，确认旧日志已清空。\n3. 返回首页，重现一次发送失败。\n4. 两台手机分别回到本页面，点击“复制完整日志”或“分享日志”。\n5. 把两份日志都发给开发者。\n\n日志包含 TCP、TLS、证书校验、接收请求及异常堆栈，不记录文件内容。","1. Install this version on both phones.\n2. Open this page on both devices and clear old logs.\n3. Reproduce the transfer failure.\n4. Export or copy the full logs from both devices.\n5. Send both logs to the developer.\n\nLogs include TCP, TLS, certificate validation and transfer errors, but never file contents."},
   {"设备名称需为 2–32 个字符","Device name must be 2–32 characters."},
   {"设备没有有效的 TLS 身份证书，已阻止连接","The device has no valid TLS identity certificate. Connection blocked."},
   {"该设备的证书指纹与此前信任的身份不同。为防止设备冒充，已阻止发送。","This device's certificate fingerprint differs from the previously trusted identity. Sending was blocked to prevent impersonation."},
   {"首次连接 · 确认设备身份","First connection · Verify device identity"},
   {"正在主动扫描局域网 IPv4 地址…","Actively scanning local IPv4 addresses…"},
   {"请先添加要发送的文件","Add files before sending."},
   {"已取消发送","Sending cancelled."},
   {"已拒绝或取消接收","Receive request declined or cancelled."},
   {"等待接收确认超时","Timed out waiting for receive confirmation."},
   {"文件校验失败：","File integrity check failed: "},
   {"接收完成，文件完整性校验通过","Receive complete. File integrity verified."},
   {"接收失败：","Receive failed: "},
   {"已收藏设备","Device added to favorites"},
   {"暂无收藏设备","No favorite devices"},
   {"收藏设备","Favorite devices"},
   {"还没有收藏设备","No favorite devices yet"},
   {"无法读取当前 Wi-Fi/热点的 IPv4 网段","Unable to read the current Wi-Fi/hotspot IPv4 subnet"},
   {"正在扫描局域网 ","Scanning LAN "},
   {" 个地址…"," addresses…"},
   {"局域网主动扫描完成","LAN scan completed"},
   {"扫描超时，已显示目前发现的设备","Scan timed out. Currently discovered devices are shown."},
   {"无法创建应用内接收目录","Unable to create app-specific receive folder"},
   {"无法在自定义目录创建文件","Unable to create file in the selected folder"},
   {"无法保存到系统相册","Unable to save to system gallery"},
   {"请允许小李全联安装应用，然后返回历史记录再次点击安装","Allow Xiao Li Quan Lian to install apps, then return to history and tap Install again."}
  };
  for(String[] p:m)if(p[0].equals(s))return p[1];
  if(s.startsWith("发送成功："))return "Sent successfully: "+s.substring(5);
  if(s.startsWith("文件校验失败："))return "File integrity check failed: "+s.substring(7);
  if(s.startsWith("接收服务已启动 · 端口 "))return "Receive service started · port "+s.substring("接收服务已启动 · 端口 ".length());
  if(s.startsWith("正在重新搜索局域网设备"))return "Searching the local network again…";
  if(s.startsWith("已发送到 "))return "Sent to "+s.substring(5);
  if(s.startsWith("开始依次发送到 "))return "Starting sequential send to "+s.substring(8);
  if(s.startsWith("已添加文件夹中的 "))return "Added "+s.substring(8);
  if(s.startsWith("接收失败："))return "Receive failed: "+s.substring(5);
  if(s.startsWith("连接失败："))return "Connection failed: "+s.substring(5);
  if(s.startsWith("无法打开文件："))return "Unable to open file: "+s.substring(7);
  if(s.startsWith("已添加 "))return "Added "+s.substring(4);
  if(s.startsWith("发现 "))return "Found "+s.substring(3);
  if(s.startsWith("发送方："))return "Sender: "+s.substring(4);
  if(s.startsWith("文件总数："))return "Files: "+s.substring(5);
  if(s.startsWith("文件总大小："))return "Total size: "+s.substring(6);
  return s;
 }
 private int fontScale(){return getSharedPreferences("ui_settings",MODE_PRIVATE).getInt("font_scale",1);}
 private TextView txt(String s,int z,int c,boolean b){TextView t=new TextView(this);t.setText(ui(s));int scale=fontScale();t.setTextSize(Math.max(10,z+(scale==0?-2:(scale==2?3:0))));t.setTextColor(c);if(b)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
 private LinearLayout col(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);int widthDp=(int)(getResources().getDisplayMetrics().widthPixels/getResources().getDisplayMetrics().density);int horizontal=widthDp<360?14:(widthDp<600?20:32);l.setPadding(dp(horizontal),dp(widthDp<600?18:24),dp(horizontal),dp(widthDp<600?18:24));return l;}
 private LinearLayout card(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(16),dp(16),dp(16),dp(16));l.setBackground(focusBackground(Color.rgb(28,45,75),0xff345b86,18));l.setFocusable(true);l.setFocusableInTouchMode(false);l.setClickable(true);return l;}
 private void gap(LinearLayout l,int h){l.addView(new View(this),new LinearLayout.LayoutParams(1,dp(h)));}
 private Button btn(String s,int c){if(c==0xff2878f5||c==0xff1f77dc)c=accentColor();Button b=new Button(this);b.setText(ui(s));b.setTextColor(-1);b.setAllCaps(false);b.setTextSize(16);b.setBackground(focusBackground(c,0xff2869a5,16));b.setMinHeight(dp(54));b.setFocusable(true);b.setFocusableInTouchMode(false);return b;}
 private void install(LinearLayout l){int widthDp=(int)(getResources().getDisplayMetrics().widthPixels/getResources().getDisplayMetrics().density);ScrollView s=new ScrollView(this);s.setFillViewport(true);s.setFocusable(false);if(widthDp>=700){FrameLayout frame=new FrameLayout(this);FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(Math.min(widthDp,1200)*((int)getResources().getDisplayMetrics().density),-1,Gravity.CENTER);s.addView(l,new ScrollView.LayoutParams(-1,-2));frame.addView(s,p);setContentView(frame);}else{s.addView(l);setContentView(s);}if(widthDp>=700){l.setPadding(dp(32),dp(28),dp(32),dp(28));}final int baseLeft=l.getPaddingLeft(),baseTop=l.getPaddingTop(),baseRight=l.getPaddingRight(),baseBottom=l.getPaddingBottom();l.setOnApplyWindowInsetsListener((v,insets)->{int top=Build.VERSION.SDK_INT>=30?insets.getInsets(android.view.WindowInsets.Type.statusBars()).top:insets.getSystemWindowInsetTop();v.setPadding(baseLeft,baseTop+top,baseRight,baseBottom);return insets;});l.requestApplyInsets();l.post(()->{ArrayList<View> focusables=l.getFocusables(View.FOCUS_FORWARD);if(!focusables.isEmpty()&&!focusables.get(0).hasFocus())focusables.get(0).requestFocus();});}
  private void installFlow(LinearLayout content,int selected){
   boolean landscapeRail=isTvMode();
   LinearLayout root=new LinearLayout(this);
   root.setOrientation(landscapeRail?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);
   root.setBackgroundColor(pageBackground());
   if(landscapeRail){
    root.addView(flowNav(selected,true),new LinearLayout.LayoutParams(dp(184),-1));
    if(page==3){
     root.addView(content,new LinearLayout.LayoutParams(0,-1,1));
    }else{
     ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);scroll.addView(content);
     root.addView(scroll,new LinearLayout.LayoutParams(0,-1,1));
    }
   }else{
    ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);scroll.addView(content);
    root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    root.addView(flowNav(selected,false),new LinearLayout.LayoutParams(-1,-2));
   }
   root.setOnApplyWindowInsetsListener((v,insets)->{
    int top=Build.VERSION.SDK_INT>=30?insets.getInsets(android.view.WindowInsets.Type.statusBars()).top:insets.getSystemWindowInsetTop();
    int bottom=Build.VERSION.SDK_INT>=30?insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom:insets.getSystemWindowInsetBottom();
    int left=Build.VERSION.SDK_INT>=30?insets.getInsets(android.view.WindowInsets.Type.systemBars()).left:insets.getSystemWindowInsetLeft();
    int right=Build.VERSION.SDK_INT>=30?insets.getInsets(android.view.WindowInsets.Type.systemBars()).right:insets.getSystemWindowInsetRight();
    v.setPadding(left,top,right,bottom);return insets;
   });
   root.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
   setContentView(root);root.requestApplyInsets();
   if(landscapeRail)root.post(()->{ArrayList<View> focusables=root.getFocusables(View.FOCUS_FORWARD);if(!focusables.isEmpty())focusables.get(0).requestFocus();});
  }
  private LinearLayout flowNav(int selected,boolean vertical){
   LinearLayout nav=new LinearLayout(this);
   nav.setOrientation(vertical?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);
   nav.setGravity(vertical?Gravity.TOP|Gravity.CENTER_HORIZONTAL:Gravity.CENTER);
   nav.setPadding(dp(vertical?14:12),dp(vertical?30:8),dp(vertical?14:12),dp(vertical?18:8));
   nav.setBackgroundColor(vertical?(isDarkTheme()?0xff14243b:0xffeaf2ff):surfaceColor());
   nav.setElevation(dp(8));
   if(vertical){
    LinearLayout brand=new LinearLayout(this);brand.setOrientation(LinearLayout.VERTICAL);brand.setGravity(Gravity.CENTER);
    BrandMarkView mark=new BrandMarkView(this);brand.addView(mark,new LinearLayout.LayoutParams(dp(76),dp(76)));
    TextView name=homeText("小李全联",18,isDarkTheme()?Color.WHITE:0xff182747,true);name.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams nameLp=new LinearLayout.LayoutParams(-1,-2);nameLp.topMargin=dp(12);brand.addView(name,nameLp);
    TextView tagline=homeText("设备互联 · 极速传输",10,isDarkTheme()?0xff9fb2d0:0xff8294ae,false);tagline.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams tagLp=new LinearLayout.LayoutParams(-1,-2);tagLp.topMargin=dp(5);brand.addView(tagline,tagLp);
    nav.addView(brand);gap(nav,38);
   }
   String[] labels={"接收","发送","设置"};
   String[] glyphs={"receive","send","settings"};
   for(int i=0;i<labels.length;i++){
    final int index=i;
    LinearLayout item=new LinearLayout(this);
    item.setOrientation(LinearLayout.HORIZONTAL);item.setGravity(Gravity.CENTER_VERTICAL);
    item.setPadding(dp(14),dp(vertical?15:12),dp(14),dp(vertical?15:12));
    item.setBackground(i==selected?shape(isDarkTheme()?0xff263d60:0xffdce8ff,24):shape(Color.TRANSPARENT,24));
    NavigationGlyph glyph=new NavigationGlyph(this,glyphs[i],i==selected?accentColor():(isDarkTheme()?0xffaab8ce:0xff64758d));
    item.addView(glyph,new LinearLayout.LayoutParams(dp(24),dp(24)));
    TextView label=homeText(labels[i],14,i==selected?accentColor():(isDarkTheme()?0xffd0d9e8:0xff66758d),i==selected);
    LinearLayout.LayoutParams labelLp=new LinearLayout.LayoutParams(-2,-2);labelLp.leftMargin=dp(12);item.addView(label,labelLp);
    item.setFocusable(isTvMode());item.setClickable(true);
    item.setOnClickListener(v->{if(index==0)receivePage();else if(index==1)sendPage();else settingsPage();});
    if(isTvMode())attachTvFocus(item,accentColor());
    if(vertical){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(9);nav.addView(item,p);}
    else nav.addView(item,new LinearLayout.LayoutParams(0,-2,1));
   }
   return nav;
  }
  private class BrandMarkView extends View{
   private final android.graphics.Paint p=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
   private final long start=android.os.SystemClock.uptimeMillis();
   BrandMarkView(Context c){super(c);setLayerType(View.LAYER_TYPE_SOFTWARE,null);}
   @Override protected void onDraw(android.graphics.Canvas c){
    super.onDraw(c);float cx=getWidth()/2f,cy=getHeight()/2f,r=Math.min(getWidth(),getHeight())*.36f;
    boolean anim=getSharedPreferences("ui_settings",MODE_PRIVATE).getBoolean("animations_enabled",true);
    float a=anim?(android.os.SystemClock.uptimeMillis()-start)*.00022f:0f;
    p.setStyle(android.graphics.Paint.Style.FILL);p.setColor(isDarkTheme()?0xff1d3556:0xffdceaff);c.drawCircle(cx,cy,r*1.55f,p);
    p.setStyle(android.graphics.Paint.Style.STROKE);p.setStrokeWidth(dp(2.4f));p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
    p.setColor(0xff397fe8);c.save();c.rotate((float)Math.toDegrees(a),cx,cy);
    c.drawOval(cx-r*1.2f,cy-r*.55f,cx+r*1.2f,cy+r*.55f,p);
    c.rotate(60,cx,cy);p.setColor(0xff65b5ff);c.drawOval(cx-r*1.2f,cy-r*.55f,cx+r*1.2f,cy+r*.55f,p);c.restore();
    p.setStyle(android.graphics.Paint.Style.FILL);p.setColor(0xff2878f5);c.drawCircle(cx,cy,dp(7),p);
    for(int i=0;i<3;i++){double ang=a+i*Math.PI*2/3;float x=cx+(float)Math.cos(ang)*r*1.05f,y=cy+(float)Math.sin(ang)*r*1.05f;p.setColor(i==1?0xff6bc3ff:0xff2878f5);c.drawCircle(x,y,dp(4),p);}
    if(anim)postInvalidateDelayed(40);
   }
  }
  private class NavigationGlyph extends View{
   private final String kind;private final android.graphics.Paint p=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);private final int color;
   NavigationGlyph(Context c,String k,int col){super(c);kind=k;color=col;}
   @Override protected void onDraw(android.graphics.Canvas c){super.onDraw(c);float w=getWidth(),h=getHeight(),cx=w/2,cy=h/2;p.setColor(color);p.setStyle(android.graphics.Paint.Style.STROKE);p.setStrokeWidth(dp(2));p.setStrokeCap(android.graphics.Paint.Cap.ROUND);p.setStrokeJoin(android.graphics.Paint.Join.ROUND);
    if("receive".equals(kind)){for(int i=0;i<3;i++){float r=dp(5+i*4);c.drawArc(cx-r,cy-r,cx+r,cy+r,225,90,false,p);c.drawArc(cx-r,cy-r,cx+r,cy+r,45,90,false,p);}c.drawLine(cx,cy-dp(8),cx,cy+dp(7),p);c.drawLine(cx-dp(6),cy+dp(1),cx,cy+dp(7),p);c.drawLine(cx+dp(6),cy+dp(1),cx,cy+dp(7),p);}
    else if("send".equals(kind)){android.graphics.Path path=new android.graphics.Path();path.moveTo(dp(3),dp(4));path.lineTo(w-dp(3),cy);path.lineTo(dp(3),h-dp(4));path.lineTo(dp(7),cy);path.close();p.setStyle(android.graphics.Paint.Style.FILL);c.drawPath(path,p);p.setStyle(android.graphics.Paint.Style.STROKE);c.drawLine(dp(7),cy,w-dp(3),cy,p);}
    else{p.setStrokeWidth(dp(2.5f));c.drawCircle(cx,cy,dp(7),p);c.drawCircle(cx,cy,dp(2),p);for(int i=0;i<8;i++){double a=Math.PI*i/4;c.drawLine(cx+(float)Math.cos(a)*dp(9),cy+(float)Math.sin(a)*dp(9),cx+(float)Math.cos(a)*dp(11),cy+(float)Math.sin(a)*dp(11),p);}}
   }
  }
  private LinearLayout flowNav(int selected){LinearLayout nav=new LinearLayout(this);nav.setGravity(Gravity.CENTER);nav.setPadding(dp(10),dp(5),dp(10),dp(5));nav.setBackgroundColor(surfaceColor());nav.setElevation(dp(10));String[] labels={"接收","发送","设置"};String[] icons={"↓","↗","⚙"};for(int i=0;i<labels.length;i++){final int index=i;TextView item=homeText(icons[i]+"  "+labels[i],13,i==selected?0xff1769e8:0xff74839a,i==selected);item.setGravity(Gravity.CENTER);item.setPadding(dp(6),dp(13),dp(6),dp(13));item.setBackground(i==selected?shape(0xffedf4ff,18):shape(Color.TRANSPARENT,18));item.setOnClickListener(v->{if(index==0)receivePage();else if(index==1)sendPage();else settingsPage();});nav.addView(item,new LinearLayout.LayoutParams(0,-2,1));}return nav;}
 private void header(LinearLayout l,String title,String sub){TextView back=homeText("‹  返回首页",15,0xff41618e,true);back.setPadding(0,0,0,dp(18));back.setOnClickListener(v->home());l.addView(back);l.addView(homeText(title,25,0xff182747,true));gap(l,6);l.addView(homeText(sub,14,0xff8491a8,false));gap(l,20);}
 private String myName(){return DeviceIdentity.name(this);}
 @Override public void onCreate(Bundle b) {
  super.onCreate(b);
  wallpaperMode = getIntent().getBooleanExtra(EXTRA_WALLPAPER_MODE, false);
   if (wallpaperMode) getSharedPreferences("quanlian_runtime", MODE_PRIVATE).edit().putBoolean("receive_enabled", true).apply();
   applyWindowTheme();
  page=3;
  nsd = (NsdManager) getSystemService(NSD_SERVICE);
  requestNotificationPermissionIfNeeded();
  if (b != null) {
   try {
    ArrayList<android.os.Parcelable> saved = b.getParcelableArrayList("selected_uris");
    if (saved != null) for (android.os.Parcelable item : saved) if (item instanceof Uri) uris.add((Uri) item);
    ArrayList<String> savedNames = b.getStringArrayList("selected_names");
    if (savedNames != null) names.addAll(savedNames);
    ArrayList<Long> savedSizes = (ArrayList<Long>) b.getSerializable("selected_sizes");
    if (savedSizes != null) sizes.addAll(savedSizes);
    page = b.getInt("current_page", 0);
   } catch (Exception ignored) {
    page = 0;
   }
  }
  registerReceiveReceiver();
  startLan();
  restorePage();
  handleIncomingShare(getIntent());
 }
 @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putParcelableArrayList("selected_uris",new ArrayList<>(uris));out.putStringArrayList("selected_names",new ArrayList<>(names));out.putSerializable("selected_sizes",new ArrayList<>(sizes));out.putInt("current_page",page);}
 private void restorePage(){switch(page){case 6:settingsPage();break;case 1:sendPage();break;case 2:sendPage();break;case 3:receivePage();break;case 4:historyPage();break;case 5:diagnosticsPage();break;default:receivePage();}}
 @Override public void onConfigurationChanged(android.content.res.Configuration newConfig){super.onConfigurationChanged(newConfig);applyWindowTheme();restorePage();}
 private void requestNotificationPermissionIfNeeded(){if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},REQ_NOTIFICATIONS);}}
 @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==REQ_NOTIFICATIONS&&grantResults.length>0&&grantResults[0]!=android.content.pm.PackageManager.PERMISSION_GRANTED){Toast.makeText(this,"通知权限未开启，传输仍可尝试进行，但系统可能不会正常显示传输通知。",Toast.LENGTH_LONG).show();}}
 private void startLan(){
  ReceiveForegroundService.start(this);
  discoveryHandler.post(pruneDevices);
  try{WifiManager w=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);lock=w.createMulticastLock("QuanLianLAN");lock.setReferenceCounted(false);lock.acquire();}catch(Exception ignored){}
   udpDiscovery=LanDiscovery.listen(announcement->{
    if(!alive||announcement==null||announcement.host==null||announcement.port<1)return;
    // UDP discovery is independent from TCP/TLS. Show a valid peer immediately;
    // secure identity and TLS are checked when the user actually connects.
    if(DeviceIdentity.id(this).equals(announcement.id))return;
    android.util.Log.i("QuanLianDiscovery","UDP peer discovered id="+announcement.id
      +", name="+announcement.name+", source="+announcement.host.getHostAddress()
      +", tcpPort="+announcement.port+", fingerprint="+announcement.fingerprint);
    runOnUiThread(()->{
     if(!alive)return;
     synchronized(devices){
      Device device=devices.get(announcement.id);
      if(device==null){
       device=new Device(announcement.name,announcement.id,announcement.host,announcement.port,announcement.fingerprint);
       devices.put(device.key,device);
      }
      device.name=announcement.name;
      device.host=announcement.host;
      device.port=announcement.port;
      device.fingerprint=announcement.fingerprint;
      device.udpSeen=true;
      device.udpLastSeen=android.os.SystemClock.elapsedRealtime();
     }
     if(deviceStatus!=null)deviceStatus.setText("已通过 UDP 发现附近设备；连接时再验证安全身份");
     refreshDevices();
    });
   });
   new android.os.Handler(getMainLooper()).postDelayed(()->{if(alive&&nsd!=null)discover();},900);
  discoveryHandler.postDelayed(()->{if(alive)runActiveLanScan();},1800);
 }
 private void runActiveLanScan(){
  if(!alive)return;
  int scanPort=port>0?port:38890;
  workers.execute(()->LanScanner.scan(this,scanPort,announcement->runOnUiThread(()->{
   if(!alive||announcement==null||announcement.host==null)return;
   synchronized(devices){Device device=devices.get(announcement.id);if(device==null){device=new Device(announcement.name,announcement.id,announcement.host,announcement.port,announcement.fingerprint);devices.put(device.key,device);}device.name=announcement.name;device.host=announcement.host;device.port=announcement.port;device.fingerprint=announcement.fingerprint;device.udpSeen=true;device.udpLastSeen=android.os.SystemClock.elapsedRealtime();}
   refreshDevices();
  }),status->runOnUiThread(()->{if(deviceStatus!=null&&alive&&devices.isEmpty())deviceStatus.setText(status);}))); 
 }
 @Override protected void onResume(){super.onResume();registerReceiveReceiver();}
 @Override protected void onPause(){unregisterReceiveReceiver();super.onPause();}
 @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
 private void registerReceiveReceiver(){
  if(receiveReceiver!=null)return;
  receiveReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
   if(i==null)return;
   if(ReceiveForegroundService.ACTION_FILES_RECEIVED.equals(i.getAction())){
     if(wallpaperMode)showReceivedWallpaperChoices(i);else showReceiveCompleteDialog(i);
   }else if(ReceiveForegroundService.ACTION_STATUS.equals(i.getAction())){
    port=i.getIntExtra(ReceiveForegroundService.EXTRA_PORT,port);
    if(receiveStatus!=null){String t=i.getStringExtra(ReceiveForegroundService.EXTRA_TEXT);receiveStatus.setText(t==null?"接收服务运行中":t);}
   }else if(ReceiveForegroundService.ACTION_INCOMING.equals(i.getAction())){
    String id=i.getStringExtra(ReceiveForegroundService.EXTRA_ID),sender=i.getStringExtra(ReceiveForegroundService.EXTRA_SENDER);
    int count=i.getIntExtra(ReceiveForegroundService.EXTRA_COUNT,0);long total=i.getLongExtra(ReceiveForegroundService.EXTRA_TOTAL,0);
    AlertDialog receiveDialog=new AlertDialog.Builder(QuanLianActivity.this).setTitle(ui("接收文件请求"))
     .setMessage("发送方："+(sender==null?"附近设备":sender)+"\n文件总数："+count+" 个\n文件总大小："+sizeText(total))
     .setPositiveButton("接受",(d,w)->ReceiveForegroundService.decide(QuanLianActivity.this,id,true))
     .setNegativeButton("拒绝",(d,w)->ReceiveForegroundService.decide(QuanLianActivity.this,id,false))
     .setOnCancelListener(d->ReceiveForegroundService.decide(QuanLianActivity.this,id,false)).create();
    receiveDialog.setOnShowListener(dialog->{
     Button accept=receiveDialog.getButton(AlertDialog.BUTTON_POSITIVE);
     Button reject=receiveDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
     if(accept!=null){accept.setTextColor(Color.WHITE);accept.setTextSize(15);accept.setAllCaps(false);accept.setBackground(shape(0xff20b56b,14));accept.setMinHeight(dp(48));}
     if(reject!=null){reject.setTextColor(Color.WHITE);reject.setTextSize(15);reject.setAllCaps(false);reject.setBackground(shape(0xffe34b4b,14));reject.setMinHeight(dp(48));}
    });
    receiveDialog.show();
   }
  }};
  IntentFilter f=new IntentFilter();f.addAction(ReceiveForegroundService.ACTION_STATUS);f.addAction(ReceiveForegroundService.ACTION_INCOMING);f.addAction(ReceiveForegroundService.ACTION_FILES_RECEIVED);
  if(Build.VERSION.SDK_INT>=33)registerReceiver(receiveReceiver,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(receiveReceiver,f);
 }
 private void unregisterReceiveReceiver(){if(receiveReceiver!=null){try{unregisterReceiver(receiveReceiver);}catch(Exception ignored){}receiveReceiver=null;}}
 private void publish(){
  NsdServiceInfo info=new NsdServiceInfo();info.setServiceName(myName());info.setServiceType(TYPE);info.setPort(port);info.setAttribute("id",DeviceIdentity.id(this));try{info.setAttribute("fp",TlsIdentity.fingerprint(this));}catch(Exception ignored){}
  registration=new NsdManager.RegistrationListener(){public void onServiceRegistered(NsdServiceInfo s){discover();}public void onRegistrationFailed(NsdServiceInfo s,int e){runOnUiThread(()->Toast.makeText(QuanLianActivity.this,"设备广播失败",Toast.LENGTH_SHORT).show());}public void onServiceUnregistered(NsdServiceInfo s){}public void onUnregistrationFailed(NsdServiceInfo s,int e){}};
  try{nsd.registerService(info,NsdManager.PROTOCOL_DNS_SD,registration);}catch(Exception ignored){}
 }
 private boolean isQuanLianService(String serviceType){
  return serviceType!=null && serviceType.toLowerCase(Locale.US).startsWith(TYPE);
 }
 private void discover(){
  if(discovery!=null||!alive||nsd==null)return;
  NsdManager.DiscoveryListener listener=new NsdManager.DiscoveryListener(){
   public void onDiscoveryStarted(String t){}
   public void onServiceFound(NsdServiceInfo service){
    if(!isQuanLianService(service.getServiceType())||myName().equals(service.getServiceName()))return;
    try{nsd.resolveService(service,new NsdManager.ResolveListener(){
     public void onResolveFailed(NsdServiceInfo info,int error){}
     public void onServiceResolved(NsdServiceInfo info){
      InetAddress chosen=info.getHost();
      if(Build.VERSION.SDK_INT>=34){try{for(InetAddress candidate:info.getHostAddresses()){if(candidate instanceof Inet4Address){chosen=candidate;break;}if(chosen==null)chosen=candidate;}}catch(Exception ignored){}}
      if(chosen==null||info.getPort()<1)return;
      String deviceId=null,fp=null;try{byte[] raw=info.getAttributes().get("id");if(raw!=null)deviceId=new String(raw,java.nio.charset.StandardCharsets.UTF_8);byte[] cert=info.getAttributes().get("fp");if(cert!=null)fp=new String(cert,java.nio.charset.StandardCharsets.UTF_8);}catch(Exception ignored){}
      if(deviceId==null||deviceId.length()<8)deviceId="nsd:"+info.getServiceName();if(fp==null||!fp.matches("(?i)[0-9a-f]{64}"))return;
      final String key=deviceId, resolvedName=info.getServiceName(), resolvedFp=fp;
      final InetAddress resolvedHost=chosen;final int resolvedPort=info.getPort();
      workers.execute(()->{
       String error=LanDiscovery.verifyDetailed(QuanLianActivity.this,resolvedHost,resolvedPort,resolvedFp);
       if(error!=null){android.util.Log.e("QuanLianDiscovery","NSD 设备未通过连接验证："+resolvedHost+":"+resolvedPort+"；"+error);runOnUiThread(()->{if(alive&&deviceStatus!=null&&devices.isEmpty())deviceStatus.setText("发现设备但连接验证失败："+error);});return;}
       runOnUiThread(()->{if(!alive)return;synchronized(devices){Device device=devices.get(key);if(device==null){device=new Device(resolvedName,key,resolvedHost,resolvedPort,resolvedFp);devices.put(key,device);}device.name=resolvedName;device.host=resolvedHost;device.port=resolvedPort;device.fingerprint=resolvedFp;device.nsdSeen=true;}refreshDevices();});
      });
     }
    });}catch(Exception ignored){}
   }
   public void onServiceLost(NsdServiceInfo service){synchronized(devices){Iterator<Map.Entry<String,Device>> it=devices.entrySet().iterator();while(it.hasNext()){Device d=it.next().getValue();if(d.name.equals(service.getServiceName())){d.nsdSeen=false;if(!d.udpSeen)it.remove();}}}runOnUiThread(()->refreshDevices());}
   public void onDiscoveryStopped(String type){if(discovery==this)discovery=null;}
   public void onStartDiscoveryFailed(String type,int error){try{nsd.stopServiceDiscovery(this);}catch(Exception ignored){}if(discovery==this)discovery=null;new android.os.Handler(getMainLooper()).postDelayed(()->{if(alive)discover();},2000);}
   public void onStopDiscoveryFailed(String type,int error){try{nsd.stopServiceDiscovery(this);}catch(Exception ignored){}if(discovery==this)discovery=null;new android.os.Handler(getMainLooper()).postDelayed(()->{if(alive)discover();},2000);}
  };
  discovery=listener;
  try{nsd.discoverServices(TYPE,NsdManager.PROTOCOL_DNS_SD,listener);}catch(Exception error){if(discovery==listener)discovery=null;new android.os.Handler(getMainLooper()).postDelayed(()->{if(alive)discover();},2000);}
 }
 private void refreshDiscovery(){
  synchronized(devices){devices.clear();}
  refreshDevices();
  if(deviceStatus!=null)deviceStatus.setText("正在重新搜索局域网设备…");
  NsdManager.DiscoveryListener old=discovery;
  discovery=null;
  if(old!=null){try{nsd.stopServiceDiscovery(old);}catch(Exception ignored){}}
  new android.os.Handler(getMainLooper()).postDelayed(()->{
   if(!alive||nsd==null)return;
   discover();
   runActiveLanScan();
  },600);
 }
 private final Runnable pruneDevices=new Runnable(){@Override public void run(){if(!alive)return;long now=android.os.SystemClock.elapsedRealtime();synchronized(devices){Iterator<Map.Entry<String,Device>> it=devices.entrySet().iterator();while(it.hasNext()){Device d=it.next().getValue();if(d.udpSeen&&now-d.udpLastSeen>9000)d.udpSeen=false;if(!d.udpSeen&&!d.nsdSeen)it.remove();}}refreshDevices();discoveryHandler.postDelayed(this,3000);}};
 private void refreshDevices(){
  if(deviceList==null)return;deviceList.removeAllViews();ArrayList<Device> list;
  synchronized(devices){list=new ArrayList<>(devices.values());}
  if(deviceStatus!=null)deviceStatus.setText("发现 "+list.size()+" 台附近设备");
  if(list.isEmpty()){deviceList.addView(homeText("暂未发现设备。请让双方打开小李全联并连接同一 Wi-Fi。",13,0xff71809e,false));return;}
  for(Device d:list){LinearLayout item=new LinearLayout(this);item.setOrientation(LinearLayout.HORIZONTAL);item.setGravity(Gravity.CENTER_VERTICAL);item.setPadding(dp(12),dp(12),dp(12),dp(12));item.setBackground(shape(0xfff4f7ff,14));LinearLayout labels=new LinearLayout(this);labels.setOrientation(LinearLayout.VERTICAL);labels.addView(homeText(d.name,15,0xff17264a,true));labels.addView(homeText(d.udpSeen?"UDP 在线":"局域网发现",11,0xff71809e,false));item.addView(labels,new LinearLayout.LayoutParams(0,-2,1));TextView fav=homeText("☆",22,0xffe2a52b,true);fav.setGravity(Gravity.CENTER);fav.setPadding(dp(10),dp(4),dp(10),dp(4));fav.setOnClickListener(v->{if(d.host!=null)FavoriteDevices.add(this,d.name,d.host.getHostAddress(),d.port,d.fingerprint);Toast.makeText(this,"已收藏设备",Toast.LENGTH_SHORT).show();});item.addView(fav);item.setClickable(true);item.setFocusable(true);item.setOnClickListener(v->{if(!uris.isEmpty())send(d);else{Toast.makeText(this,"请先添加要发送的文件",Toast.LENGTH_SHORT).show();sendPage();}});LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(8);deviceList.addView(item,p);}
 }

 private void receive(Socket socket){
  try(Socket s=socket;DataInputStream in=new DataInputStream(new BufferedInputStream(s.getInputStream()));DataOutputStream out=new DataOutputStream(new BufferedOutputStream(s.getOutputStream()))){
   s.setSoTimeout(180000);runOnUiThread(()->Toast.makeText(this,"收到设备连接，正在读取发送请求…",Toast.LENGTH_SHORT).show());if(!"QUANLIAN2".equals(in.readUTF()))throw new IOException("传输协议不匹配");String sender=in.readUTF();int count=in.readInt();if(count<1||count>500)return;
   ArrayList<String> fileNames=new ArrayList<>();ArrayList<Long> fileSizes=new ArrayList<>();ArrayList<String> hashes=new ArrayList<>();long total=0;
   for(int i=0;i<count;i++){String name=in.readUTF();long size=in.readLong();String hash=in.readUTF();if(size<0||hash.length()!=64||total>Long.MAX_VALUE-size)return;fileNames.add(name);fileSizes.add(size);hashes.add(hash);total+=size;}
   final long totalBytes=total;CountDownLatch latch=new CountDownLatch(1);final boolean[] yes={false};
   runOnUiThread(()->new AlertDialog.Builder(this).setTitle("接收文件请求").setMessage(sender+" 要发送 "+count+" 个文件（"+sizeText(totalBytes)+"）。是否接收？").setPositiveButton("接收",(a,b)->{yes[0]=true;latch.countDown();}).setNegativeButton("拒绝",(a,b)->latch.countDown()).setOnCancelListener(a->latch.countDown()).show());
   boolean accepted=latch.await(90,TimeUnit.SECONDS)&&yes[0];out.writeBoolean(accepted);out.flush();if(!accepted){if(latch.getCount()==0&&!yes[0])runOnUiThread(()->Toast.makeText(this,"已拒绝或取消接收",Toast.LENGTH_SHORT).show());else runOnUiThread(()->Toast.makeText(this,"等待接收确认超时",Toast.LENGTH_LONG).show());return;}
   File dir=new File(getCacheDir(),"QuanLianIncoming");if(!dir.exists()&&!dir.mkdirs())throw new IOException("无法创建临时接收目录");
   byte[] buf=new byte[BUF];
   for(int i=0;i<count;i++){
    String safe=fileNames.get(i).replaceAll("[\\\\/:*?\"<>|]","_").trim();if(safe.isEmpty()||safe.equals(".")||safe.equals(".."))safe="received_file_"+i;
    File temp=new File(dir,".quanlian_"+UUID.randomUUID()+".part");MessageDigest digest=MessageDigest.getInstance("SHA-256");long left=fileSizes.get(i);
    try(FileOutputStream fos=new FileOutputStream(temp)){while(left>0){int n=in.read(buf,0,(int)Math.min(buf.length,left));if(n<0)throw new EOFException("文件未传完");fos.write(buf,0,n);digest.update(buf,0,n);left-=n;}fos.getFD().sync();}
    String actual=hex(digest.digest());if(!actual.equalsIgnoreCase(hashes.get(i))){temp.delete();throw new IOException("文件校验失败："+safe);}
    Uri saved=publishReceivedFile(temp,safe);temp.delete();if(saved==null)throw new IOException("无法保存到手机 Download/小李全联 目录："+safe);addHistory("接收",safe,fileSizes.get(i),sender,saved.toString());
   }
   out.writeBoolean(true);out.flush();runOnUiThread(()->Toast.makeText(this,"接收完成，文件已通过完整性校验",Toast.LENGTH_LONG).show());
  }catch(Exception e){runOnUiThread(()->Toast.makeText(this,"接收失败："+e.getMessage(),Toast.LENGTH_LONG).show());}
 }
 private static class TransferFile { File file; String name,hash; long size; TransferFile(File f,String n,long z,String h){file=f;name=n;size=z;hash=h;} }
 private void send(Device d){
  if(uris.isEmpty()){Toast.makeText(this,"请先选择文件",Toast.LENGTH_SHORT).show();return;}
  if(!batchSending&&"multiple".equals(getSharedPreferences("ui_settings",MODE_PRIVATE).getString("send_mode","single"))){
   String saved=getSharedPreferences("ui_settings",MODE_PRIVATE).getString("selected_receivers","");
   if(saved!=null&&!saved.trim().isEmpty()){
    ArrayList<Device> targets=new ArrayList<>();Set<String> keys=new HashSet<>(Arrays.asList(saved.split(",")));
    synchronized(devices){for(Device candidate:devices.values())if(keys.contains(candidate.key))targets.add(candidate);}
    if(!targets.isEmpty()){startBatchSend(targets);return;}
   }
  }
  DiagnosticLog.info(this,"SEND_SELECTED","peer="+d.name+", id="+d.key+", target="+d.host.getHostAddress()+":"+d.port+", fingerprint="+d.fingerprint);
  if(d.fingerprint==null||!d.fingerprint.matches("(?i)[0-9a-f]{64}")){Toast.makeText(this,"设备没有有效的 TLS 身份证书，已阻止连接",Toast.LENGTH_LONG).show();return;}
  android.content.SharedPreferences trust=getSharedPreferences("trusted_device_pins",MODE_PRIVATE);
  String pinned=trust.getString(d.key,null);
  if(pinned!=null&&!pinned.equalsIgnoreCase(d.fingerprint)){new AlertDialog.Builder(this).setTitle("设备身份发生变化").setMessage("该设备当前证书与此前保存的身份不同。可能是设备重装或身份恢复，也可能是设备冒充。请先确认对方就是你身边的目标设备，再核对下面的新 SHA-256 指纹：\\n\\n"+d.fingerprint+"\\n\\n只有确认设备身份后，才更新信任记录并继续发送。").setNegativeButton("取消",null).setPositiveButton("确认新身份并发送",(dialog,which)->{DiagnosticLog.info(this,"USER_REPIN_CONFIRMED","peer="+d.name+", id="+d.key+", oldFingerprint="+pinned+", newFingerprint="+d.fingerprint);if(trust.edit().putString(d.key,d.fingerprint).commit())send(d);else Toast.makeText(this,"保存设备信任失败，请重试",Toast.LENGTH_LONG).show();}).show();return;}
  if(pinned==null){new AlertDialog.Builder(this).setTitle("首次连接 · 确认设备身份").setMessage("请确认你信任这台接收设备。TLS 证书 SHA-256 指纹：\\n\\n"+d.fingerprint+"\\n\\n确认后会保存此设备身份，后续若证书变化将拒绝连接。").setNegativeButton("取消",null).setPositiveButton("信任并发送",(dialog,which)->{DiagnosticLog.info(this,"USER_TRUST_CONFIRMED","peer="+d.name+", id="+d.key+", fingerprint="+d.fingerprint);trust.edit().putString(d.key,d.fingerprint).commit();send(d);}).show();return;}
  sendProgress=new ProgressDialog(this);sendProgress.setTitle("小李全联正在发送");sendProgress.setMessage("正在准备文件并计算校验值…");sendProgress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);sendProgress.setMax(1000);sendProgress.setProgress(0);
  sendProgress.setButton(ProgressDialog.BUTTON_NEGATIVE,"取消",(dialog,which)->TransferForegroundService.cancelTransfer(this));
  sendProgress.setCancelable(false);sendProgress.show();
  retryDevice=d;
  registerSendReceiver(d);
  TransferForegroundService.sendFiles(this,new ArrayList<>(uris),new ArrayList<>(names),d.host.getHostAddress(),d.port,d.name,d.fingerprint);
 }

 @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
 private void registerSendReceiver(Device target){
  unregisterSendReceiver();
  sendReceiver=new BroadcastReceiver(){
   @Override public void onReceive(Context context,Intent intent){
    if(intent==null)return;
    if(TransferForegroundService.ACTION_PROGRESS.equals(intent.getAction())){
     if(sendProgress!=null&&sendProgress.isShowing()){
      sendProgress.setMessage(intent.getStringExtra(TransferForegroundService.EXTRA_TEXT));
      sendProgress.setProgress(intent.getIntExtra(TransferForegroundService.EXTRA_PROGRESS,0));
     }
    }else if(TransferForegroundService.ACTION_RESULT.equals(intent.getAction())){
     boolean success=intent.getBooleanExtra(TransferForegroundService.EXTRA_SUCCESS,false);
     boolean cancelled=intent.getBooleanExtra(TransferForegroundService.EXTRA_CANCELLED,false);
     String error=intent.getStringExtra(TransferForegroundService.EXTRA_ERROR);
     if(sendProgress!=null){sendProgress.dismiss();sendProgress=null;}
     unregisterSendReceiver();
     if(success){
      if(batchSending&&!pendingRecipients.isEmpty()){
       Device next=pendingRecipients.removeFirst();Toast.makeText(QuanLianActivity.this,"已发送到 "+target.name+"，正在发送下一台设备",Toast.LENGTH_SHORT).show();send(next);return;
      }
      batchSending=false;pendingRecipients.clear();uris.clear();names.clear();sizes.clear();retryDevice=null;page=0;home();Toast.makeText(QuanLianActivity.this,"发送成功："+target.name+"，全部传输任务已完成",Toast.LENGTH_LONG).show();
     }
     else if(cancelled){batchSending=false;pendingRecipients.clear();Toast.makeText(QuanLianActivity.this,"已取消发送",Toast.LENGTH_SHORT).show();}
     else {batchSending=false;pendingRecipients.clear();DiagnosticLog.error(QuanLianActivity.this,"SEND_RESULT_FAILED",new Exception(error==null?"传输中断":error));new AlertDialog.Builder(QuanLianActivity.this).setTitle("发送失败").setMessage(error==null?"传输中断":error).setNegativeButton("关闭",null).setPositiveButton("重新发送",(dialog,which)->send(target)).show();
    }
   }
  }
  };
  IntentFilter filter=new IntentFilter();
  filter.addAction(TransferForegroundService.ACTION_PROGRESS);
  filter.addAction(TransferForegroundService.ACTION_RESULT);
  if(Build.VERSION.SDK_INT>=33)registerReceiver(sendReceiver,filter,Context.RECEIVER_NOT_EXPORTED);
  else registerReceiver(sendReceiver,filter);
 }

 private void unregisterSendReceiver(){
  if(sendReceiver!=null){try{unregisterReceiver(sendReceiver);}catch(Exception ignored){}sendReceiver=null;}
 }
 private Uri publishReceivedFile(File source,String name)throws Exception{
  if(Build.VERSION.SDK_INT>=29){
   ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);values.put(MediaStore.MediaColumns.MIME_TYPE,"application/octet-stream");values.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/小李全联");values.put(MediaStore.MediaColumns.IS_PENDING,1);
   Uri uri=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);if(uri==null)return null;
   try(OutputStream out=getContentResolver().openOutputStream(uri);InputStream in=new FileInputStream(source)){if(out==null)throw new IOException("无法打开下载目录");byte[] b=new byte[BUF];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
   values.clear();values.put(MediaStore.MediaColumns.IS_PENDING,0);getContentResolver().update(uri,values,null,null);return uri;
  }
  File dir=new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),"小李全联");if(!dir.exists()&&!dir.mkdirs())return null;File dst=new File(dir,System.currentTimeMillis()+"_"+name);try(InputStream in=new FileInputStream(source);OutputStream out=new FileOutputStream(dst)){byte[] b=new byte[BUF];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}return Uri.fromFile(dst);
 }
 private void addHistory(String direction,String name,long size,String peer,String uri){
  try{android.content.SharedPreferences p=getSharedPreferences("transfer_history",MODE_PRIVATE);JSONArray old=new JSONArray(p.getString("items","[]"));JSONArray next=new JSONArray();JSONObject item=new JSONObject();item.put("direction",direction);item.put("name",name);item.put("size",size);item.put("peer",peer);item.put("time",System.currentTimeMillis());item.put("uri",uri);next.put(item);for(int i=0;i<old.length()&&i<199;i++)next.put(old.getJSONObject(i));p.edit().putString("items",next.toString()).apply();}catch(Exception ignored){}
 }
 private String mimeForName(String name){String lower=name==null?"":name.toLowerCase(Locale.ROOT);if(lower.endsWith(".apk"))return "application/vnd.android.package-archive";String mime=java.net.URLConnection.guessContentTypeFromName(name==null?"":name);return mime==null?"application/octet-stream":mime;}
 private void openReceivedFile(String rawUri,String name){
  try{
   Uri uri=Uri.parse(rawUri);
   if("file".equalsIgnoreCase(uri.getScheme()))uri=androidx.core.content.FileProvider.getUriForFile(this,getPackageName()+".fileprovider",new File(uri.getPath()));
   String mime=mimeForName(name);
   if(name!=null&&name.toLowerCase(Locale.ROOT).endsWith(".apk")){
    if(Build.VERSION.SDK_INT>=26&&!getPackageManager().canRequestPackageInstalls()){
     Intent settings=new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName()));startActivity(settings);
     Toast.makeText(this,"请允许小李全联安装应用，然后返回历史记录再次点击安装",Toast.LENGTH_LONG).show();return;
    }
    Intent install=new Intent(Intent.ACTION_INSTALL_PACKAGE);install.setDataAndType(uri,"application/vnd.android.package-archive");install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(install);
   }else{
    String actual=getContentResolver().getType(uri);if(actual==null||"application/octet-stream".equals(actual))actual=mime;
    Intent view=new Intent(Intent.ACTION_VIEW);view.setDataAndType(uri,actual);view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(view);
   }
  }catch(Exception e){Toast.makeText(this,"无法打开文件："+e.getMessage(),Toast.LENGTH_LONG).show();}
 }
 private void showReceiveCompleteDialog(Intent event){
  String sender=event.getStringExtra(ReceiveForegroundService.EXTRA_SENDER);
  String raw=event.getStringExtra(ReceiveForegroundService.EXTRA_FILES);
  JSONArray files;try{files=new JSONArray(raw==null?"[]":raw);}catch(Exception e){files=new JSONArray();}
  if(files.length()==0)return;
  JSONObject first=files.optJSONObject(0);if(first==null)return;
  String firstName=first.optString("name","文件"),firstUri=first.optString("uri","");
  boolean apk=firstName.toLowerCase(Locale.ROOT).endsWith(".apk");
  StringBuilder message=new StringBuilder("已成功接收 ").append(files.length()).append(" 个文件");
  if(sender!=null&&!sender.isEmpty())message.append("\n发送设备：").append(sender);
  message.append("\n\\n");
  for(int x=0;x<files.length()&&x<8;x++){JSONObject f=files.optJSONObject(x);if(f!=null)message.append("• ").append(f.optString("name","文件")).append("\n");}
  if(files.length()>8)message.append("另有 ").append(files.length()-8).append(" 个文件…");
  AlertDialog dialog=new AlertDialog.Builder(this).setTitle("文件接收完成").setMessage(message.toString())
   .setPositiveButton(apk?"安装应用":"打开文件",(d,w)->openReceivedFile(firstUri,firstName))
   .setNeutralButton("打开接收目录",(d,w)->openReceiveFolder())
   .setNegativeButton("完成",null).create();
  dialog.setOnShowListener(d->{
   Button open=dialog.getButton(AlertDialog.BUTTON_POSITIVE),folder=dialog.getButton(AlertDialog.BUTTON_NEUTRAL),done=dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
   if(open!=null){open.setTextColor(Color.WHITE);open.setAllCaps(false);open.setBackground(shape(0xff2878f5,14));}
   if(folder!=null){folder.setTextColor(0xff2878f5);folder.setAllCaps(false);}
   if(done!=null){done.setTextColor(0xff71809e);done.setAllCaps(false);}
  });
  if(!isFinishing())dialog.show();
 }
 private void showReceivedWallpaperChoices(Intent intent){
  String raw=intent.getStringExtra(ReceiveForegroundService.EXTRA_FILES);
  JSONArray files;try{files=new JSONArray(raw==null?"[]":raw);}catch(Exception e){files=new JSONArray();}
  ArrayList<String> labels=new ArrayList<>(),values=new ArrayList<>();
  for(int index=0;index<files.length();index++){
   JSONObject item=files.optJSONObject(index);if(item==null)continue;
   String name=item.optString("name","图片"),value=item.optString("uri","");
   if(value.isEmpty())continue;
   Uri uri=Uri.parse(value);String mime=getContentResolver().getType(uri);
   if(mime==null)mime=item.optString("mime","");
   if(mime.startsWith("image/")){labels.add(name);values.add(value);}
  }
  if(labels.isEmpty()){
   Toast.makeText(this,"本次接收没有图片文件，无法设置壁纸",Toast.LENGTH_LONG).show();
   return;
  }
  new AlertDialog.Builder(this).setTitle("选择要设置的桌面壁纸")
   .setMessage("已接收 "+labels.size()+" 张图片。选择一张应用到小李桌面。")
   .setItems(labels.toArray(new String[0]),(dialog,which)->{
    Uri uri=Uri.parse(values.get(which));
    getSharedPreferences("launcher",MODE_PRIVATE).edit().putString("wallpaper_uri",uri.toString()).apply();
    stopService(new Intent(this,ReceiveForegroundService.class));
    Toast.makeText(this,"桌面壁纸已更新",Toast.LENGTH_LONG).show();
    finish();
   }).setNegativeButton("暂不设置",null).show();
 }
 private void openReceiveFolder(){
  try{
   Intent view=new Intent(Intent.ACTION_VIEW);
   view.setDataAndType(android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:Download/小李全联"),"vnd.android.document/directory");
   view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
   try{startActivity(view);return;}catch(Exception ignored){}
   Intent tree=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
   tree.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
   tree.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
    android.provider.DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents","primary:Download/小李全联"));
   startActivityForResult(tree,1044);
  }catch(Exception e){Toast.makeText(this,"无法打开接收目录，请使用文件管理器进入 Download/小李全联",Toast.LENGTH_LONG).show();}
 }
 private void handleIncomingShare(Intent intent){
  if(intent==null)return;String action=intent.getAction();int before=uris.size();
  try{
   boolean usedStream=false;
   if(Intent.ACTION_SEND_MULTIPLE.equals(action)){
    ArrayList<Uri> shared=intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);if(shared!=null){for(Uri uri:shared)if(uri!=null)addFile(uri);usedStream=!shared.isEmpty();}
   }else if(Intent.ACTION_SEND.equals(action)){
    Uri shared=intent.getParcelableExtra(Intent.EXTRA_STREAM);if(shared!=null){addFile(shared);usedStream=true;}
    else{CharSequence text=intent.getCharSequenceExtra(Intent.EXTRA_TEXT);if(text!=null&&text.length()>0)addTextAsFile(text.toString(),"分享文本.txt");}
   }
   if(!usedStream&&intent.getClipData()!=null)for(int i=0;i<intent.getClipData().getItemCount();i++){Uri u=intent.getClipData().getItemAt(i).getUri();if(u!=null)addFile(u);}
  }catch(Exception e){android.util.Log.w("QuanLianShare","Unable to import shared content",e);}
  if(uris.size()>before){page=1;sendPage();}
 }
 @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleIncomingShare(intent);}
 private void historyPage(){
  page=4;LinearLayout l=col();l.setBackgroundColor(pageBackground());lightHeader(l,"传输历史记录","查看已发送和已接收的文件");JSONArray items;try{items=new JSONArray(getSharedPreferences("transfer_history",MODE_PRIVATE).getString("items","[]"));}catch(Exception e){items=new JSONArray();}
  if(items.length()==0)l.addView(txt("暂时没有传输记录",14,0xff8491a8,false));
  for(int i=0;i<items.length();i++){JSONObject o=items.optJSONObject(i);if(o==null)continue;LinearLayout item=homeCard();item.setPadding(dp(15),dp(11),dp(15),dp(11));String name=o.optString("name","文件");String direction=o.optString("direction","");String peer=o.optString("peer","");long size=o.optLong("size",0);long time=o.optLong("time",0);item.addView(homeText((direction.equals("接收")?"↓ 已接收  ":"↑ 已发送  ")+name,14,0xff1b2947,true));gap(item,3);item.addView(homeText(sizeText(size)+" · "+peer,11,0xff71809e,false));gap(item,2);item.addView(homeText(new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.getDefault()).format(new Date(time)),10,0xff91a0b5,false));
   if(direction.equals("接收")){Button open=btn("打开文件",0xff1f77dc);String uri=o.optString("uri","");open.setOnClickListener(v->openReceivedFile(uri,name));item.addView(open);}
   LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(7);l.addView(item,p);
  }
  Button clear=btn("清空历史记录",0xff304b70);clear.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("清空历史记录").setMessage("只清除记录，不会删除已传输的文件。").setNegativeButton("取消",null).setPositiveButton("清空",(a,b)->{getSharedPreferences("transfer_history",MODE_PRIVATE).edit().clear().apply();historyPage();}).show());l.addView(clear);install(l);
 }

 private String etaText(long seconds){if(seconds<60)return seconds+" 秒";if(seconds<3600)return (seconds/60)+" 分钟 "+(seconds%60)+" 秒";return (seconds/3600)+" 小时 "+((seconds%3600)/60)+" 分钟";}
 private static String hex(byte[] bytes){StringBuilder b=new StringBuilder(bytes.length*2);for(byte v:bytes)b.append(String.format(Locale.US,"%02x",v&0xff));return b.toString();}
 private void lightHeader(LinearLayout l,String title,String sub){TextView back=homeText("‹  返回首页",15,0xff41618e,true);back.setPadding(0,0,0,dp(18));back.setOnClickListener(v->home());l.addView(back);l.addView(homeText(title,25,0xff182747,true));gap(l,6);l.addView(homeText(sub,14,0xff8491a8,false));gap(l,20);}
 private TextView homeText(String value,int size,int color,boolean bold){if(isDarkTheme()){if(color==0xff1b2947||color==0xff182747||color==0xff263957||color==0xff17264a)color=0xffedf3ff;else if(color==0xff8491a8||color==0xff71809e||color==0xff8190a8||color==0xff91a0b5)color=0xffaab7ca;}if(color==0xff1769e8||color==0xff2878f5)color=accentColor();TextView t=txt(value,size,color,bold);t.setIncludeFontPadding(false);return t;}
 private LinearLayout homeCard(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(17),dp(16),dp(17),dp(16));GradientDrawable bg=shape(surfaceColor(),22);bg.setStroke(dp(1),isDarkTheme()?0xff2c3b52:0xffe8eef8);c.setBackground(bg);c.setElevation(dp(2));return c;}
 private GradientDrawable homeGradient(int start,int end,int radius){GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{start,end});d.setCornerRadius(dp(radius));return d;}
 private void homeNavItem(LinearLayout nav,String label,boolean selected,Runnable action){TextView t=homeText(label,14,selected?0xff1769e8:0xff62718c,selected);t.setGravity(Gravity.CENTER_VERTICAL);t.setPadding(dp(14),dp(13),dp(14),dp(13));t.setBackground(selected?shape(0xffedf4ff,14):shape(Color.TRANSPARENT,14));t.setFocusable(isTvMode());t.setClickable(true);t.setOnClickListener(v->action.run());if(isTvMode())attachTvFocus(t,selected?0xff2878f5:accentColor());LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(7);nav.addView(t,p);}
 private TextView homeNavLabel(String label,boolean selected){TextView t=homeText(label,12,selected?0xff315b91:0xff74839a,selected);t.setGravity(Gravity.CENTER);t.setPadding(dp(5),dp(13),dp(5),dp(13));t.setBackground(selected?shape(0xffedf4ff,18):shape(Color.TRANSPARENT,18));return t;}
 private void home(){receivePage();}
 private Spinner settingSpinner(LinearLayout card,String label,String[] values,int selected){card.addView(homeText(label,15,0xff1b2947,true));gap(card,6);Spinner spinner=new Spinner(this);String[] translated=values.clone();for(int i=0;i<translated.length;i++)translated[i]=ui(translated[i]);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,translated);spinner.setAdapter(adapter);spinner.setSelection(Math.max(0,Math.min(selected,values.length-1)));card.addView(spinner);gap(card,12);return spinner;}
 private Switch settingSwitch(LinearLayout card,String label,boolean checked){LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.addView(homeText(label,14,0xff263957,true),new LinearLayout.LayoutParams(0,-2,1));Switch toggle=new Switch(this);toggle.setChecked(checked);row.addView(toggle);card.addView(row);gap(card,8);return toggle;}
 private void infoPage(String title,String body){new AlertDialog.Builder(this).setTitle(ui(title)).setMessage(ui(body)).setPositiveButton("知道了",null).show();}
 private void settingsPage(){
  page=6;
  android.content.SharedPreferences prefs=getSharedPreferences("ui_settings",MODE_PRIVATE);
  LinearLayout l=col();
  l.setBackgroundColor(pageBackground());
  header(l,"设置","字体与语言");
  LinearLayout card=homeCard();
  card.addView(homeText("显示设置",18,0xff1b2947,true));
  gap(card,12);
  String lv=prefs.getString("language",prefs.getBoolean("english",false)?"en":"system");
  Spinner language=settingSpinner(card,"语言",new String[]{"跟随系统","简体中文","English"},"zh".equals(lv)?1:("en".equals(lv)?2:0));
  Spinner fontSize=settingSpinner(card,"字体大小",new String[]{"小","标准","大"},prefs.getInt("font_scale",1));
  l.addView(card);
  gap(l,16);
  Button save=btn("保存",0xff2878f5);
  save.setOnClickListener(v->{
   String[] langs={"system","zh","en"};
   prefs.edit()
    .putString("language",langs[language.getSelectedItemPosition()])
    .putBoolean("english",language.getSelectedItemPosition()==2)
    .putInt("font_scale",fontSize.getSelectedItemPosition())
    .apply();
   applyWindowTheme();
   Toast.makeText(this,ui("设置已保存"),Toast.LENGTH_SHORT).show();
   settingsPage();
  });
  l.addView(save);
  installFlow(l,2);
 }private void diagnosticsPage(){
  page=5;LinearLayout l=col();l.setBackgroundColor(pageBackground());lightHeader(l,"故障诊断与日志导出","连接失败后，发送端和接收端分别导出");
  l.addView(homeText("操作步骤",18,0xff1b2947,true));gap(l,8);l.addView(homeText("1. 两台手机都安装带有此功能的版本。\n2. 先分别打开本页面，确认旧日志已清空。\n3. 返回首页，重现一次发送失败。\n4. 两台手机分别回到本页面，点击“复制完整日志”或“分享日志”。\n5. 把两份日志都发给开发者。\n\n日志包含 TCP、TLS、证书校验、接收请求及异常堆栈，不记录文件内容。",14,0xff52627a,false));
  gap(l,16);Button copy=btn("复制完整日志",0xff1f77dc);copy.setOnClickListener(v->{((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("小李全联诊断日志",DiagnosticLog.read(this)));Toast.makeText(this,"日志已复制，可以直接粘贴发送",Toast.LENGTH_LONG).show();});l.addView(copy);
  gap(l,8);Button share=btn("分享 / 导出日志",0xff1f9e8f);share.setOnClickListener(v->{Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_SUBJECT,"小李全联故障诊断日志");i.putExtra(Intent.EXTRA_TEXT,DiagnosticLog.read(this));startActivity(Intent.createChooser(i,"导出诊断日志"));});l.addView(share);
  gap(l,8);Button clear=btn("清空旧日志",0xff304b70);clear.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("清空诊断日志").setMessage("清空后再重现一次故障，便于准确定位。").setNegativeButton("取消",null).setPositiveButton("清空",(d,w)->{DiagnosticLog.clear(this);Toast.makeText(this,"旧日志已清空",Toast.LENGTH_SHORT).show();diagnosticsPage();}).show());l.addView(clear);
  gap(l,12);l.addView(homeText("注意：两台手机都要导出。只有发送端日志，无法完整判断接收端是否收到连接。",12,0xffb07824,false));install(l);
 }

 private void pick(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,PICK);}
 @Override protected void onActivityResult(int r,int c,Intent d){
  super.onActivityResult(r,c,d);if(c!=RESULT_OK||d==null)return;
  if(r==1004&&d.getData()!=null){Uri tree=d.getData();try{getContentResolver().takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);}catch(Exception ignored){}getSharedPreferences("ui_settings",MODE_PRIVATE).edit().putString("save_tree_uri",tree.toString()).apply();Toast.makeText(this,"自定义保存目录已保存",Toast.LENGTH_SHORT).show();settingsPage();return;}
  if(r==1003&&d.getData()!=null){
   Uri tree=d.getData();try{getContentResolver().takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
   try{String rootId=android.provider.DocumentsContract.getTreeDocumentId(tree);int[] count={0};addFolderChildren(tree,rootId,count);Toast.makeText(this,"已添加文件夹中的 "+count[0]+" 个文件",Toast.LENGTH_LONG).show();}catch(Exception e){Toast.makeText(this,"读取文件夹失败："+e.getMessage(),Toast.LENGTH_LONG).show();}
   sendPage();return;
  }
  if(r!=PICK)return;
  if(d.getClipData()!=null){for(int i=0;i<d.getClipData().getItemCount();i++)addFile(d.getClipData().getItemAt(i).getUri());}
  else if(d.getData()!=null)addFile(d.getData());
  sendPage();
 }
 private void addFile(Uri u){try{getContentResolver().takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}String n="文件";long z=-1;try(Cursor c=getContentResolver().query(u,null,null,null,null)){if(c!=null&&c.moveToFirst()){int ni=c.getColumnIndex(OpenableColumns.DISPLAY_NAME),si=c.getColumnIndex(OpenableColumns.SIZE);if(ni>=0)n=c.getString(ni);if(si>=0&&!c.isNull(si))z=c.getLong(si);}}catch(Exception ignored){}uris.add(u);names.add(n);sizes.add(z);}
 private String sizeText(long n){if(n<0)return "大小未知";if(n<1024)return n+" B";if(n<1048576)return String.format(Locale.getDefault(),"%.1f KB",n/1024d);if(n<1073741824)return String.format(Locale.getDefault(),"%.1f MB",n/1048576d);return String.format(Locale.getDefault(),"%.2f GB",n/1073741824d);}
 private void selectedPage(){sendPage();}
  private void devicePage(){sendPage();}
  private void showEditSelectedFiles(){
  if(uris.isEmpty())return;
  String[] labels=names.toArray(new String[0]);
  new AlertDialog.Builder(this).setTitle(ui("编辑已选文件"))
   .setItems(labels,(dialog,which)->{
    if(which>=0&&which<uris.size()){
     uris.remove(which);names.remove(which);sizes.remove(which);
     sendPage();
    }
   }).setNegativeButton(ui("完成"),null).show();
 }
 private void sendPage(){
   page=1;deviceList=null;deviceStatus=null;
   LinearLayout l=col();l.setBackgroundColor(pageBackground());
   LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);
   LinearLayout titles=new LinearLayout(this);titles.setOrientation(LinearLayout.VERTICAL);
   titles.addView(homeText("发送文件",25,0xff182747,true));gap(titles,4);
   titles.addView(homeText("选择要分享的内容",12,0xff8491a8,false));
   top.addView(titles,new LinearLayout.LayoutParams(0,-2,1));l.addView(top);gap(l,isTvMode()?8:14);

   if(uris.isEmpty()){
    if(isTvMode()){
     LinearLayout categories=new LinearLayout(this);categories.setOrientation(LinearLayout.VERTICAL);
     LinearLayout rowOne=new LinearLayout(this);rowOne.setOrientation(LinearLayout.HORIZONTAL);
     LinearLayout rowTwo=new LinearLayout(this);rowTwo.setOrientation(LinearLayout.HORIZONTAL);
     addSendCategory(rowOne,"▤","文件",()->pick());
     addSendCategory(rowOne,"▧","媒体",()->pickMedia());
     addSendCategory(rowOne,"▣","剪贴板",()->addClipboardText());
     addSendCategory(rowTwo,"T","文本",()->createTextFile());
     addSendCategory(rowTwo,"▱","文件夹",()->pickFolder());
     addSendCategory(rowTwo,"APK","应用",()->pickApk());
     for(int i=0;i<3;i++){
      View upper=rowOne.getChildAt(i),lower=rowTwo.getChildAt(i);
      upper.setNextFocusLeftId(i>0?rowOne.getChildAt(i-1).getId():upper.getId());
      upper.setNextFocusRightId(i<2?rowOne.getChildAt(i+1).getId():upper.getId());
      upper.setNextFocusDownId(lower.getId());upper.setNextFocusUpId(upper.getId());
      lower.setNextFocusLeftId(i>0?rowTwo.getChildAt(i-1).getId():lower.getId());
      lower.setNextFocusRightId(i<2?rowTwo.getChildAt(i+1).getId():lower.getId());
      lower.setNextFocusUpId(upper.getId());lower.setNextFocusDownId(lower.getId());
     }
     categories.addView(rowOne,new LinearLayout.LayoutParams(-1,dp(112)));
     gap(categories,7);
     categories.addView(rowTwo,new LinearLayout.LayoutParams(-1,dp(112)));
     l.addView(categories,new LinearLayout.LayoutParams(-1,-2));
    }else{
     android.widget.GridLayout categories=new android.widget.GridLayout(this);
     categories.setColumnCount(3);categories.setRowCount(2);categories.setUseDefaultMargins(false);
     addSendCategory(categories,"▤","文件",()->pick());
     addSendCategory(categories,"▧","媒体",()->pickMedia());
     addSendCategory(categories,"▣","剪贴板",()->addClipboardText());
     addSendCategory(categories,"T","文本",()->createTextFile());
     addSendCategory(categories,"▱","文件夹",()->pickFolder());
     addSendCategory(categories,"APK","应用",()->pickApk());
     l.addView(categories,new LinearLayout.LayoutParams(-1,-2));
    }
   }else{
    LinearLayout preview=homeCard();
    LinearLayout previewHead=new LinearLayout(this);previewHead.setGravity(Gravity.CENTER_VERTICAL);
    previewHead.addView(homeText("已选择的文件",18,0xff1b2947,true),new LinearLayout.LayoutParams(0,-2,1));
    TextView edit=homeText("编辑",13,0xff41618e,true);
    edit.setPadding(dp(10),dp(7),dp(10),dp(7));
    edit.setOnClickListener(v->showEditSelectedFiles());
    previewHead.addView(edit);
    TextView add=homeText("+ 添加",13,0xff1769e8,true);
    add.setPadding(dp(10),dp(7),dp(10),dp(7));
    add.setBackground(shape(0xffedf4ff,12));
    add.setOnClickListener(v->pick());
    previewHead.addView(add);preview.addView(previewHead);gap(preview,10);
    long total=0;for(long z:sizes)if(z>0)total+=z;
    preview.addView(homeText("文件："+uris.size()+"    大小："+sizeText(total),12,0xff8491a8,false));gap(preview,9);
    int screenDp=(int)(getResources().getDisplayMetrics().widthPixels/getResources().getDisplayMetrics().density);
    android.widget.GridLayout fileGrid=new android.widget.GridLayout(this);
    fileGrid.setColumnCount(screenDp>=700?6:(screenDp<360?4:5));
    fileGrid.setUseDefaultMargins(false);
    for(int i=0;i<uris.size();i++){
     final int index=i;
     LinearLayout fileItem=new LinearLayout(this);fileItem.setOrientation(LinearLayout.VERTICAL);fileItem.setGravity(Gravity.CENTER);
     fileItem.setPadding(dp(3),dp(5),dp(3),dp(5));
     android.widget.FrameLayout iconBox=new android.widget.FrameLayout(this);
     iconBox.setBackground(shape(0xffedf4ff,15));
     android.widget.GridLayout.LayoutParams cell=new android.widget.GridLayout.LayoutParams();
     cell.width=0;cell.height=-2;
     cell.columnSpec=android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED,1f);
     cell.setMargins(dp(2),dp(2),dp(2),dp(5));
     iconBox.setLayoutParams(new LinearLayout.LayoutParams(dp(54),dp(54)));
     ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);
     iconBox.addView(image,new android.widget.FrameLayout.LayoutParams(-1,-1));
     String mime=null;try{mime=getContentResolver().getType(uris.get(i));}catch(Exception ignored){}
     boolean visual=mime!=null&&(mime.startsWith("image/")||mime.startsWith("video/"));
     if(visual){
      try{
       if(Build.VERSION.SDK_INT>=29)image.setImageBitmap(getContentResolver().loadThumbnail(uris.get(i),new android.util.Size(dp(108),dp(108)),null));
       else if(mime.startsWith("image/"))image.setImageURI(uris.get(i));
       else{TextView fallback=homeText("▶",23,0xff347bd2,true);fallback.setGravity(Gravity.CENTER);iconBox.addView(fallback,new android.widget.FrameLayout.LayoutParams(-1,-1));}
      }catch(Exception ex){TextView fallback=homeText(mime.startsWith("video/")?"▶":"▧",23,0xff347bd2,true);fallback.setGravity(Gravity.CENTER);iconBox.addView(fallback,new android.widget.FrameLayout.LayoutParams(-1,-1));}
     }else{
      String symbol=mime!=null&&mime.contains("pdf")?"PDF":(mime!=null&&mime.contains("audio")?"♫":(mime!=null&&mime.contains("text")?"TXT":"▤"));
      TextView fallback=homeText(symbol,18,0xff347bd2,true);fallback.setGravity(Gravity.CENTER);
      iconBox.addView(fallback,new android.widget.FrameLayout.LayoutParams(-1,-1));
     }
     TextView remove=homeText("×",15,Color.WHITE,true);remove.setGravity(Gravity.CENTER);remove.setBackground(shape(0xffe45b64,20));
     android.widget.FrameLayout.LayoutParams removePos=new android.widget.FrameLayout.LayoutParams(dp(20),dp(20),Gravity.RIGHT|Gravity.TOP);
     iconBox.addView(remove,removePos);
     remove.setOnClickListener(v->{uris.remove(index);names.remove(index);sizes.remove(index);sendPage();});
     fileItem.addView(iconBox);
     TextView fileName=homeText(names.get(i),10,0xff263957,true);fileName.setGravity(Gravity.CENTER);
     fileName.setMaxLines(1);fileName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
     LinearLayout.LayoutParams nameLp=new LinearLayout.LayoutParams(-1,-2);nameLp.topMargin=dp(5);
     fileItem.addView(fileName,nameLp);
     TextView fileSize=homeText(sizeText(sizes.get(i)),9,0xff8491a8,false);fileSize.setGravity(Gravity.CENTER);
     fileItem.addView(fileSize);
     fileGrid.addView(fileItem,cell);
    }
    preview.addView(fileGrid);
    l.addView(preview);
   }
   gap(l,14);
   LinearLayout target=homeCard();
   LinearLayout targetHead=new LinearLayout(this);targetHead.setGravity(Gravity.CENTER_VERTICAL);
   targetHead.addView(homeText("附近设备",18,0xff1b2947,true),new LinearLayout.LayoutParams(0,-2,1));
   TextView refresh=homeText("↻",22,0xff1769e8,true);refresh.setGravity(Gravity.CENTER);
   refresh.setContentDescription("刷新设备");refresh.setPadding(dp(10),dp(6),dp(10),dp(6));
   refresh.setOnClickListener(v->refreshDiscovery());targetHead.addView(refresh);
   TextView connect=homeText("◎",21,0xff41618e,true);connect.setGravity(Gravity.CENTER);
   connect.setContentDescription("连接设备");connect.setPadding(dp(10),dp(6),dp(10),dp(6));
   connect.setOnClickListener(v->manualConnect());targetHead.addView(connect);
   TextView favorites=homeText("♥",20,0xffd99b22,true);favorites.setGravity(Gravity.CENTER);
   favorites.setContentDescription("收藏设备");favorites.setPadding(dp(10),dp(6),dp(10),dp(6));
   favorites.setOnClickListener(v->showFavorites());targetHead.addView(favorites);
   TextView options=homeText("⚙",20,0xff41618e,true);options.setGravity(Gravity.CENTER);
   options.setContentDescription("发送设置");options.setPadding(dp(10),dp(6),dp(10),dp(6));
   options.setOnClickListener(v->showSendOptions());targetHead.addView(options);
   target.addView(targetHead);gap(target,8);
   deviceStatus=homeText("正在搜索附近设备…",12,0xff8491a8,false);target.addView(deviceStatus);gap(target,8);
   deviceList=new LinearLayout(this);deviceList.setOrientation(LinearLayout.VERTICAL);
   target.addView(deviceList);l.addView(target);refreshDevices();
   installFlow(l,1);
  }
  private void attachTvFocus(View view,int accent){
   view.setFocusable(true);view.setFocusableInTouchMode(false);view.setClickable(true);
   final android.graphics.drawable.Drawable normalBackground=view.getBackground();
   view.setOnFocusChangeListener((v,hasFocus)->{
    if(hasFocus){GradientDrawable focused=shape(isDarkTheme()?0xff203b60:0xffedf5ff,18);focused.setStroke(dp(3),accent);v.setBackground(focused);v.setElevation(dp(14));v.setScaleX(1.05f);v.setScaleY(1.05f);v.setTranslationZ(dp(8));if(Build.VERSION.SDK_INT>=28){v.setOutlineAmbientShadowColor(accent);v.setOutlineSpotShadowColor(accent);}
    }else{v.setBackground(normalBackground);v.setElevation(dp(2));v.setScaleX(1f);v.setScaleY(1f);v.setTranslationZ(0);if(Build.VERSION.SDK_INT>=28){int shadow=isDarkTheme()?0xff000000:0xff64748b;v.setOutlineAmbientShadowColor(shadow);v.setOutlineSpotShadowColor(shadow);}}
   });
   view.setOnKeyListener((v,keyCode,event)->{
    if((keyCode==android.view.KeyEvent.KEYCODE_DPAD_CENTER||keyCode==android.view.KeyEvent.KEYCODE_ENTER||keyCode==android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)){
     if(event.getAction()==android.view.KeyEvent.ACTION_UP){v.performClick();return true;}return true;
    }
    return false;
   });
  }
  private class AdaptiveFeatureCard extends LinearLayout{
   AdaptiveFeatureCard(String label,Runnable action){super(QuanLianActivity.this);setOrientation(LinearLayout.VERTICAL);setGravity(Gravity.CENTER);setPadding(dp(5),dp(isTvMode()?8:10),dp(5),dp(isTvMode()?8:10));setBackground(shape(surfaceColor(),18));setElevation(dp(2));setFocusable(isTvMode());setClickable(true);
    SendCategoryIcon icon=new SendCategoryIcon(QuanLianActivity.this,label);addView(icon,new LinearLayout.LayoutParams(dp(isTvMode()?26:30),dp(isTvMode()?26:30)));gap(this,isTvMode()?5:7);
    TextView title=homeText(label,isTvMode()?12:14,isDarkTheme()?0xffe7edf7:0xff263957,true);title.setGravity(Gravity.CENTER);addView(title,new LinearLayout.LayoutParams(-1,-2));
    setOnClickListener(v->action.run());if(isTvMode())attachTvFocus(this,accentColor());
   }
  }
  private class SendCategoryIcon extends View{
   private final String kind;private final android.graphics.Paint p=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
   SendCategoryIcon(Context c,String k){super(c);kind=k;}
   @Override protected void onDraw(android.graphics.Canvas c){super.onDraw(c);float w=getWidth(),h=getHeight(),cx=w/2,cy=h/2;p.setColor(accentColor());p.setStrokeWidth(dp(2));p.setStrokeCap(android.graphics.Paint.Cap.ROUND);p.setStrokeJoin(android.graphics.Paint.Join.ROUND);p.setStyle(android.graphics.Paint.Style.STROKE);
    if("文件".equals(kind)){android.graphics.Path q=new android.graphics.Path();q.moveTo(cx-dp(9),cy-dp(12));q.lineTo(cx+dp(3),cy-dp(12));q.lineTo(cx+dp(10),cy-dp(5));q.lineTo(cx+dp(10),cy+dp(12));q.lineTo(cx-dp(9),cy+dp(12));q.close();c.drawPath(q,p);c.drawLine(cx+dp(3),cy-dp(12),cx+dp(3),cy-dp(5),p);c.drawLine(cx+dp(3),cy-dp(5),cx+dp(10),cy-dp(5),p);for(int i=-1;i<=1;i++)c.drawLine(cx-dp(5),cy+dp(i*5),cx+dp(5),cy+dp(i*5),p);}
    else if("媒体".equals(kind)){c.drawRoundRect(cx-dp(13),cy-dp(10),cx+dp(13),cy+dp(10),dp(3),dp(3),p);p.setStyle(android.graphics.Paint.Style.FILL);c.drawCircle(cx+dp(6),cy-dp(4),dp(2),p);android.graphics.Path q=new android.graphics.Path();q.moveTo(cx-dp(10),cy+dp(7));q.lineTo(cx-dp(3),cy-dp(1));q.lineTo(cx+dp(1),cy+dp(3));q.lineTo(cx+dp(5),cy- dp(1));q.lineTo(cx+dp(10),cy+dp(7));q.close();c.drawPath(q,p);}
    else if("剪贴板".equals(kind)){c.drawRoundRect(cx-dp(10),cy-dp(9),cx+dp(10),cy+dp(12),dp(2),dp(2),p);c.drawRoundRect(cx-dp(5),cy-dp(13),cx+dp(5),cy-dp(6),dp(2),dp(2),p);}
    else if("文本".equals(kind)){p.setStyle(android.graphics.Paint.Style.FILL);p.setTextSize(dp(25));p.setTypeface(Typeface.create(Typeface.DEFAULT,Typeface.BOLD));c.drawText("T",cx-p.measureText("T")/2,cy+dp(9),p);}
    else if("文件夹".equals(kind)){android.graphics.Path q=new android.graphics.Path();q.moveTo(cx-dp(13),cy-dp(7));q.lineTo(cx-dp(3),cy-dp(7));q.lineTo(cx+dp(1),cy-dp(3));q.lineTo(cx+dp(13),cy-dp(3));q.lineTo(cx+dp(11),cy+dp(10));q.lineTo(cx-dp(12),cy+dp(10));q.close();c.drawPath(q,p);}
    else{for(int r=0;r<3;r++)for(int col=0;col<3;col++)c.drawRoundRect(cx-dp(11)+col*dp(8),cy-dp(11)+r*dp(8),cx-dp(6)+col*dp(8),cy-dp(6)+r*dp(8),dp(1),dp(1),p);}
   }
  }
  private void addSendCategory(android.widget.GridLayout grid,String symbol,String label,Runnable action){
   AdaptiveFeatureCard tile=new AdaptiveFeatureCard(label,action);
   if(isTvMode())tile.setId(View.generateViewId());
   android.widget.GridLayout.LayoutParams p=new android.widget.GridLayout.LayoutParams();p.width=0;p.height=dp(isTvMode()?108:88);p.columnSpec=android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED,1f);p.setMargins(dp(isTvMode()?5:4),dp(4),dp(isTvMode()?5:4),dp(4));grid.addView(tile,p);
  }
  private void addSendCategory(LinearLayout row,String symbol,String label,Runnable action){
   AdaptiveFeatureCard tile=new AdaptiveFeatureCard(label,action);
   tile.setId(View.generateViewId());
   LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1f);
   p.setMargins(dp(5),dp(4),dp(5),dp(4));
   row.addView(tile,p);
  }
  private void pickMedia(){
   Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");
   i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"image/*","video/*","audio/*"});i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
   i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,PICK);
  }
  private void pickApk(){
   Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/vnd.android.package-archive");i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
   i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,PICK);
  }
  private void addClipboardText(){
   android.content.ClipboardManager cm=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
   if(cm==null||!cm.hasPrimaryClip()||cm.getPrimaryClip().getItemCount()==0){Toast.makeText(this,"剪贴板为空",Toast.LENGTH_SHORT).show();return;}
   CharSequence value=cm.getPrimaryClip().getItemAt(0).coerceToText(this);if(value==null||value.length()==0){Toast.makeText(this,"剪贴板中没有可发送的文本",Toast.LENGTH_SHORT).show();return;}
   addTextAsFile(value.toString(),"剪贴板.txt");
  }
  private void createTextFile(){
   EditText input=new EditText(this);input.setMinLines(4);input.setGravity(Gravity.TOP|Gravity.START);input.setHint("输入要发送的文本内容");
   new AlertDialog.Builder(this).setTitle("发送文本").setView(input).setNegativeButton("取消",null).setPositiveButton("添加到发送列表",(d,w)->{String value=input.getText().toString();if(value.trim().isEmpty()){Toast.makeText(this,"文本内容不能为空",Toast.LENGTH_SHORT).show();return;}addTextAsFile(value,"文本.txt");}).show();
  }
  private void addTextAsFile(String value,String fileName){
   try{File dir=new File(getCacheDir(),"shared_text");if(!dir.exists()&&!dir.mkdirs())throw new IOException("无法创建临时目录");File file=new File(dir,System.currentTimeMillis()+"_"+fileName);try(OutputStream out=new FileOutputStream(file)){out.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));}addFile(Uri.fromFile(file));sendPage();}
   catch(Exception e){Toast.makeText(this,"无法创建文本文件："+e.getMessage(),Toast.LENGTH_LONG).show();}
  }
  private void pickFolder(){
   Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,1003);
  }
  private void addFolderChildren(Uri treeUri,String documentId,int[] count){
   if(count[0]>=500)return;
   Uri children=android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri,documentId);
   String[] projection={android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE};
   try(Cursor cursor=getContentResolver().query(children,projection,null,null,null)){
    if(cursor==null)return;
    while(cursor.moveToNext()&&count[0]<500){
     String id=cursor.getString(0),name=cursor.getString(1),mime=cursor.getString(2);
     if(android.provider.DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)){addFolderChildren(treeUri,id,count);}
     else{Uri child=android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri,id);addFile(child);count[0]++;}
    }
   }catch(Exception e){android.util.Log.w("QuanLianFiles","Unable to enumerate selected folder",e);}
  }
  private void showSendOptions(){
   String[] options={ui("单个接收者"),ui("多个接收者"),ui("通过连接分享")};
   new AlertDialog.Builder(this).setTitle(ui("发送方式")).setItems(options,(dialog,which)->{
    if(which==0){getSharedPreferences("ui_settings",MODE_PRIVATE).edit().putString("send_mode","single").apply();Toast.makeText(this,"已选择单个接收者，点击一个附近设备即可发送",Toast.LENGTH_SHORT).show();}
    else if(which==1){getSharedPreferences("ui_settings",MODE_PRIVATE).edit().putString("send_mode","multiple").apply();showMultipleReceiverSelection();}
    else{getSharedPreferences("ui_settings",MODE_PRIVATE).edit().putString("send_mode","connection").apply();manualConnect();}
   }).setNegativeButton("取消",null).show();
  }
  private void startBatchSend(ArrayList<Device> targets){
   if(targets==null||targets.isEmpty())return;
   pendingRecipients.clear();pendingRecipients.addAll(targets);batchSending=true;
   Device first=pendingRecipients.removeFirst();
   Toast.makeText(this,"开始依次发送到 "+targets.size()+" 台设备",Toast.LENGTH_SHORT).show();
   send(first);
  }
  private void showMultipleReceiverSelection(){
   ArrayList<Device> list; synchronized(devices){list=new ArrayList<>(devices.values());}
   if(list.isEmpty()){Toast.makeText(this,"暂未发现附近设备",Toast.LENGTH_SHORT).show();return;}
   String[] labels=new String[list.size()];boolean[] checked=new boolean[list.size()];
   for(int i=0;i<list.size();i++)labels[i]=list.get(i).name;
   new AlertDialog.Builder(this).setTitle(ui("选择多个接收者")).setMultiChoiceItems(labels,checked,(d,which,on)->checked[which]=on)
    .setNegativeButton("取消",null).setPositiveButton("完成",(d,w)->{
     ArrayList<Device> selectedDevices=new ArrayList<>();ArrayList<String> selected=new ArrayList<>();
     for(int i=0;i<checked.length;i++)if(checked[i]){selected.add(list.get(i).key);selectedDevices.add(list.get(i));}
     getSharedPreferences("ui_settings",MODE_PRIVATE).edit().putString("selected_receivers",android.text.TextUtils.join(",",selected)).apply();
     if(selectedDevices.isEmpty()){Toast.makeText(this,"没有选择接收设备",Toast.LENGTH_SHORT).show();return;}
     if(!uris.isEmpty())startBatchSend(selectedDevices);
     else Toast.makeText(this,"已保存 "+selectedDevices.size()+" 个接收设备；添加文件后点击附近设备即可依次发送",Toast.LENGTH_LONG).show();
    }).show();
  }
  private void showFavorites(){List<FavoriteDevices.Entry> list=FavoriteDevices.list(this);if(list.isEmpty()){Toast.makeText(this,"还没有收藏设备",Toast.LENGTH_SHORT).show();return;}String[] labels=new String[list.size()];for(int i=0;i<list.size();i++)labels[i]=list.get(i).name;new AlertDialog.Builder(this).setTitle("收藏设备").setItems(labels,(d,which)->connectFavorite(list.get(which))).setNegativeButton("关闭",null).show();}
  private void scanSubnet(){
  if(deviceStatus!=null)deviceStatus.setText("正在主动扫描局域网 IPv4 地址…");
  int scanPort=port>0?port:38890;
  workers.execute(()->LanScanner.scan(this,scanPort,announcement->{
   if(myName().equals(announcement.name))return;
   runOnUiThread(()->{if(!alive)return;synchronized(devices){Device d=devices.get(announcement.id);if(d==null){d=new Device(announcement.name,announcement.id,announcement.host,announcement.port,announcement.fingerprint);devices.put(d.key,d);}d.name=announcement.name;d.host=announcement.host;d.port=announcement.port;d.fingerprint=announcement.fingerprint;d.udpSeen=true;d.udpLastSeen=android.os.SystemClock.elapsedRealtime();}refreshDevices();});
  },message->runOnUiThread(()->{if(deviceStatus!=null)deviceStatus.setText(message);})));
 }
 private void manualConnect(){
  LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(8),dp(8),dp(8),dp(4));
  EditText host=new EditText(this);host.setSingleLine(true);host.setHint("设备 IP 地址，例如 192.168.1.20");box.addView(host);
  EditText portInput=new EditText(this);portInput.setSingleLine(true);portInput.setInputType(2);portInput.setHint("端口（默认 38890）");portInput.setText("38890");box.addView(portInput);
  new AlertDialog.Builder(this).setTitle("手动连接设备").setView(box).setNegativeButton("取消",null).setPositiveButton("验证并连接",(dialog,which)->{
   String address=host.getText().toString().trim();int targetPort;try{targetPort=Integer.parseInt(portInput.getText().toString().trim());}catch(Exception e){Toast.makeText(this,"端口无效",Toast.LENGTH_SHORT).show();return;}
   workers.execute(()->{try{InetAddress ip=InetAddress.getByName(address);if(!ip.getHostAddress().equals(address)&&!address.matches("[0-9.]+"))throw new IOException("请输入有效 IPv4 地址");String fp=TlsIdentity.inspectFingerprint(this,ip.getHostAddress(),targetPort);if(!LanDiscovery.verify(this,ip,targetPort,fp))throw new IOException("连接验证失败，请检查 IP、端口和对方接收服务");String id="manual:"+ip.getHostAddress()+":"+targetPort;Device d=new Device("手动设备 "+ip.getHostAddress(),id,ip,targetPort,fp);runOnUiThread(()->new AlertDialog.Builder(QuanLianActivity.this).setTitle("确认设备身份").setMessage("这是首次信任该设备。请核对设备证书指纹：\n\n"+fp+"\n\n信任后将保存指纹，后续连接会验证证书是否一致。").setNegativeButton("取消",null).setPositiveButton("信任并连接",(confirmDialog,confirmWhich)->{synchronized(devices){devices.put(id,d);}getSharedPreferences("trusted_device_pins",MODE_PRIVATE).edit().putString(d.key,fp).apply();FavoriteDevices.add(this,d.name,address,targetPort,fp);refreshDevices();if(!uris.isEmpty())send(d);else Toast.makeText(this,"设备已验证并收藏",Toast.LENGTH_LONG).show();}).show());}catch(Exception e){runOnUiThread(()->Toast.makeText(this,"连接失败："+e.getMessage(),Toast.LENGTH_LONG).show());}});
  }).show();
 }
 private void connectFavorite(FavoriteDevices.Entry entry){
  workers.execute(()->{try{InetAddress ip=InetAddress.getByName(entry.host);if(!LanDiscovery.verify(this,ip,entry.port,entry.fingerprint))throw new IOException("设备当前不可连接或证书指纹已变化");Device d=new Device(entry.name,"favorite:"+entry.host+":"+entry.port,ip,entry.port,entry.fingerprint);runOnUiThread(()->{synchronized(devices){devices.put(d.key,d);}if(!uris.isEmpty())send(d);else Toast.makeText(this,"设备已连接验证",Toast.LENGTH_SHORT).show();});}catch(Exception e){runOnUiThread(()->Toast.makeText(this,"收藏设备连接失败："+e.getMessage(),Toast.LENGTH_LONG).show());}});
 }
 private class ReceiveAnimationView extends View {
  private final android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
  private final long started=android.os.SystemClock.uptimeMillis();
  ReceiveAnimationView(Context context){super(context);setLayerType(View.LAYER_TYPE_SOFTWARE,null);}
  @Override protected void onDraw(android.graphics.Canvas canvas){
   super.onDraw(canvas);
   float cx=getWidth()/2f,cy=getHeight()/2f;
   float max=Math.min(getWidth(),getHeight())*.43f;
   boolean animated=getSharedPreferences("ui_settings",MODE_PRIVATE).getBoolean("animations_enabled",true);
   float t=animated?((android.os.SystemClock.uptimeMillis()-started)%3600L)/3600f:0f;
   paint.setStyle(android.graphics.Paint.Style.FILL);
   paint.setColor(0x183b86ef);canvas.drawCircle(cx,cy,max*.70f,paint);
   paint.setStyle(android.graphics.Paint.Style.STROKE);paint.setStrokeWidth(dp(1.4f));
   for(int i=0;i<4;i++){
    float phase=animated?(t+i*.25f)%1f:.5f;
    float radius=max*(.42f+phase*.58f);
    paint.setColor(android.graphics.Color.argb(animated?(int)(100*(1-phase)):38,55,133,238));
    paint.setStrokeWidth(dp(i==0?2.4f:1.5f));canvas.drawCircle(cx,cy,radius,paint);
   }
   // Subtle orbit paths and moving data nodes.
   paint.setColor(0x553b8af0);paint.setStrokeWidth(dp(1.2f));
   canvas.drawOval(cx-max*.82f,cy-max*.34f,cx+max*.82f,cy+max*.34f,paint);
   canvas.save();canvas.rotate(-58,cx,cy);canvas.drawOval(cx-max*.82f,cy-max*.34f,cx+max*.82f,cy+max*.34f,paint);canvas.restore();
   for(int i=0;i<5;i++){
    double a=(animated?t*Math.PI*2:0)+i*Math.PI*2/5;
    float x=cx+(float)Math.cos(a)*max*.82f,y=cy+(float)Math.sin(a)*max*.34f;
    paint.setStyle(android.graphics.Paint.Style.FILL);paint.setColor(0xff3d91ff);canvas.drawCircle(x,y,dp(i==0?4:2.7f),paint);
    paint.setColor(0x553d91ff);canvas.drawCircle(x,y,dp(7),paint);
   }
   // Central receiver module with soft halo.
   paint.setColor(0x223b86ef);canvas.drawCircle(cx,cy,dp(55),paint);
   paint.setColor(0xffe8f1ff);canvas.drawCircle(cx,cy,dp(46),paint);
   paint.setColor(0xff347ff0);paint.setStyle(android.graphics.Paint.Style.STROKE);paint.setStrokeWidth(dp(4));
   android.graphics.RectF device=new android.graphics.RectF(cx-dp(24),cy-dp(31),cx+dp(24),cy+dp(31));
   canvas.drawRoundRect(device,dp(9),dp(9),paint);
   paint.setColor(0xff347ff0);paint.setStrokeWidth(dp(3.5f));paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
   android.graphics.Path arrow=new android.graphics.Path();
   arrow.moveTo(cx,cy-dp(17));arrow.lineTo(cx,cy+dp(9));
   arrow.moveTo(cx-dp(10),cy-dp(1));arrow.lineTo(cx,cy+dp(10));arrow.lineTo(cx+dp(10),cy-dp(1));
   canvas.drawPath(arrow,paint);
   if(animated)postInvalidateDelayed(32);
  }
 }
 private void receivePage(){
  page=3;deviceList=null;deviceStatus=null;
  applyWindowTheme();
  int widthDp=(int)(getResources().getDisplayMetrics().widthPixels/getResources().getDisplayMetrics().density);
  boolean wide=isTvMode();
  LinearLayout l=col();l.setBackgroundColor(pageBackground());
  l.setGravity(Gravity.CENTER_HORIZONTAL);
  if(wide)l.setPadding(dp(28),dp(12),dp(28),dp(12));
  LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
  TextView history=homeText("◷",24,0xff263957,true);history.setGravity(Gravity.CENTER);
  history.setPadding(dp(12),dp(8),dp(12),dp(8));history.setOnClickListener(v->historyPage());top.addView(history);
  TextView info=homeText("●",20,0xff263957,true);info.setGravity(Gravity.CENTER);
  info.setPadding(dp(12),dp(8),dp(12),dp(8));info.setOnClickListener(v->infoPage("关于小李全联","小李全联是一款局域网文件传输工具。\n无广告，不上传文件内容。"));top.addView(info);
  l.addView(top);
  LinearLayout center=new LinearLayout(this);center.setOrientation(LinearLayout.VERTICAL);
  center.setGravity(Gravity.CENTER);center.setPadding(0,dp(wide?4:20),0,dp(wide?4:20));
  ReceiveAnimationView animation=new ReceiveAnimationView(this);
  int visualSize=wide?Math.min(300,Math.max(240,widthDp/3)):220;
  LinearLayout.LayoutParams animationParams=new LinearLayout.LayoutParams(dp(visualSize),dp(visualSize));
  animationParams.gravity=Gravity.CENTER_HORIZONTAL;center.addView(animation,animationParams);
  gap(center,wide?8:18);
  TextView brand=homeText("小李全联",wide?30:34,0xff182747,true);brand.setGravity(Gravity.CENTER);center.addView(brand);
  gap(center,wide?8:18);
  receiveStatus=homeText(port>0?"接收服务已启动 · 正在等待附近设备":"正在启动接收服务…",wide?14:13,0xff8491a8,false);
  receiveStatus.setGravity(Gravity.CENTER);center.addView(receiveStatus);
  if(wide){TextView hint=homeText("设备靠近时自动识别 · 安全加密传输",12,0xff9aa9bf,false);hint.setGravity(Gravity.CENTER);LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);hp.topMargin=dp(12);center.addView(hint,hp);}
  LinearLayout.LayoutParams centerParams=new LinearLayout.LayoutParams(-1,0,1);l.addView(center,centerParams);
  installFlow(l,0);
 }
 @Override protected void onDestroy(){unregisterSendReceiver();if(sendProgress!=null){try{sendProgress.dismiss();}catch(Exception ignored){}sendProgress=null;}alive=false;discoveryHandler.removeCallbacks(pruneDevices);unregisterReceiveReceiver();if(udpDiscovery!=null){udpDiscovery.close();udpDiscovery=null;}try{if(nsd!=null&&discovery!=null)nsd.stopServiceDiscovery(discovery);}catch(Exception ignored){}try{if(nsd!=null&&registration!=null)nsd.unregisterService(registration);}catch(Exception ignored){}try{if(lock!=null&&lock.isHeld())lock.release();}catch(Exception ignored){}workers.shutdownNow();super.onDestroy();}
 @Override public void onBackPressed(){if(page==3)super.onBackPressed();else receivePage();}
}