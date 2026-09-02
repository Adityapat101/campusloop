package com.example.marketplace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StudentMarketplaceApplicationTests {

    @Test
    void applicationEntryPointExists() {
        assertThat(StudentMarketplaceApplication.class).isNotNull();
    }
}
