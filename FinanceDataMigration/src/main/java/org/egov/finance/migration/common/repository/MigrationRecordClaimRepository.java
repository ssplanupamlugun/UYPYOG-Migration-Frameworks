package org.egov.finance.migration.common.repository;

import java.util.Optional;

import org.egov.finance.migration.common.entity.MigrationRecordClaim;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MigrationRecordClaimRepository extends JpaRepository<MigrationRecordClaim, Long> {

    Optional<MigrationRecordClaim> findByTenantIdAndModuleCodeAndRecordKey(
            String tenantId, String moduleCode, String recordKey);

    @Modifying
    @Query("""
            update MigrationRecordClaim c
               set c.status = :status,
                   c.updatedTime = CURRENT_TIMESTAMP
             where c.tenantId = :tenantId
               and c.moduleCode = :moduleCode
               and c.recordKey = :recordKey
               and c.jobId = :jobId
            """)
    int updateStatus(@Param("tenantId") String tenantId,
                     @Param("moduleCode") String moduleCode,
                     @Param("recordKey") String recordKey,
                     @Param("jobId") String jobId,
                     @Param("status") String status);

    @Modifying
    @Query("""
            delete from MigrationRecordClaim c
             where c.tenantId = :tenantId
               and c.moduleCode = :moduleCode
               and c.recordKey = :recordKey
               and c.jobId = :jobId
               and c.status = 'CLAIMED'
            """)
    int release(@Param("tenantId") String tenantId,
                @Param("moduleCode") String moduleCode,
                @Param("recordKey") String recordKey,
                @Param("jobId") String jobId);
}
