package com.example.screenshotviewer

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

interface ApiService {
    @FormUrlEncoded
    @POST("login")
    suspend fun login(
        @Field("username") username: String,
        @Field("password") password: String
    ): Response<ResponseBody>

    @GET("browse/{path}")
    suspend fun browseDirectory(@Path("path", encoded = true) path: String = ""): Response<ResponseBody>

    @GET("stream/{path}")
    suspend fun getImage(@Path("path", encoded = true) path: String): Response<ResponseBody>

    @FormUrlEncoded
    @POST("delete")
    suspend fun deleteFile(@Field(value = "path", encoded = true) path: String): Response<ApiResponse>
}

class CookieManager : CookieJar {
    private val cookieStore = HashMap<String, List<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookieStore[url.host] = cookies
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return cookieStore[url.host] ?: ArrayList()
    }

    fun clear() {
        cookieStore.clear()
    }
}

object RetrofitClient {
    private var retrofit: Retrofit? = null
    private val cookieManager = CookieManager()
    private var okHttpClient: OkHttpClient? = null

    fun getClient(baseUrl: String): ApiService {
        if (retrofit == null || retrofit!!.baseUrl().toString() != baseUrl) {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }

            okHttpClient = OkHttpClient.Builder()
                .cookieJar(cookieManager)
                .addInterceptor(loggingInterceptor)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()

            retrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient!!)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
        }
        return retrofit!!.create(ApiService::class.java)
    }

    fun getOkHttpClient(): OkHttpClient {
        return okHttpClient ?: OkHttpClient.Builder()
            .cookieJar(cookieManager)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build().also { okHttpClient = it }
    }

    fun clearCookies() {
        cookieManager.clear()
    }
}
