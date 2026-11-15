package com.spotifyclone.backend.controller;

import com.spotifyclone.backend.dto.ApiResponse;
import com.spotifyclone.backend.dto.PlaylistRequest;
import com.spotifyclone.backend.dto.PlaylistResponse;
import com.spotifyclone.backend.dto.TrackDto;
import com.spotifyclone.backend.security.UserPrincipal;
import com.spotifyclone.backend.service.PlaylistService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/playlists")
public class PlaylistController {

    @Autowired
    private PlaylistService playlistService;

    @GetMapping
    public ResponseEntity<List<PlaylistResponse>> getUserPlaylists(
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(playlistService.getUserPlaylists(currentUser.getId()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PlaylistResponse> getPlaylistById(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        return ResponseEntity.ok(playlistService.getPlaylistById(id, currentUser.getId()));
    }

    @PostMapping
    public ResponseEntity<PlaylistResponse> createPlaylist(
            @Valid @RequestBody PlaylistRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        PlaylistResponse response = playlistService.createPlaylist(request, currentUser.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<PlaylistResponse> updatePlaylist(
            @PathVariable Long id,
            @Valid @RequestBody PlaylistRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        PlaylistResponse response = playlistService.updatePlaylist(id, request, currentUser.getId());
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse> deletePlaylist(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        playlistService.deletePlaylist(id, currentUser.getId());
        return ResponseEntity.ok(new ApiResponse(true, "Playlist deleted successfully"));
    }

    @PostMapping("/{id}/tracks")
    public ResponseEntity<PlaylistResponse> addTrackToPlaylist(
            @PathVariable Long id,
            @Valid @RequestBody TrackDto trackDto,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        PlaylistResponse response = playlistService.addTrackToPlaylist(id, trackDto, currentUser.getId());
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}/tracks/{trackId}")
    public ResponseEntity<PlaylistResponse> removeTrackFromPlaylist(
            @PathVariable Long id,
            @PathVariable Long trackId,
            @AuthenticationPrincipal UserPrincipal currentUser) {
        PlaylistResponse response = playlistService.removeTrackFromPlaylist(id, trackId, currentUser.getId());
        return ResponseEntity.ok(response);
    }
}
