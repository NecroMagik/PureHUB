package com.necromagik.purehub;

interface IPureHubService {
    void startVolumeListener();
    void stopVolumeListener();
    boolean isListening();
    void setPaused(boolean paused);
    boolean isPaused();
    void destroy();
}