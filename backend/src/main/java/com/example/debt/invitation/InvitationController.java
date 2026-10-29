package com.example.debt.invitation;

import com.example.debt.access.CurrentUser;
import com.example.debt.access.UserRole;
import com.example.debt.generated.api.InvitationsApi;
import com.example.debt.generated.model.InvitationIssueRequest;
import com.example.debt.generated.model.InvitationIssuedResponse;
import java.time.ZoneOffset;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile({"local", "auth-test"})
public class InvitationController implements InvitationsApi {
    private final InvitationService invitations;
    private final CurrentUser currentUser;

    public InvitationController(InvitationService invitations, CurrentUser currentUser) {
        this.invitations = invitations;
        this.currentUser = currentUser;
    }

    @Override
    public ResponseEntity<InvitationIssuedResponse> issueInvitation(InvitationIssueRequest request) {
        var issued = invitations.issue(currentUser.require(), request.getEmail(), UserRole.valueOf(request.getRole().getValue()));
        var result = new InvitationIssuedResponse(issued.invitation().id(), issued.invitation().expiresAt().atOffset(ZoneOffset.UTC),
                "/accept-invitation#token=" + issued.token().value());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer").body(result);
    }

}
