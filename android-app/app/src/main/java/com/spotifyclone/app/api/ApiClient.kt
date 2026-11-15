package com.spotifyclone.app.api

import android.content.Context
import android.content.SharedPreferences
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {

    private const val BASE_URL = "https://music-app-5b9t.onrender.com/"
    private const val PREF_NAME = "spotify_clone_prefs"
    private const val KEY_JWT_TOKEN = "jwt_token"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_USER_EMAIL = "user_email"

    private var sharedPreferences: SharedPreferences? = null
    private var backendApi: BackendApi? = null

    fun init(context: Context) {
        if (sharedPreferences == null) {
            sharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        }
    }

    fun saveAuthData(token: String, username: String, email: String) {
        sharedPreferences?.edit()
            ?.putString(KEY_JWT_TOKEN, token)
            ?.putString(KEY_USER_NAME, username)
            ?.putString(KEY_USER_EMAIL, email)
            ?.apply()
    }

    fun getToken(): String? {
        return sharedPreferences?.getString(KEY_JWT_TOKEN, null)
    }

    fun getUsername(): String? {
        return sharedPreferences?.getString(KEY_USER_NAME, null)
    }

    fun clearAuth() {
        sharedPreferences?.edit()?.clear()?.apply()
    }

    fun isLoggedIn(): Boolean {
        return !getToken().isNullOrEmpty()
    }

    fun getApi(): BackendApi {
        if (backendApi == null) {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }

            val authInterceptor = Interceptor { chain ->
                val originalRequest = chain.request()
                val token = getToken()
                val builder = originalRequest.newBuilder()

                if (!token.isNullOrEmpty()) {
                    builder.header("Authorization", "Bearer $token")
                }

                chain.proceed(builder.build())
            }

            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(authInterceptor)
                .addInterceptor(loggingInterceptor)
                .build()

            val retrofit = Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            backendApi = retrofit.create(BackendApi::class.java)
        }
        return backendApi!!
    }
}
