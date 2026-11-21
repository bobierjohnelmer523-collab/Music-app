package com.spotifyclone.app.spotify

import com.spotifyclone.app.model.SpotifyPaging
import com.spotifyclone.app.model.SpotifyPlaylistDetail
import com.spotifyclone.app.model.SpotifyPlaylistSummary
import com.spotifyclone.app.model.SpotifyPlaylistTrackItem
import com.spotifyclone.app.model.SpotifySearchResult
import com.spotifyclone.app.model.SpotifyUser
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

/** Spotify Web API v1 endpoints used by the app (Bearer token passed per-call). */
interface SpotifyApiService {

    @GET("me")
    suspend fun getMe(@Header("Authorization") auth: String): SpotifyUser

    @GET("me/playlists")
    suspend fun getMyPlaylists(
        @Header("Authorization") auth: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): SpotifyPaging<SpotifyPlaylistSummary>

    @GET("playlists/{id}")
    suspend fun getPlaylist(
        @Header("Authorization") auth: String,
        @Path("id") id: String
    ): SpotifyPlaylistDetail

    @GET("playlists/{id}/tracks")
    suspend fun getPlaylistTracks(
        @Header("Authorization") auth: String,
        @Path("id") id: String,
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0
    ): SpotifyPaging<SpotifyPlaylistTrackItem>

    @GET("search")
    suspend fun searchPlaylists(
        @Header("Authorization") auth: String,
        @Query("q") query: String,
        @Query("type") type: String = "playlist",
        @Query("limit") limit: Int = 20
    ): SpotifySearchResult
}

object SpotifyApi {

    private const val BASE_URL = "https://api.spotify.com/v1/"

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val service: SpotifyApiService = retrofit.create(SpotifyApiService::class.java)
}
