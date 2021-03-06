package cn.chitanda.plusme2.hook

import android.app.Activity
import android.app.AndroidAppHelper
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.Keep
import cn.chitanda.plusme2.utile.BroadcastUtil
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@Keep
class LauncherHook : IXposedHookLoadPackage {
    private lateinit var launcherContext: Context
    private lateinit var launcherActivity: Activity
    private lateinit var launcherClass: Class<*>
    private lateinit var header: ViewGroup
    private lateinit var title: TextView
    private var cunt = 0
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            XposedBridge.log("Launcher onReceive:")
            when (intent?.action) {
                BroadcastUtil.CHANGE.action -> {
                    XposedBridge.log("Change")
                    val uri = Uri.parse(intent!!.getStringExtra("Uri"))
                    if (uri != null) MainScope().launch {
                        saveAndSetBackground(
                            uri
                        )
                    }
                }
                BroadcastUtil.DELETE.action -> {
                    XposedBridge.log("Delete")
                    MainScope().launch { removeBg() }
                }
                BroadcastUtil.GET_SIZE.action -> {
                    XposedBridge.log("getSize")
                    title.performClick()
                }
            }
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam?) {
        if ("net.oneplus.launcher" != lpparam?.processName) return
        try {
            XposedHelpers.findAndHookMethod(
                "net.oneplus.launcher.quickpage.view.WelcomePanel",
                lpparam.classLoader,
                "onFinishInflate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam?) {
                        super.afterHookedMethod(param)
                        header = param?.thisObject as ViewGroup
                        title = XposedHelpers.getObjectField(header, "mWelcomeTitle") as TextView
                        title.setOnClickListener {
                            val width = header.width
                            val height = header.height
                            XposedBridge.log("Title ClickListerner: $width $height ")
                            launcherContext.sendBroadcast(BroadcastUtil.GET_SIZE.apply {
                                putExtra("Width", width)
                                putExtra("Height", height)
                            })
                            if (cunt == 0) {
                                Toast.makeText(
                                    launcherContext,
                                    "成功获取宽高width:$width,height$height",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            cunt++
                        }
                        MainScope().launch { setBackground(getSavedBg()) }
                    }

                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("WelcomeHeader错误:")
            XposedBridge.log(t)
        }
        try {
            launcherClass =
                XposedHelpers.findClass("net.oneplus.launcher.Launcher", lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                launcherClass,
                "onCreate",
                Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam?) {
                        super.afterHookedMethod(param)
                        launcherActivity = param?.thisObject as Activity
                        launcherContext = AndroidAppHelper.currentApplication().applicationContext
                        XposedBridge.log("Context获取成功：$launcherContext")
                        //XposedBridge.log(launcherActivity.applicationContext.toString())
                        launcherContext.registerReceiver(receiver, IntentFilter().apply {
                            addAction(BroadcastUtil.CHANGE.action)
                            addAction(BroadcastUtil.DELETE.action)
                            addAction(BroadcastUtil.GET_SIZE.action)
                        })
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("Context获取错误")
            XposedBridge.log(t)
        }

        try {
            launcherClass =
                XposedHelpers.findClass("net.oneplus.launcher.Launcher", lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                launcherClass,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam?) {
                        super.afterHookedMethod(param)
                        launcherContext.unregisterReceiver(receiver)
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("onDestroy: $t")
        }
    }

    private suspend fun removeBg() {
        withContext(Dispatchers.IO) {
            try {
                val file = File(launcherContext.filesDir, "bg")
                if (file.exists() && file.delete()) {
                    Toast.makeText(launcherContext, "删除成功", Toast.LENGTH_SHORT).show()
                }
//                setBackground(getSavedBg())
                header.backgroundTintList = null
            } catch (t: Throwable) {
                XposedBridge.log(t)
            }
        }
    }

    suspend fun saveAndSetBackground(uri: Uri?) {
        withContext(Dispatchers.IO) {
            try {
                if (saveBg(
                        BitmapFactory.decodeStream(
                            launcherContext.contentResolver.openInputStream(
                                uri!!
                            )
                        )
                    )
                ) {
                    deleteCrop(uri)
                    setBackground(getSavedBg())
                } else {
                    false
                }
            } catch (t: Throwable) {
                XposedBridge.log(t)
                false
            }
        }
    }

    private suspend fun deleteCrop(uri: Uri) = withContext(Dispatchers.IO) {
        launcherContext.contentResolver.delete(uri, null, null)
    }

    private fun setBackground(savedBg: Bitmap?) = if (savedBg != null) {
        header.background = BitmapDrawable(launcherContext.resources, savedBg)
        true
    } else {
        XposedBridge.log("Saved BitMap is null")
        false
    }.also {
        launcherContext.sendBroadcast(BroadcastUtil.CHANGE_RESULT.apply {
            putExtra(BroadcastUtil.CHANGE_RESULT.action, it)
        })
    }


    private suspend fun saveBg(bitmap: Bitmap?) = withContext(Dispatchers.IO) {
        try {
            bitmap?.compress(
                Bitmap.CompressFormat.WEBP,
                100,
                FileOutputStream(File(launcherContext.filesDir, "bg"))
            )!!
        } catch (t: Throwable) {
            XposedBridge.log(t)
            false
        }
    }

    private suspend fun getSavedBg() = withContext(Dispatchers.IO) {
        val file = File(launcherContext.filesDir, "bg")
        if (file.exists()) {
            BitmapFactory.decodeFile(file.absolutePath)
        } else {
            null
        }
    }
}

