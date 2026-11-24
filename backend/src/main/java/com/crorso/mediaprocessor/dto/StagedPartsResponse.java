package com.crorso.mediaprocessor.dto;

import java.util.List;

/** Part numbers currently staged for a story, in ascending order. */
public record StagedPartsResponse(String storyId, List<Integer> partNumbers, String requestId) {
}
