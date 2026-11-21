package com.xiaoli.quanlian;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

public final class FavoriteDevices {
    public static final class Entry {
        public final String name,host,fingerprint; public final int port;
        Entry(String n,String h,int p,String fp){name=n;host=h;port=p;fingerprint=fp;}
    }
    private FavoriteDevices(){}
    public static synchronized List<Entry> list(Context c){
        ArrayList<Entry> out=new ArrayList<>();
        try{JSONArray a=new JSONArray(c.getSharedPreferences("favorite_devices",0).getString("items","[]"));
            for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);String h=o.optString("host","");int p=o.optInt("port",0);if(!h.isEmpty()&&p>0&&p<=65535)out.add(new Entry(o.optString("name",h),h,p,o.optString("fp","")));}
        }catch(Exception ignored){}return out;
    }
    public static synchronized void add(Context c,String name,String host,int port,String fingerprint){
        if(host==null||host.trim().isEmpty()||port<1||port>65535)return;
        try{JSONArray old=new JSONArray(c.getSharedPreferences("favorite_devices",0).getString("items","[]")),next=new JSONArray();boolean replaced=false;
            for(int i=0;i<old.length();i++){JSONObject o=old.getJSONObject(i);if(host.equals(o.optString("host"))&&port==o.optInt("port")){if(!replaced){next.put(item(name,host,port,fingerprint));replaced=true;}}else next.put(o);}
            if(!replaced)next.put(item(name,host,port,fingerprint));
            c.getSharedPreferences("favorite_devices",0).edit().putString("items",next.toString()).apply();
        }catch(Exception ignored){}
    }
    public static synchronized void remove(Context c,String host,int port){
        try{JSONArray old=new JSONArray(c.getSharedPreferences("favorite_devices",0).getString("items","[]")),next=new JSONArray();for(int i=0;i<old.length();i++){JSONObject o=old.getJSONObject(i);if(!host.equals(o.optString("host"))||port!=o.optInt("port"))next.put(o);}c.getSharedPreferences("favorite_devices",0).edit().putString("items",next.toString()).apply();}catch(Exception ignored){}
    }
    private static JSONObject item(String n,String h,int p,String fp)throws Exception{JSONObject o=new JSONObject();o.put("name",n==null||n.trim().isEmpty()?h:n);o.put("host",h);o.put("port",p);o.put("fp",fp);return o;}
}
