package com.cl2.integration.adapter.out.persistence;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.JpaRepository;

import static org.assertj.core.api.Assertions.assertThat;

class SpringDataIntegrationProfileRepositoryTest {

    @Test
    void exposesOnlyTenantScopedLookupOperations() {
        assertThat(JpaRepository.class.isAssignableFrom(SpringDataIntegrationProfileRepository.class)).isFalse();
        assertThat(Arrays.stream(SpringDataIntegrationProfileRepository.class.getDeclaredMethods())
                .filter(method -> method.getName().startsWith("find")
                        || method.getName().startsWith("exists")
                        || method.getName().startsWith("update"))
                .allMatch(method -> Arrays.stream(method.getParameterTypes())
                        .anyMatch(parameterType -> parameterType.equals(java.util.UUID.class))))
                .isTrue();
    }
}
