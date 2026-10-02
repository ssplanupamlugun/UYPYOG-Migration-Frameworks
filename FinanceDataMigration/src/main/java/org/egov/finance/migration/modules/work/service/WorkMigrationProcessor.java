package org.egov.finance.migration.modules.work.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.egov.finance.migration.service.MigrationRecordClaimService;

import org.egov.finance.migration.common.dto.MigrationRequest;
import org.egov.finance.migration.common.dto.MigrationResult;
import org.egov.finance.migration.common.dto.RecordResult;
import org.egov.finance.migration.common.dto.RequestInfoBuilder;
import org.egov.finance.migration.common.entity.MigrationJob;
import org.egov.finance.migration.common.entity.MigrationJobDetail;
import org.egov.finance.migration.common.enums.MigrationType;
import org.egov.finance.migration.common.enums.RecordStatus;
import org.egov.finance.migration.common.repository.MigrationJobDetailRepository;
import org.egov.finance.migration.common.repository.MigrationJobRepository;
import org.egov.finance.migration.modules.work.dto.WorkRecord;
import org.egov.finance.migration.modules.work.dto.WorkRequest;
import org.egov.finance.migration.modules.work.dto.WorkResponse;
import org.egov.finance.migration.modules.work.reader.WorkExcelReader;
import org.egov.finance.migration.processor.AbstractMigrationProcessor;
import org.egov.finance.migration.service.DuplicateDetectionService;
import org.springframework.stereotype.Service;
import org.egov.finance.migration.service.MigrationCancellationManager;
import org.egov.finance.migration.service.MigrationProgressPublisher;

@Service
public class WorkMigrationProcessor extends AbstractMigrationProcessor {

    private final WorkExcelReader excelReader;
    private final WorkRequestBuilder requestBuilder;
    private final DuplicateDetectionService duplicateDetectionService;
    private final MigrationRecordClaimService migrationRecordClaimService;
    private final WorkApiClient workApiClient;
    private final MigrationJobRepository migrationJobRepository;
    private final MigrationJobDetailRepository migrationJobDetailRepository;
	private final RequestInfoBuilder requestInfoBuilder;
	private final MigrationCancellationManager cancellationManager;
	private final MigrationProgressPublisher progressPublisher;
	

    public WorkMigrationProcessor(
            WorkExcelReader excelReader,
            WorkRequestBuilder requestBuilder,
            DuplicateDetectionService duplicateDetectionService,
            MigrationRecordClaimService migrationRecordClaimService,
            WorkApiClient workApiClient,
            MigrationJobRepository migrationJobRepository,
            MigrationJobDetailRepository migrationJobDetailRepository,
            RequestInfoBuilder requestInfoBuilder,
            MigrationCancellationManager cancellationManager,
            MigrationProgressPublisher progressPublisher) {

        this.excelReader = excelReader;
        this.requestBuilder = requestBuilder;
        this.duplicateDetectionService = duplicateDetectionService;
        this.migrationRecordClaimService = migrationRecordClaimService;
        this.workApiClient = workApiClient;
        this.migrationJobRepository = migrationJobRepository;
        this.migrationJobDetailRepository = migrationJobDetailRepository;
        this.requestInfoBuilder = requestInfoBuilder;
        this.cancellationManager = cancellationManager;
        this.progressPublisher = progressPublisher;
    }

    @Override
    public MigrationType getMigrationType() {
        return MigrationType.WORK;
    }

