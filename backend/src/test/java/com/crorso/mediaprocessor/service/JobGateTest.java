package com.crorso.mediaprocessor.service;

import com.crorso.mediaprocessor.config.MediaProperties;
import com.crorso.mediaprocessor.exception.ErrorCode;
import com.crorso.mediaprocessor.exception.MediaException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobGateTest {

    private static JobGate gate(int maxConcurrentJobs) {
        return new JobGate(new MediaProperties(null, "t", "o", "ffmpeg", "ffprobe", 1, 1, 1, 1, maxConcurrentJobs,
                900, 1, 2, 2, 1, "128k", "black", 0, null));
    }

    @Test
    void secondJobForSameStoryIsRefusedWithConflict() {
        JobGate gate = gate(5);
        try (JobGate.Ticket first = gate.acquire("STORY-1")) {
            assertThatThrownBy(() -> gate.acquire("STORY-1"))
                    .isInstanceOfSatisfying(MediaException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.STORY_BUSY));
        }
    }

    @Test
    void differentStoriesRunConcurrently() {
        JobGate gate = gate(5);
        try (JobGate.Ticket a = gate.acquire("STORY-1"); JobGate.Ticket b = gate.acquire("STORY-2")) {
            assertThat(a).isNotNull();
            assertThat(b).isNotNull();
        }
    }

    @Test
    void saturatedServiceRefusesWithServiceBusy() {
        JobGate gate = gate(1);
        try (JobGate.Ticket first = gate.acquire("STORY-1")) {
            assertThatThrownBy(() -> gate.acquire("STORY-2"))
                    .isInstanceOfSatisfying(MediaException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.SERVICE_BUSY));
        }
    }

    @Test
    void closingTicketReleasesSlotAndStoryLock() {
        JobGate gate = gate(1);
        gate.acquire("STORY-1").close();
        try (JobGate.Ticket again = gate.acquire("STORY-1")) {
            assertThat(again).isNotNull();
        }
    }

    @Test
    void failedStoryLockDoesNotLeakSlot() {
        JobGate gate = gate(1);
        try (JobGate.Ticket held = gate.lockStory("STORY-1")) {
            assertThatThrownBy(() -> gate.acquire("STORY-1")).isInstanceOf(MediaException.class);
        }
        try (JobGate.Ticket ok = gate.acquire("STORY-2")) {
            assertThat(ok).isNotNull();
        }
    }
}
