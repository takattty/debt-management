package com.example.debt.collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CollectionValuesTest {
    @Test
    void explicitBusinessDateCountsCalendarDaysWithoutTimeZoneOrBucketRounding() {
        LocalDate due = LocalDate.of(2026, 11, 30);
        assertThat(CollectionValues.daysOverdue(due, due.minusDays(1))).isZero();
        assertThat(CollectionValues.daysOverdue(due, due)).isZero();
        assertThat(CollectionValues.daysOverdue(due, due.plusDays(1))).isEqualTo(1);
        assertThat(CollectionValues.daysOverdue(LocalDate.of(2028, 2, 28), LocalDate.of(2028, 3, 1))).isEqualTo(2);
    }

    @Test
    void businessDatesRequireAnExplicitSupportedYear() {
        assertThatThrownBy(() -> CollectionValues.date(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CollectionValues.date(LocalDate.of(0, 1, 1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CollectionValues.date(LocalDate.of(10000, 1, 1))).isInstanceOf(IllegalArgumentException.class);
    }
}
