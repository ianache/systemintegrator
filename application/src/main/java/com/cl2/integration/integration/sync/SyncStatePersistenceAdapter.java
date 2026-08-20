package com.cl2.integration.integration.sync;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class SyncStatePersistenceAdapter implements SyncStateRepository {

    private final SpringDataSyncStateRepository repository;

    SyncStatePersistenceAdapter(SpringDataSyncStateRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SyncState> find(UUID profileId) {
        return repository.findById(profileId).map(SyncStateJpaEntity::toDomain);
    }

    @Override
    @Transactional
    public void upsert(SyncState state) {
        SyncStateJpaEntity entity = repository.findById(state.profileId())
                .orElseGet(SyncStateJpaEntity::new);
        entity.applyUpdate(state);
        repository.save(entity);
    }
}
