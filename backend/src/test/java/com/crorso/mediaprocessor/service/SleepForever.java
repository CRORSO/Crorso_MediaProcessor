package com.crorso.mediaprocessor.service;

/** Test helper process that never exits on its own, used to exercise timeout handling. */
public final class SleepForever {

    private SleepForever() {
    }

    public static void main(String[] args) throws InterruptedException {
        Thread.sleep(600_000L);
    }
}
