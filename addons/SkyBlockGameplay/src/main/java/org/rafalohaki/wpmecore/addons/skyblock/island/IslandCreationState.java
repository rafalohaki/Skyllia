package org.rafalohaki.wpmecore.addons.skyblock.island;

/**
 * Coordination CREATING -> INITIALIZING -> READY.
 * Reconnect returns status, not second create.
 */
public enum IslandCreationState {
    IDLE,
    CREATING,
    INITIALIZING,
    READY,
    FAILED
}
