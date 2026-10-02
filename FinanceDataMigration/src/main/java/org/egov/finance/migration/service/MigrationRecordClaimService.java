package org.egov.finance.migration.service;

import java.time.LocalDateTime;
import java.util.List;

import org.egov.finance.migration.common.entity.MigrationRecordClaim;
import org.egov.finance.migration.common.repository.MigrationRecordClaimRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MigrationRecordClaimService {

    private static final String CLAIMED = "CLAIMED";
    private static final String SUCCESS = "SUCCESS";

    private final MigrationRecordClaimRepository repository;

    public MigrationRecordClaimService(MigrationRecordClaimRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public boolean claim(String tenantId, String moduleCode, List<String> recordKeys, String jobId) {
        if (recordKeys == null || recordKeys.isEmpty()) {
            return true;
        }

        for (String recordKey : recordKeys.stream()
                .filter(key -> key != null && !key.isBlank())
                .distinct()
                .toList()) {

            if (repository.findByTenantIdAndModuleCodeAndRecordKey(
                    tenantId, moduleCode, recordKey)
                    .map(claim -> CLAIMED.equals(claim.getStatus()) || SUCCESS.equals(claim.getStatus()))
                    .orElse(false)) {
                return false;
            }

            try {
                MigrationRecordClaim claim = new MigrationRecordClaim();
                claim.setTenantId(tenantId);
                claim.setModuleCode(moduleCode);
                claim.setRecordKey(recordKey);
                claim.setJobId(jobId);
                claim.setStatus(CLAIMED);
                claim.setCreatedTime(LocalDateTime.now());
                claim.setUpdatedTime(LocalDateTime.now());
                repository.saveAndFlush(claim);
            } catch (DataIntegrityViolationException e) {
                return false;
            }
        }

        return true;
    }

    @Transactional
    public void markSuccess(String tenantId, String moduleCode, List<String> recordKeys, String jobId) {
        updateStatus(tenantId, moduleCode, recordKeys, jobId, SUCCESS);
    }

    @Transactional
    public void release(String tenantId, String moduleCode, List<String> recordKeys, String jobId) {
        if (recordKeys == null) return;
        recordKeys.stream()
                .filter(key -> key != null && !key.isBlank())
                .distinct()
                .forEach(key -> repository.release(tenantId, moduleCode, key, jobId));
    }

    private void updateStatus(String tenantId, String moduleCode, List<String> recordKeys,
                              String jobId, String status) {
        if (recordKeys == null) return;
        recordKeys.stream()
                .filter(key -> key != null && !key.isBlank())
                .distinct()
                .forEach(key -> repository.updateStatus(tenantId, moduleCode, key, jobId, status));
    }
}
