package com.cl2.integration.adapter.out.generic;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
class GenericJdbcAdapterContextTest {

    @Autowired
    private GenericJdbcAdapter genericJdbcAdapter;

    @Test
    void isRegisteredAsASpringBean() {
        assertThat(genericJdbcAdapter).isNotNull();
    }
}
