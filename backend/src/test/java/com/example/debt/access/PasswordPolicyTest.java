package com.example.debt.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

class PasswordPolicyTest {
    @Test
    void enforcesCreationBoundsInCodePointsWithoutTrimming() {
        PasswordPolicy.validateCreation(" ".repeat(12));
        PasswordPolicy.validateCreation("😀".repeat(128));
        PasswordPolicy.validateLogin("a");
        assertThatThrownBy(() -> PasswordPolicy.validateCreation("𠮷".repeat(11)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PasswordPolicy.validateLogin("😀".repeat(129)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PasswordPolicy.validateLogin(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void usesSaltedArgon2idAndChecksBytesBeyondBcryptLimit() {
        var passwords = new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);
        String password = "𠮷".repeat(30) + "  exact suffix  ";
        String first = passwords.encode(password);
        String second = passwords.encode(password);
        assertThat(first).startsWith("$argon2id$v=19$m=19456,t=2,p=1$").isNotEqualTo(second);
        assertThat(passwords.matches(password, first)).isTrue();
        assertThat(passwords.matches("𠮷".repeat(30) + "  wrong suffix", first)).isFalse();
        assertThat(passwords.matches(password.strip(), first)).isFalse();
    }

    @Test
    void normalizesEmailButRejectsNonAsciiAndMalformedAddresses() {
        assertThat(new EmailAddress(" Alpha-Admin@EXAMPLE.TEST ").canonical()).isEqualTo("alpha-admin@example.test");
        assertThatThrownBy(() -> new EmailAddress("担当者@example.test")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmailAddress("not-an-email")).isInstanceOf(IllegalArgumentException.class);
    }
}
