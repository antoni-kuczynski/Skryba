package com.kuczynski.skryba

import android.app.Application
import android.content.Context
import com.kuczynski.skryba.i18n.Lang
import com.kuczynski.skryba.data.Library
import com.kuczynski.skryba.data.Settings
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.opencv.android.OpenCVLoader

class SkrybaApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(Lang.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        Lang.attach(this)
        OpenCVLoader.initLocal()
        PDFBoxResourceLoader.init(this)
        Settings.init(this)
        Library.init(this)
        Library.clearTemp()
    }
}
