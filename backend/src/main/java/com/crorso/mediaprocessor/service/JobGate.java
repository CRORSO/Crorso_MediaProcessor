package com.crorso.mediaprocessor.service;

import com.crorso.mediaprocessor.config.MediaProperties;
import com.crorso.mediaprocessor.exception.ErrorCode;
import com.crorso.mediaprocessor.exception.MediaException;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Admission control for FFmpeg work.
 *
 * <ul>
 *   <li>At most {@code MAX_CONCURRENT_JOBS} jobs run at once; extra requests are refused with 503 rather than queued.</li>
 *   <li>Only one job or delete may hold a given story ID at a time; a second request gets 409.</li>
 * </ul>
 */
@Component
public class JobGate {

    private final Semaphore slots;
    private final Set<String> activeStories = ConcurrentHashMap.newKeySet();

    public JobGate(MediaProperties properties) {
        this.slots = new Semaphore(properties.maxConcurrentJobs(), true);
    }

    /** Takes a processing slot and the story lock. Closing the ticket releases both. */
    public Ticket acquire(String storyId) {
        if (!slots.tryAcquire()) {
            throw new MediaException(ErrorCode.SERVICE_BUSY, ErrorCode.SERVICE_BUSY.defaultMessage());
        }
        Ticket story;
        try {
            story = lockStory(storyId);
        } catch (MediaException e) {
            slots.release();
            throw e;
        }
        return () -> {
            story.close();
            slots.release();
        };
    }

    /** Takes only the story lock, for operations such as deletion that do not need a processing slot. */
    public Ticket lockStory(String storyId) {
        if (!activeStories.add(storyId)) {
            throw new MediaException(ErrorCode.STORY_BUSY, ErrorCode.STORY_BUSY.defaultMessage());
        }
        return () -> activeStories.remove(storyId);
    }

    @FunctionalInterface
    public interface Ticket extends AutoCloseable {

        @Override
        void close();
    }
}
