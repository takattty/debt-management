package com.example.debt.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CustomerValuesTest {
    @Test
    void trimsSurroundingWhitespaceWithoutChangingJapaneseName() {
        CustomerValues values = new CustomerValues(" C001 ", " 株式会社架空 ");
        assertThat(values.code()).isEqualTo("C001");
        assertThat(values.name()).isEqualTo("株式会社架空");
    }

    @Test
    void rejectsMissingOverlongAndControlCharacterValues() {
        assertThatThrownBy(() -> new CustomerValues(" ", "name")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CustomerValues("C".repeat(51), "name"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CustomerValues("C001", "name\nother"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CustomerValues(null, "name"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
