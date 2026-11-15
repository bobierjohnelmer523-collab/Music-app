package com.spotifyclone.app.api

import com.spotifyclone.app.model.*
import retrofit2.Response
import retrofit2.http.*

interface BackendApi {

    @POST("api/auth/register")
    suspend fun register(@Body request: RegisterRequest): Response<AuthResponse>

    @POST("api/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<AuthResponse>

    @GET("api/playlists")
    suspend fun getPlaylists(): Response<List<PlaylistDto>>

    @GET("api/playlists/{id}")
    suspend fun getPlaylistById(@Path("id") id: Long): Response<PlaylistDto>

    @POST("api/playlists")
    suspend fun createPlaylist(@Body request: PlaylistRequest): Response<PlaylistDto>

    @PUT("api/playlists/{id}")
    suspend fun updatePlaylist(@Path("id") id: Long, @Body request: PlaylistRequest): Response<PlaylistDto>

    @DELETE("api/playlists/{id}")
    suspend fun deletePlaylist(@Path("id") id: Long): Response<ApiResponse>

    @POST("api/playlists/{id}/tracks")
    suspend fun addTrackToPlaylist(@Path("id") id: Long, @Body track: TrackDto): Response<PlaylistDto>

    @DELETE("api/playlists/{id}/tracks/{trackId}")
    suspend fun removeTrackFromPlaylist(@Path("id") id: Long, @Path("trackId") trackId: Long): Response<PlaylistDto>
}
