package com.etherstories.link;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerPresenceTest {

    @Test
    void heartbeatTimeoutMatchesTwentySecondOfflineWindow() {
        long now = 1_000_000L;
        long offlineAfter = 20_000L;
        Models.ServerRow live = new Models.ServerRow(
                "ES2", "以太物语", "ES2", "", "CYAN", "TERRACOTTA", 1.0, now - 5_000L, now);
        Models.ServerRow stale = new Models.ServerRow(
                "SNC", "对端", "SNC", "", "ORANGE", "CONCRETE", 1.0, now - 21_000L, now);
        Models.ServerRow never = new Models.ServerRow(
                "NEW", "新服", "", "", "WHITE", "TERRACOTTA", 1.0, 0, now);
        assertTrue(live.online(offlineAfter));
        assertFalse(stale.online(offlineAfter));
        assertFalse(never.online(offlineAfter));
    }
}
