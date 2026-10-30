package com.example.debt.collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CollectionValuesTest {
    @Test
    void sharedNotesPreserveMultilineTextAndCountSupplementaryCharactersAsCodePoints() {
        assertThat(CollectionValues.note(" 依頼内容\n次の担当への確認\t事項 ")).isEqualTo("依頼内容\n次の担当への確認\t事項");
        assertThat(CollectionValues.note("𠮷".repeat(5000))).hasSize(10000);
        assertThatThrownBy(() -> CollectionValues.note("𠮷".repeat(5001))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CollectionValues.note(" \n\t ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CollectionValues.note("invalid\u0000text")).isInstanceOf(IllegalArgumentException.class);
    }
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
