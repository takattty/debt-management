package com.example.debt.invitation;

import com.example.debt.access.CurrentUser;
import com.example.debt.access.UserRole;
import com.example.debt.generated.api.InvitationsApi;
import com.example.debt.generated.model.InvitationAcceptRequest;
import com.example.debt.generated.model.InvitationAcceptedResponse;
import com.example.debt.generated.model.InvitationInspectRequest;
import com.example.debt.generated.model.InvitationInspectionResponse;
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

    @Override
    public ResponseEntity<InvitationInspectionResponse> inspectInvitation(InvitationInspectRequest request) {
        var preview = invitations.inspect(request.getToken());
        var result = new InvitationInspectionResponse(preview.tenantDisplayName(), preview.maskedEmail(),
                InvitationInspectionResponse.RoleEnum.fromValue(preview.role().name()), preview.expiresAt().atOffset(ZoneOffset.UTC));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer").body(result);
    }

    @Override
    public ResponseEntity<InvitationAcceptedResponse> acceptInvitation(InvitationAcceptRequest request) {
        var accepted = invitations.accept(request.getToken(), request.getEmail(), request.getPassword());
        var result = new InvitationAcceptedResponse(accepted.userId(), accepted.email(),
                InvitationAcceptedResponse.RoleEnum.fromValue(accepted.role().name()));
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer").body(result);
    }
}
