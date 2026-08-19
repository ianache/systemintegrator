package com.cl2.integration.integration.sync;

import java.util.Optional;
import java.util.UUID;

public interface SyncStateRepository {
    Optional<SyncState> find(UUID profileId);
    void upsert(SyncState state);
}
