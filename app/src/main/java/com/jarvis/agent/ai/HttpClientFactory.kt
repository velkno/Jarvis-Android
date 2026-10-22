package com.jarvis.agent.ai

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** مصنع عميل HTTP مشترك مع مهلة مناسبة لنماذج التفكير الطويل. */
class HttpClientFactory {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
