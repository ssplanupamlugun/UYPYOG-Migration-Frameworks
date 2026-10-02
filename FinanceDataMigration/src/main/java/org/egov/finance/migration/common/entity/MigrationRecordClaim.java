package org.egov.finance.migration.common.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

@Entity
@Table(name = "migration_record_claim",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_migration_record_claim",
                columnNames = {"tenant_id", "module_code", "record_key"}))
@Data
public class MigrationRecordClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "module_code", nullable = false)
    private String moduleCode;

    @Column(name = "record_key", nullable = false)
    private String recordKey;

    @Column(name = "job_id", nullable = false)
    private String jobId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "created_time", nullable = false)
    private LocalDateTime createdTime;

    @Column(name = "updated_time", nullable = false)
    private LocalDateTime updatedTime;
}
