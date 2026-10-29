package com.earlln.rainornot.data

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object Http {
    const val USER_AGENT = "Mozilla/5.0"
    const val TOPIS_REFERER = "https://topis.seoul.go.kr/"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
