package com.cl2.integration.integration.lookup;

import com.cl2.integration.integration.lookup.domain.ValueLookup;
import com.cl2.integration.integration.lookup.domain.ValueLookupRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
class ValueLookupPersistenceAdapterTest {

    @Autowired
    private ValueLookupRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final UUID TENANT_ID_1 = UUID.randomUUID();
    private static final UUID TENANT_ID_2 = UUID.randomUUID();
    private static final String EXTERNAL_SOURCE = "sap-hana";
    private static final String CATALOG_CODE = "VEHICLE_TYPE";

    @BeforeEach
    void clear() {
        jdbcTemplate.update("DELETE FROM integration_value_lookup");
    }

    @Test
    void savesAndFindsTargetValueBySourceValue() {
        ValueLookup lookup = ValueLookup.create(
                UUID.randomUUID(),
                TENANT_ID_1,
                EXTERNAL_SOURCE,
                CATALOG_CODE,
                "AUTO",
                "SEDAN",
                "Automobile mapping",
                true
        );

        ValueLookup saved = repository.save(lookup);
        assertThat(saved).isNotNull();
        assertThat(saved.id()).isEqualTo(lookup.id());
        assertThat(saved.tenantId()).isEqualTo(TENANT_ID_1);

        Optional<String> targetValue = repository.findTargetValue(
                TENANT_ID_1,
                EXTERNAL_SOURCE,
                CATALOG_CODE,
                "AUTO"
        );

        assertThat(targetValue).isPresent().contains("SEDAN");
    }

    @Test
    void returnsEmptyWhenLookupNotFoundOrInactive() {
        ValueLookup inactiveLookup = ValueLookup.create(
                UUID.randomUUID(),
                TENANT_ID_1,
                EXTERNAL_SOURCE,
                CATALOG_CODE,
                "MOTO",
                "MOTORCYCLE",
                "Motorcycle mapping",
                false
        );
        repository.save(inactiveLookup);

        Optional<String> targetValueInactive = repository.findTargetValue(
                TENANT_ID_1,
                EXTERNAL_SOURCE,
                CATALOG_CODE,
                "MOTO"
        );
        assertThat(targetValueInactive).isEmpty();

        Optional<String> targetValueNonExistent = repository.findTargetValue(
                TENANT_ID_1,
                EXTERNAL_SOURCE,
                CATALOG_CODE,
                "UNKNOWN"
        );
        assertThat(targetValueNonExistent).isEmpty();
    }

    @Test
    void isolatesLookupsByTenantAndSource() {
        ValueLookup lookupTenant1 = ValueLookup.create(
                UUID.randomUUID(),
                TENANT_ID_1,
                EXTERNAL_SOURCE,
                CATALOG_CODE,
                "TRUCK",
                "CAMION",
                "Desc",
                true
        );
        repository.save(lookupTenant1);

        Optional<String> resultTenant2 = repository.findTargetValue(
                TENANT_ID_2,
                EXTERNAL_SOURCE,
                CATALOG_CODE,
                "TRUCK"
        );
        assertThat(resultTenant2).isEmpty();

        Optional<String> resultOtherSource = repository.findTargetValue(
                TENANT_ID_1,
                "salesforce",
                CATALOG_CODE,
                "TRUCK"
        );
        assertThat(resultOtherSource).isEmpty();
    }

    @Test
    void findsAllMappingsForCatalog() {
        ValueLookup item1 = ValueLookup.create(
                UUID.randomUUID(),
                TENANT_ID_1,
                EXTERNAL_SOURCE,
                CATALOG_CODE,
                "V1",
                "T1",
                "Desc 1",
                true
        );
        ValueLookup item2 = ValueLookup.create(
                UUID.randomUUID(),
                TENANT_ID_1,
                EXTERNAL_SOURCE,
                CATALOG_CODE,
                "V2",
                "T2",
                "Desc 2",
                true
        );
        ValueLookup itemOtherCatalog = ValueLookup.create(
                UUID.randomUUID(),
                TENANT_ID_1,
                EXTERNAL_SOURCE,
                "COLOR",
                "RED",
                "ROJO",
                "Desc 3",
                true
        );

        repository.save(item1);
        repository.save(item2);
        repository.save(itemOtherCatalog);

        List<ValueLookup> mappings = repository.findAll(TENANT_ID_1, EXTERNAL_SOURCE, CATALOG_CODE);
        assertThat(mappings).hasSize(2);
        assertThat(mappings).extracting(ValueLookup::sourceValue).containsExactlyInAnyOrder("V1", "V2");
    }

    @Test
    void deletesById() {
        UUID id = UUID.randomUUID();
        ValueLookup lookup = ValueLookup.create(
                id,
                TENANT_ID_1,
                EXTERNAL_SOURCE,
                CATALOG_CODE,
                "TO_DELETE",
                "TARGET",
                "Desc",
                true
        );
        repository.save(lookup);

        assertThat(repository.findTargetValue(TENANT_ID_1, EXTERNAL_SOURCE, CATALOG_CODE, "TO_DELETE")).isPresent();

        repository.deleteById(TENANT_ID_1, id);

        assertThat(repository.findTargetValue(TENANT_ID_1, EXTERNAL_SOURCE, CATALOG_CODE, "TO_DELETE")).isEmpty();
    }
}
