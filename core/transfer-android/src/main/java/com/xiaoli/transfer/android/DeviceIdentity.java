package com.xiaoli.transfer.android;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import java.util.UUID;

/** Stable, app-local device identity shared by discovery and the receive service. */
public final class DeviceIdentity {
    private DeviceIdentity() {}

    public static String id(Context context) {
        String androidId = null;
        try {
            androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Exception ignored) {}
        if (androidId != null && androidId.length() >= 8) return "android-" + androidId.toLowerCase(java.util.Locale.ROOT);
        android.content.SharedPreferences prefs = context.getSharedPreferences("device_identity", Context.MODE_PRIVATE);
        String value = prefs.getString("id", null);
        if (value == null || value.trim().isEmpty()) {
            value = "install-" + UUID.randomUUID().toString();
            prefs.edit().putString("id", value).apply();
        }
        return value;
    }

    public static String name(Context context) {
        android.content.SharedPreferences prefs=context.getSharedPreferences("device_identity",Context.MODE_PRIVATE);
        String custom=prefs.getString("display_name","");
        if(custom!=null&&!custom.trim().isEmpty())return custom.trim();
        String generated=prefs.getString("generated_name",null);
        if(generated==null||generated.trim().isEmpty()){
            String[] names={"蓝鲸","星河","云雀","极光","海风","晨曦","山岚","流星","白鹭","远帆","青松","月影"};
            String id=id(context);
            int index=Math.floorMod(id.hashCode(),names.length);
            String tail=id.replaceAll("[^A-Za-z0-9]","");
            if(tail.length()>4)tail=tail.substring(tail.length()-4);
            generated=names[index]+"-"+tail;
            prefs.edit().putString("generated_name",generated).apply();
        }
        return generated;
    }
}
