package com.vela.app

import com.vela.data.network.ServerTrust
import android.app.Application
import android.content.Context
import coil3.PlatformContext
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.vela.app.locale.AppLanguageManager
import com.vela.app.util.logging.CrashHandler
import com.vela.data.repository.AuthRepositoryProvider
import com.vela.shared.util.image.ImageLoaderConfig
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class VelaApplication : Application(), SingletonImageLoader.Factory {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguageManager.wrapContext(base))
    }

    override fun onCreate() {
        super.onCreate()
        // 用户确认过指纹的自签证书（如 Peach 源码部署）对 OkHttp 与播放器的 HttpsURLConnection 都生效。
        ServerTrust.install(this)
        AppLanguageManager.applySavedLanguage(this)

        // Initialize crash handler for better crash log collection
        CrashHandler.initialize(this)
        AuthRepositoryProvider.getInstance(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoaderConfig.createOptimizedImageLoader(context)
    }
}
