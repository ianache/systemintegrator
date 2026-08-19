package com.cl2.integration.integration.sync;

import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

interface SpringDataSyncStateRepository extends CrudRepository<SyncStateJpaEntity, UUID> {
}
