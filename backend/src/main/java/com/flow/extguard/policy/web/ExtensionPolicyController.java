package com.flow.extguard.policy.web;

import com.flow.extguard.common.RequestActors;
import com.flow.extguard.config.PolicyProperties;
import com.flow.extguard.policy.domain.CustomExtension;
import com.flow.extguard.policy.service.ExtensionPolicyService;
import com.flow.extguard.policy.web.dto.AddCustomExtensionRequest;
import com.flow.extguard.policy.web.dto.AuditLogDto;
import com.flow.extguard.policy.web.dto.PolicyResponse;
import com.flow.extguard.policy.web.dto.UpdateFixedExtensionRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Extension policy management.
 *
 * <p>Note the shape of this API: fixed extensions can only be toggled, and custom
 * extensions can only be added or removed. There is deliberately no endpoint that
 * deletes a fixed extension.
 */
@RestController
@RequestMapping("/api/v1/policy")
public class ExtensionPolicyController {

    private final ExtensionPolicyService policyService;
    private final PolicyProperties policyProperties;

    public ExtensionPolicyController(ExtensionPolicyService policyService,
                                     PolicyProperties policyProperties) {
        this.policyService = policyService;
        this.policyProperties = policyProperties;
    }

    @GetMapping("/extensions")
    public PolicyResponse getPolicy() {
        return PolicyResponse.from(policyService.getPolicy(), policyProperties);
    }

    @PatchMapping("/extensions/fixed/{extension}")
    public PolicyResponse updateFixed(@PathVariable String extension,
                                      @Valid @RequestBody UpdateFixedExtensionRequest body,
                                      HttpServletRequest request) {
        policyService.setFixedBlocked(extension, body.blocked(), RequestActors.from(request));
        return PolicyResponse.from(policyService.getPolicy(), policyProperties);
    }

    @PostMapping("/extensions/custom")
    public ResponseEntity<PolicyResponse> addCustom(@Valid @RequestBody AddCustomExtensionRequest body,
                                                    HttpServletRequest request) {
        CustomExtension created = policyService.addCustom(body.extension(), RequestActors.from(request));
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .header("Location", "/api/v1/policy/extensions/custom/" + created.getExtension())
                .body(PolicyResponse.from(policyService.getPolicy(), policyProperties));
    }

    @DeleteMapping("/extensions/custom/{extension}")
    public PolicyResponse removeCustom(@PathVariable String extension, HttpServletRequest request) {
        policyService.removeCustom(extension, RequestActors.from(request));
        return PolicyResponse.from(policyService.getPolicy(), policyProperties);
    }

    /** Policy change history: who changed what, when, and from which value. */
    @GetMapping("/audit")
    public List<AuditLogDto> auditLog(@RequestParam(defaultValue = "50") int limit) {
        return policyService.recentAuditLog(limit).stream().map(AuditLogDto::from).toList();
    }
}