    @Override
    protected MigrationResult doProcess(MigrationRequest request) {

        long startTime = System.currentTimeMillis();

        List<RecordResult> recordResults = new ArrayList<>();

        /*
         * ============================================================
         * STEP 1 : READ EXCEL
         * ============================================================
         */

        List<WorkRecord> records = excelReader.read(request.getFilePath());

        /*
         * ============================================================
         * STEP 2 : GET EXISTING MIGRATION JOB
         * ============================================================
         */

        MigrationJob job = migrationJobRepository
                .findByJobId(request.getJobId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Migration job not found: " + request.getJobId()));

        /*
         * ============================================================
         * STEP 3 : INITIALIZE JOB PROGRESS
         * ============================================================
         */

        job.setTotalRecords(records.size());
        job.setSuccessRecords(0);
        job.setFailedRecords(0);
        job.setSkippedRecords(0);
        job.setCurrentRecord(0);
        job.setProgressPercent(0);
        job.setStatus("RUNNING");
        job.setCurrentMessage(
                "Excel read successfully. Starting Work migration...");

        migrationJobRepository.save(job);

        /*
         * ============================================================
         * COUNTERS
         * ============================================================
         */

        int success = 0;
        int failed = 0;
        int skipped = 0;

        /*
         * ============================================================
         * STEP 4 : PROCESS EACH WORK INDEPENDENTLY
         *
         * One Excel row = one Work API request.
         *
         * If one work fails, remaining works are still processed.
         * ============================================================
         */

        for (int i = 0; i < records.size(); i++) {
        	
            if (cancellationManager.isCancelled(request.getJobId())) {

                job.setStatus("CANCELLED");
                job.setCurrentMessage("Migration cancelled by user.");

                migrationJobRepository.saveAndFlush(job);
                progressPublisher.publish(job);

                break;
            }

            WorkRecord record = records.get(i);

            long recordStart = System.currentTimeMillis();

            RecordResult result = new RecordResult();

            /*
             * WorkRecord contains only one Excel row number,
             * therefore startRow and endRow are the same.
             */
            result.setRecordNumber(i + 1);
            result.setStartRow(record.getRowNumber());
            result.setEndRow(record.getRowNumber());

            /*
             * ========================================================
             * DUPLICATE CHECK
             * ========================================================
             */

            List<String> recordKeys = getRecordKeys(record);
            
            boolean alreadyMigrated =
                    duplicateDetectionService.isAlreadyMigrated(
                            request.getTenantId(),
                            request.getMigrationType().name(),
                            recordKeys);

            if (alreadyMigrated) {

                result.setStatus(RecordStatus.SKIPPED);

                result.setMessage(
                        "Work already migrated.");

                result.setExecutionTime(0L);

                skipped++;

                recordResults.add(result);

                /*
                 * Save skipped record
                 */
                saveMigrationDetail(
                        job,
                        request,
                        result,
                        RecordStatus.SKIPPED.name(),
                        recordKeys);

                /*
                 * Update progress
                 */
                updateJobProgress(
                        job,
                        i + 1,
                        records.size(),
                        success,
                        failed,
                        skipped,
                        "Work " + (i + 1)
                                + " of " + records.size()
                                + " skipped - already migrated.");

                /*
                 * Do not process duplicate.
                 */
                continue;
            }

            boolean claimed = migrationRecordClaimService.claim(
                    request.getTenantId(),
                    request.getMigrationType().name(),
                    recordKeys,
                    request.getJobId());

            if (!claimed) {
                result.setStatus(RecordStatus.SKIPPED);
                result.setMessage("Work already already claimed or migrated.");
                result.setExecutionTime(0L);
                skipped++;
                recordResults.add(result);
                saveMigrationDetail(job, request, result, RecordStatus.SKIPPED.name(), recordKeys);
                updateJobProgress(job, i + 1, records.size(), success, failed, skipped,
                        "Record " + (i + 1) + " of " + records.size() + " skipped (already claimed or migrated)");
                continue;
            }

            /*
             * ========================================================
             * PROCESS CURRENT WORK
             * ========================================================
             */

            try {

                /*
                 * Build WorkRequest
                 */
                WorkRequest workRequest =
                        requestBuilder.build(record);

                if (workRequest == null) {

                    throw new RuntimeException(
                            "Unable to build WorkRequest.");
                }

                /*
                 * Call Work API
                 *
                 * WorkApiClient internally creates the API request
                 * and authentication headers.
                 */
                org.egov.finance.migration.common.dto.ApiRequest<WorkRequest>
                        apiRequest = new org.egov.finance.migration.common.dto.ApiRequest<>();

                apiRequest.setTenantId(request.getTenantId());
                apiRequest.setRequest(workRequest);
                apiRequest.setRequestInfo(requestInfoBuilder.build(request.getTenantId()));

                /*
                 * Call Work Create API
                 */
                org.egov.finance.migration.common.dto.ApiResponse<WorkResponse>
                        response = workApiClient.createWork(apiRequest);

                /*
                 * Validate API response
                 */
                if (response == null) {

                    throw new RuntimeException(
                            "Work API returned empty response.");
                }

                if (!response.isSuccess()) {

                    throw new RuntimeException(
                            response.getMessage() != null
                                    ? response.getMessage()
                                    : "Work API failed.");
                }

                result.setStatus(RecordStatus.SUCCESS);

                result.setMessage(
                        "Work created successfully.");

                success++;
                migrationRecordClaimService.markSuccess(
                        request.getTenantId(),
                        request.getMigrationType().name(),
                        recordKeys,
                        request.getJobId());

            } catch (Exception e) {

                result.setStatus(RecordStatus.FAILED);

                String errorMessage =
                        getRootCauseMessage(e);

                result.setMessage(errorMessage);

                failed++;
                migrationRecordClaimService.release(
                        request.getTenantId(),
                        request.getMigrationType().name(),
                        recordKeys,
                        request.getJobId());

            } finally {

                result.setExecutionTime(
                        System.currentTimeMillis() - recordStart);
            }

            /*
             * ========================================================
             * SAVE RESULT
             * ========================================================
             */

            recordResults.add(result);

            saveMigrationDetail(
                    job,
                    request,
                    result,
                    result.getStatus().name(),
                    recordKeys);

            /*
             * ========================================================
             * UPDATE REALTIME PROGRESS
             * ========================================================
             */

            updateJobProgress(
                    job,
                    i + 1,
                    records.size(),
                    success,
                    failed,
                    skipped,
                    "Processing Work "
                            + (i + 1)
                            + " of "
                            + records.size());
        }

        /*
         * ============================================================
         * STEP 5 : FINAL JOB STATUS
         * ============================================================
         */
        
        if ("CANCELLED".equals(job.getStatus())) {

            job.setCompletedTime(LocalDateTime.now());
            migrationJobRepository.save(job);

            cancellationManager.remove(request.getJobId());

            return MigrationResult.builder()
                    .success(false)
                    .message("Migration cancelled by user.")
                    .totalRecords(records.size())
                    .successRecords(success)
                    .failedRecords(failed)
                    .skippedRecords(skipped)
                    .recordResults(recordResults)
                    .totalExecutionTime(
                            System.currentTimeMillis() - startTime)
                    .build();
        }

        job.setTotalRecords(records.size());
        job.setSuccessRecords(success);
        job.setFailedRecords(failed);
        job.setSkippedRecords(skipped);
        job.setProgressPercent(100);
        job.setCurrentRecord(records.size());

        /*
         * ============================================================
         * FINAL MESSAGE
         * ============================================================
         */

        String finalMessage;

        if (failed > 0) {

            finalMessage =
                    "Work migration completed with "
                            + failed
                            + " failed record(s).";

        } else if (skipped > 0) {

            finalMessage =
                    "Work migration completed successfully. "
                            + skipped
                            + " record(s) skipped as duplicate.";

        } else {

            finalMessage =
                    "Work migration completed successfully.";
        }

        job.setCurrentMessage(finalMessage);

        /*
         * ============================================================
         * FINAL STATUS
         * ============================================================
         */

        if (failed > 0) {

            job.setStatus("COMPLETED_WITH_ERRORS");

        } else {

            job.setStatus("COMPLETED");
        }

        job.setCompletedTime(LocalDateTime.now());

        migrationJobRepository.save(job);
        
        progressPublisher.publish(job);

        /*
         * ============================================================
         * TOTAL EXECUTION TIME
         * ============================================================
         */

        long totalExecutionTime =
                System.currentTimeMillis() - startTime;

        /*
         * ============================================================
         * RETURN FINAL RESULT
         * ============================================================
         */

        return MigrationResult.builder()
                .success(failed == 0)
                .message(finalMessage)
                .totalRecords(records.size())
                .successRecords(success)
                .failedRecords(failed)
                .skippedRecords(skipped)
                .recordResults(recordResults)
                .totalExecutionTime(totalExecutionTime)
                .build();
    }

    /**
     * Update realtime migration job progress.
     */
    private void updateJobProgress(
            MigrationJob job,
            int currentRecord,
            int totalRecords,
            int success,
            int failed,
            int skipped,
            String message) {

        job.setCurrentRecord(currentRecord);
        job.setTotalRecords(totalRecords);

        int progress = 0;

        if (totalRecords > 0) {

            progress = (int) (
                    ((double) currentRecord / totalRecords) * 100
            );
        }

        job.setProgressPercent(progress);
        job.setSuccessRecords(success);
        job.setFailedRecords(failed);
        job.setSkippedRecords(skipped);
        job.setCurrentMessage(message);

        migrationJobRepository.saveAndFlush(job);
        
        progressPublisher.publish(job);
    }

    /**
     * Save migration detail for one Work record.
     */
    private void saveMigrationDetail(
            MigrationJob job,
            MigrationRequest request,
            RecordResult result,
            String status,
            List<String> recordKey) {

        MigrationJobDetail detail =
                new MigrationJobDetail();

        detail.setJob(job);

        detail.setTenantId(
                request.getTenantId());

        detail.setModuleCode(
                request.getMigrationType().name());

        detail.setRecordNumber(
                result.getRecordNumber());

        detail.setStartRow(
                result.getStartRow());

        detail.setEndRow(
                result.getEndRow());

        detail.setStatus(status);

        detail.setMessage(
                result.getMessage());

        detail.setExecutionTime(
                result.getExecutionTime());

        detail.setRecordKey(recordKey);

        detail.setCreatedTime(
                LocalDateTime.now());

        migrationJobDetailRepository.save(detail);
    }

    /**
     * Get the actual root cause message.
     */
    private String getRootCauseMessage(
            Throwable exception) {

        Throwable root = exception;

        while (root.getCause() != null) {

            root = root.getCause();
        }

        if (root.getMessage() == null) {

            return root.getClass().getSimpleName();
        }

        return root.getMessage();
    }
    
    @Override
    protected List<String> getRecordKeys(Object record) {

        if (!(record instanceof WorkRecord work)) {

            throw new IllegalArgumentException(
                    "Invalid record type for WorkMigrationProcessor");
        }

        String workCode = normalize(work.getWorkCode());

        List<String> recordKeys = new ArrayList<>();

        // Work Code identity
        if (!workCode.isEmpty()) {

            recordKeys.add("WORK_CODE:" + workCode);
        }

        if (recordKeys.isEmpty()) {

            throw new IllegalArgumentException(
                    "Unable to generate unique record key for work. "
                    + "Work code is missing.");
        }

        return recordKeys;
    }
}