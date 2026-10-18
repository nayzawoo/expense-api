package com.nayzawoo.expense.identity;

import com.nayzawoo.expense.shared.AuditEventWriter;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class IdentityAuditService {

    static final String EVENT_PASSWORD_ROTATED = "PASSWORD_ROTATED";
    static final String EVENT_TEMPORARY_PASSWORD_ASSIGNED = "TEMPORARY_PASSWORD_ASSIGNED";
    static final String EVENT_OPERATOR_RECOVERY = "OPERATOR_RECOVERY";
    static final String EVENT_LOGIN_EMAIL_CHANGED = "LOGIN_EMAIL_CHANGED";

    static final String RESOURCE_USER = "USER";

    private final AuditEventWriter audit;

    IdentityAuditService(AuditEventWriter audit) {
        this.audit = audit;
    }

    void record(
            UUID householdId,
            UUID actorUserId,
            String eventType,
            String resourceType,
            UUID resourceId,
            String detailsJson) {
        audit.record(householdId, actorUserId, eventType, resourceType, resourceId, detailsJson);
    }

    static String targetUserDetails(UUID targetUserId) {
        return "{\"targetUserId\":\"" + targetUserId + "\"}";
    }

    static String emailChangeDetails(UUID targetUserId, String previousEmail, String newEmail) {
        return "{\"targetUserId\":\""
                + targetUserId
                + "\",\"previousEmail\":\""
                + AuditEventWriter.escape(previousEmail)
                + "\",\"newEmail\":\""
                + AuditEventWriter.escape(newEmail)
                + "\"}";
    }
}
