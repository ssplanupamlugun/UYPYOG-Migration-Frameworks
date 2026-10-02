package org.egov.finance.migration.common.repository;

import org.egov.finance.migration.common.entity.MigrationRecordClaim;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MigrationRecordClaimRepository extends JpaRepository<MigrationRecordClaim, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO migration_record_claim
                (tenant_id, module_code, record_key, job_id, status, created_time, updated_time)
            VALUES
                (:tenantId, :moduleCode, :recordKey, :jobId, 'CLAIMED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (tenant_id, module_code, record_key) DO NOTHING
            """, nativeQuery = true)
    int tryClaim(@Param("tenantId") String tenantId,
                 @Param("moduleCode") String moduleCode,
                 @Param("recordKey") String recordKey,
                 @Param("jobId") String jobId);

    @Modifying
    @Query(value = """
            UPDATE migration_record_claim
               SET status = :status,
                   updated_time = CURRENT_TIMESTAMP
             WHERE tenant_id = :tenantId
               AND module_code = :moduleCode
               AND record_key = :recordKey
               AND job_id = :jobId
            """, nativeQuery = true)
    int updateStatus(@Param("tenantId") String tenantId,
                     @Param("moduleCode") String moduleCode,
                     @Param("recordKey") String recordKey,
                     @Param("jobId") String jobId,
                     @Param("status") String status);

    @Modifying
    @Query(value = """
            DELETE FROM migration_record_claim
             WHERE tenant_id = :tenantId
               AND module_code = :moduleCode
               AND record_key = :recordKey
               AND job_id = :jobId
               AND status = 'CLAIMED'
            """, nativeQuery = true)
    int release(@Param("tenantId") String tenantId,
                @Param("moduleCode") String moduleCode,
                @Param("recordKey") String recordKey,
                @Param("jobId") String jobId);
}
