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
        String model = Build.MODEL;
        if (model == null || model.trim().isEmpty()) model = "Android";
        model = model.replaceAll("[^\\p{L}\\p{N}_-]", "");
        String id = id(context);
        String suffix = id.length() > 6 ? id.substring(id.length() - 6) : id;
        return "小李全联-" + model + "-" + suffix;
    }
}
