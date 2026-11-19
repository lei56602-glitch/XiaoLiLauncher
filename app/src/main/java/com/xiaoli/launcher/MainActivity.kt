package com.xiaoli.launcher

import android.app.Activity
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
        setContentView(launcherView)
        enterImmersiveMode()
    }

    @Deprecated("Deprecated in Android API; retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_WALLPAPER && resultCode == RESULT_OK) {
            data?.data?.let(launcherView::setWallpaper)
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
