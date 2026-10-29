package com.example.debt.invitation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.debt.access.UserRole;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InvitationTest {
    @Test
    void tokenHas32RandomBytesCanonicalEncodingAndHashWithoutDiagnosticDisclosure() {
        InvitationToken first = InvitationToken.generate();
        InvitationToken second = InvitationToken.generate();
        assertThat(Base64.getUrlDecoder().decode(first.value())).hasSize(32);
        assertThat(first.value()).hasSize(43).isNotEqualTo(second.value());
        assertThat(first.hash()).matches("[0-9a-f]{64}").isEqualTo(new InvitationToken(first.value()).hash());
        assertThat(first.toString()).doesNotContain(first.value(), first.hash());
    }

    @Test
    void rejectsMalformedAndNonCanonicalTokenRepresentations() {
        assertThatThrownBy(() -> new InvitationToken(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InvitationToken("wrong")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InvitationToken("A".repeat(42) + "B")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InvitationToken("A".repeat(43) + "=" )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deadlineIsExclusiveAndConsumedInvitationCannotBeReused() {
        Instant created = Instant.parse("2026-10-29T00:00:00Z");
        Instant deadline = created.plusSeconds(24 * 60 * 60);
        Invitation invitation = new Invitation(UUID.randomUUID(), UUID.randomUUID(), "fictional@example.test", UserRole.ADMIN,
                InvitationToken.generate().hash(), UUID.randomUUID(), created, deadline, null, null);
        assertThat(invitation.isAvailableAt(deadline.minusNanos(1))).isTrue();
        assertThat(invitation.isAvailableAt(deadline)).isFalse();
        assertThat(invitation.isAvailableAt(deadline.plusNanos(1))).isFalse();
        Invitation consumed = new Invitation(invitation.id(), invitation.tenantId(), invitation.email(), invitation.role(),
                invitation.tokenHash(), invitation.createdByUserId(), created, deadline, created.plusSeconds(1), UUID.randomUUID());
        assertThat(consumed.isAvailableAt(created.plusSeconds(2))).isFalse();
        assertThat(invitation.toString()).doesNotContain(invitation.tokenHash(), invitation.email());
    }
}
