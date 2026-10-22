package com.uyirhealthapp.payment_service.linkedaccount;

import com.uyirhealthapp.payment_service.gateway.LinkedAccountGateway.Activation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public final class LinkedAccountDtos {

    private LinkedAccountDtos() {
    }

    public static final String PAN = "[A-Z]{5}[0-9]{4}[A-Z]";

    /**
     * Everything Razorpay needs to pay a business. Sent in full on every call: the steps already done at Razorpay
     * are skipped, and once the account exists only the bank account can still change (business details are fixed
     * there and are changed through Razorpay support). referenceId: the module's own id for the business.
     */
    public record OnboardRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Z][A-Z0-9_]*") String ownerType,
            @NotBlank @Size(max = 128) String referenceId,
            @NotBlank @Size(min = 4, max = 200) String legalBusinessName,
            @NotBlank @Pattern(regexp = "individual|proprietorship|partnership|private_limited|public_limited|llp"
                    + "|ngo|trust|society|not_yet_registered|educational_institutes") String businessType,
            @NotBlank @Size(min = 4, max = 120) String contactName,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Pattern(regexp = "[0-9]{10}") String phone,
            @NotBlank @Size(max = 64) String category,
            @NotBlank @Size(max = 64) String subcategory,
            @Valid @NotNull Address address,
            @NotBlank @Pattern(regexp = PAN) String pan,
            @Pattern(regexp = "[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]") String gst,
            @Valid @NotNull Stakeholder stakeholder,
            @Valid @NotNull BankAccount bankAccount,
            @AssertTrue(message = "must be true: the business accepts Razorpay's Route terms") boolean tncAccepted) {
    }

    public record Address(@NotBlank @Size(max = 100) String street1,
                          @Size(max = 100) String street2,
                          @NotBlank @Size(max = 100) String city,
                          @NotBlank @Size(max = 100) String state,
                          @NotBlank @Pattern(regexp = "[1-9][0-9]{5}") String postalCode,
                          @Pattern(regexp = "[A-Z]{2}") String country) {
    }

    /** The owner or authorised signatory; pan is their personal PAN. */
    public record Stakeholder(@NotBlank @Size(max = 120) String name,
                              @NotBlank @Email @Size(max = 254) String email,
                              @Pattern(regexp = "[0-9]{10}") String phone,
                              @NotBlank @Pattern(regexp = PAN) String pan) {
    }

    public record BankAccount(@NotBlank @Pattern(regexp = "[0-9]{9,18}") String accountNumber,
                              @NotBlank @Pattern(regexp = "[A-Z]{4}0[A-Z0-9]{6}") String ifscCode,
                              @NotBlank @Size(max = 120) String beneficiaryName) {
    }

    /**
     * A linked account as callers see it. Payouts are possible only when status is ACTIVATED; under
     * NEEDS_CLARIFICATION, requirements lists what Razorpay still needs. The bank account shows its last 4 digits only.
     */
    public record LinkedAccountView(UUID id, String ownerType, String referenceId, Activation status,
                                    String providerAccountId, List<String> requirements, String legalBusinessName,
                                    String bankAccountLast4, String ifscCode, String beneficiaryName,
                                    Instant activatedAt, Instant createdAt, Instant updatedAt) {

        public static LinkedAccountView of(LinkedAccount a) {
            List<String> requirements = a.getRequirements() == null ? List.of()
                    : Arrays.asList(a.getRequirements().split(", "));
            return new LinkedAccountView(a.getId(), a.getOwnerType(), a.getReferenceId(), a.getStatus(),
                    a.getProviderAccountId(), requirements, a.getLegalBusinessName(), a.getBankAccountLast4(),
                    a.getIfscCode(), a.getBeneficiaryName(), a.getActivatedAt(), a.getCreatedAt(), a.getUpdatedAt());
        }
    }
}
