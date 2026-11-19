package com.xiaoli.launcher

import android.app.Activity
import android.app.role.RoleManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import android.os.Bundle
import android.content.Intent
import android.view.View
import android.view.WindowManager

class MainActivity : Activity() {
    private lateinit var launcherView: LauncherView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        launcherView = LauncherView(this)
        launcherView.onChooseWallpaper = {
            val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            startActivityForResult(picker, REQUEST_WALLPAPER)
        }
        launcherView.onRequestDefaultHome = { requestDefaultHomeRole() }
        setContentView(launcherView)
        enterImmersiveMode()
    }

    override fun onResume() {
        super.onResume()
        if (::launcherView.isInitialized) launcherView.invalidate()
    }

    @Deprecated("Deprecated in Android API; retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_WALLPAPER && resultCode == RESULT_OK) {
            data?.data?.let(launcherView::setWallpaper)
        } else if (requestCode == REQUEST_HOME_ROLE) {
            val isDefault = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true
            Toast.makeText(this, if (isDefault) "小李桌面已设为默认主屏幕" else "尚未更改默认主屏幕", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestDefaultHomeRole() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = getSystemService(RoleManager::class.java)
                if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    if (roleManager.isRoleHeld(RoleManager.ROLE_HOME)) {
                        Toast.makeText(this, "小李桌面已经是默认主屏幕", Toast.LENGTH_SHORT).show()
                    } else {
                        startActivityForResult(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME), REQUEST_HOME_ROLE)
                    }
                    return
                }
            }
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "当前电视系统不支持直接打开默认桌面设置", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onBackPressed() {
        if (::launcherView.isInitialized && launcherView.isLauncherSettingsOpen) {
            launcherView.closeLauncherSettings()
        } else if (::launcherView.isInitialized && launcherView.isDrawerOpen) {
            launcherView.closeDrawer()
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        private const val REQUEST_WALLPAPER = 7101
        private const val REQUEST_HOME_ROLE = 7102
    }

    private fun enterImmersiveMode() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
    }
}
