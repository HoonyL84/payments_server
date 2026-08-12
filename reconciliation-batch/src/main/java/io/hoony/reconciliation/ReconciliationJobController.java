package io.hoony.reconciliation;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/internal/v1/reconciliation")
class ReconciliationJobController {

    private final JobLauncher launcher;
    private final Job reconciliationJob;
    private final JdbcTemplate jdbc;

    ReconciliationJobController(JobLauncher launcher, Job reconciliationJob, JdbcTemplate jdbc) {
        this.launcher = launcher;
        this.reconciliationJob = reconciliationJob;
        this.jdbc = jdbc;
    }

    @PostMapping("/jobs")
    ResponseEntity<Map<String, Object>> run(
            @RequestParam String runKey,
            @RequestParam(defaultValue = "0") long failAfter,
            @RequestParam(defaultValue = "1970-01-01T00:00:00Z") Instant pgFrom
    ) throws Exception {
        try {
            JobExecution execution = launcher.run(reconciliationJob, new JobParametersBuilder()
                    .addString("runKey", runKey)
                    .addLong("failAfter", failAfter)
                    .addString("pgFrom", pgFrom.toString())
                    .toJobParameters());
            return ResponseEntity.ok(Map.of(
                    "executionId", execution.getId(),
                    "status", execution.getStatus().name(),
                    "exitCode", execution.getExitStatus().getExitCode()
            ));
        } catch (JobInstanceAlreadyCompleteException exception) {
            return ResponseEntity.ok(Map.of("status", "COMPLETED", "reused", true));
        }
    }

    @GetMapping("/runs/{runKey}")
    Map<String, Object> report(@PathVariable String runKey) {
        List<Map<String, Object>> summaries = jdbc.queryForList("""
                SELECT job_execution_id, status, read_count, write_count,
                       auto_correct_count, requery_count, manual_review_count,
                       duration_millis
                  FROM reconciliation_run_summaries
                 WHERE run_key = ?
                 ORDER BY job_execution_id DESC
                """, runKey);
        List<Map<String, Object>> cases = jdbc.queryForList("""
                SELECT classification, COUNT(*) AS count
                  FROM reconciliation_cases
                 WHERE run_key = ?
                 GROUP BY classification
                 ORDER BY classification
                """, runKey);
        Long corrections = jdbc.queryForObject("""
                SELECT COUNT(*) FROM reconciliation_corrections WHERE source_run_key = ?
                """, Long.class, runKey);
        return Map.of(
                "runKey", runKey,
                "summaries", summaries,
                "cases", cases,
                "corrections", corrections == null ? 0 : corrections
        );
    }
}