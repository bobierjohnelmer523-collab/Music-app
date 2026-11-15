package com.spotifyclone.backend.service;

import com.spotifyclone.backend.dto.PlaylistRequest;
import com.spotifyclone.backend.dto.PlaylistResponse;
import com.spotifyclone.backend.dto.TrackDto;
import com.spotifyclone.backend.model.Playlist;
import com.spotifyclone.backend.model.Track;
import com.spotifyclone.backend.model.User;
import com.spotifyclone.backend.repository.PlaylistRepository;
import com.spotifyclone.backend.repository.TrackRepository;
import com.spotifyclone.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class PlaylistService {

    @Autowired
    private PlaylistRepository playlistRepository;

    @Autowired
    private TrackRepository trackRepository;

    @Autowired
    private UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<PlaylistResponse> getUserPlaylists(Long userId) {
        List<Playlist> playlists = playlistRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        return playlists.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public PlaylistResponse getPlaylistById(Long playlistId, Long userId) {
        Playlist playlist = playlistRepository.findByIdAndUserId(playlistId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Playlist not found or access denied"));
        return mapToResponse(playlist);
    }

    @Transactional
    public PlaylistResponse createPlaylist(PlaylistRequest request, Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        Playlist playlist = Playlist.builder()
                .title(request.getTitle())
                .description(request.getDescription())
                .user(user)
                .build();

        Playlist saved = playlistRepository.save(playlist);
        return mapToResponse(saved);
    }

    @Transactional
    public PlaylistResponse updatePlaylist(Long playlistId, PlaylistRequest request, Long userId) {
        Playlist playlist = playlistRepository.findByIdAndUserId(playlistId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Playlist not found or access denied"));

        playlist.setTitle(request.getTitle());
        if (request.getDescription() != null) {
            playlist.setDescription(request.getDescription());
        }

        Playlist updated = playlistRepository.save(playlist);
        return mapToResponse(updated);
    }

    @Transactional
    public void deletePlaylist(Long playlistId, Long userId) {
        Playlist playlist = playlistRepository.findByIdAndUserId(playlistId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Playlist not found or access denied"));
        playlistRepository.delete(playlist);
    }

    @Transactional
    public PlaylistResponse addTrackToPlaylist(Long playlistId, TrackDto trackDto, Long userId) {
        Playlist playlist = playlistRepository.findByIdAndUserId(playlistId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Playlist not found or access denied"));

        int position = playlist.getTracks().size();
        Track track = Track.builder()
                .youtubeId(trackDto.getYoutubeId())
                .title(trackDto.getTitle())
                .artist(trackDto.getArtist())
                .durationSeconds(trackDto.getDurationSeconds())
                .thumbnailUrl(trackDto.getThumbnailUrl())
                .position(trackDto.getPosition() != null ? trackDto.getPosition() : position)
                .playlist(playlist)
                .build();

        playlist.getTracks().add(track);
        Playlist saved = playlistRepository.save(playlist);
        return mapToResponse(saved);
    }

    @Transactional
    public PlaylistResponse removeTrackFromPlaylist(Long playlistId, Long trackId, Long userId) {
        Playlist playlist = playlistRepository.findByIdAndUserId(playlistId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Playlist not found or access denied"));

        boolean removed = playlist.getTracks().removeIf(t -> t.getId().equals(trackId));
        if (!removed) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Track not found in this playlist");
        }

        // Re-index remaining positions
        for (int i = 0; i < playlist.getTracks().size(); i++) {
            playlist.getTracks().get(i).setPosition(i);
        }

        Playlist saved = playlistRepository.save(playlist);
        return mapToResponse(saved);
    }

    private PlaylistResponse mapToResponse(Playlist playlist) {
        List<TrackDto> trackDtos = playlist.getTracks().stream()
                .map(track -> TrackDto.builder()
                        .id(track.getId())
                        .youtubeId(track.getYoutubeId())
                        .title(track.getTitle())
                        .artist(track.getArtist())
                        .durationSeconds(track.getDurationSeconds())
                        .thumbnailUrl(track.getThumbnailUrl())
                        .position(track.getPosition())
                        .build())
                .collect(Collectors.toList());

        return PlaylistResponse.builder()
                .id(playlist.getId())
                .title(playlist.getTitle())
                .description(playlist.getDescription())
                .createdAt(playlist.getCreatedAt())
                .updatedAt(playlist.getUpdatedAt())
                .tracks(trackDtos)
                .build();
    }
}
