package com.spotifyclone.backend.repository;

import com.spotifyclone.backend.model.Track;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TrackRepository extends JpaRepository<Track, Long> {
    Optional<Track> findByIdAndPlaylistId(Long id, Long playlistId);
    void deleteByIdAndPlaylistId(Long id, Long playlistId);
}
