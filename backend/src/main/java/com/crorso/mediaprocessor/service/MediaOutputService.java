package com.crorso.mediaprocessor.service;

import com.crorso.mediaprocessor.exception.ErrorCode;
import com.crorso.mediaprocessor.exception.MediaException;
import com.crorso.mediaprocessor.service.storage.MediaKind;
import com.crorso.mediaprocessor.service.storage.MediaStorage;
import com.crorso.mediaprocessor.service.storage.StoredMedia;
import com.crorso.mediaprocessor.util.StoryIdValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Lookup and deletion of published outputs. Deletion waits for any running job on the same story. */
@Service
public class MediaOutputService {

    private static final Logger log = LoggerFactory.getLogger(MediaOutputService.class);

    private final MediaStorage storage;
    private final JobGate jobGate;

    public MediaOutputService(MediaStorage storage, JobGate jobGate) {
        this.storage = storage;
        this.jobGate = jobGate;
    }

    public StoredMedia require(String storyId, MediaKind kind) {
        String validated = StoryIdValidator.validate(storyId);
        return storage.find(validated, kind)
                .orElseThrow(() -> new MediaException(ErrorCode.OUTPUT_NOT_FOUND,
                        "No " + kind.label() + " exists for this story. Process the story again if it has expired."));
    }

    public void deleteStory(String storyId) {
        String validated = StoryIdValidator.validate(storyId);
        try (JobGate.Ticket ignored = jobGate.lockStory(validated)) {
            if (!storage.deleteStory(validated)) {
                throw new MediaException(ErrorCode.OUTPUT_NOT_FOUND, ErrorCode.OUTPUT_NOT_FOUND.defaultMessage());
            }
        }
        log.info("Story outputs deleted on request");
    }
}
