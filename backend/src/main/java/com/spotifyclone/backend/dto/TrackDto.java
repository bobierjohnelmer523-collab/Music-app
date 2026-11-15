package com.spotifyclone.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TrackDto {
    private Long id;

    @NotBlank
    private String youtubeId;

    @NotBlank
    private String title;

    private String artist;
    private Long durationSeconds;
    private String thumbnailUrl;
    private Integer position;
}
