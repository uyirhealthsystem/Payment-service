package com.uyirhealthapp.payment_service.linkedaccount;

import com.uyirhealthapp.payment_service.config.SecurityConfig;
import com.uyirhealthapp.payment_service.linkedaccount.LinkedAccountDtos.LinkedAccountView;
import com.uyirhealthapp.payment_service.linkedaccount.LinkedAccountDtos.OnboardRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping(SecurityConfig.API + "/linked-accounts")
@Tag(name = "linked accounts")
@SecurityRequirement(name = "bearer")
public class LinkedAccountController {

    private final LinkedAccountService service;

    public LinkedAccountController(LinkedAccountService service) {
        this.service = service;
    }

    @Operation(summary = "Onboard (or continue onboarding) the caller's business as a Razorpay Route linked account",
            description = "Send the full details every time: steps already done at Razorpay are skipped, and once "
                    + "the account exists only the bank account can still change. A 400 carries Razorpay's reason "
                    + "(invalid PAN, IFSC, ...).")
    @PutMapping
    public LinkedAccountView onboard(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody OnboardRequest request) {
        return service.onboard(request, jwt);
    }

    @Operation(summary = "The caller's own linked account for an ownerType (e.g. HOSPITAL)")
    @GetMapping
    public LinkedAccountView mine(@AuthenticationPrincipal Jwt jwt, @RequestParam String ownerType) {
        return service.mine(ownerType, jwt);
    }

    @GetMapping("/{id}")
    public LinkedAccountView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.get(id, jwt);
    }

    @Operation(summary = "Ask Razorpay for the activation status now, instead of waiting for its webhook")
    @PostMapping("/{id}/refresh")
    public LinkedAccountView refresh(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.refresh(id, jwt);
    }
}
