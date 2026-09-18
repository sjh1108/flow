package com.flow.extguard.policy.repository;

import com.flow.extguard.policy.domain.PolicyAuditLog;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

public interface PolicyAuditLogRepository extends Repository<PolicyAuditLog, Long> {

    PolicyAuditLog save(PolicyAuditLog auditLog);

    List<PolicyAuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
