package io.hoony.reconciliation;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;

@Configuration
class ReconciliationJobConfiguration {

    @Bean
    Job reconciliationJob(
            JobRepository jobRepository,
            Step pgSnapshotStep,
            Step paymentReconciliationStep,
            Step reconciliationSummaryStep
    ) {
        return new JobBuilder("paymentReconciliationJob", jobRepository)
                .start(pgSnapshotStep)
                .next(paymentReconciliationStep)
                .next(reconciliationSummaryStep)
                .build();
    }

    @Bean
    Step pgSnapshotStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            PgSnapshotTasklet tasklet
    ) {
        return new StepBuilder("pgSnapshotStep", jobRepository)
                .tasklet(tasklet, transactionManager)
                .build();
    }

    @Bean
    Step paymentReconciliationStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            ReconciliationProperties properties,
            ItemStreamReader<ReconciliationModel.PaymentRow> reader,
            ItemProcessor<ReconciliationModel.PaymentRow, ReconciliationModel.Result> processor,
            ItemWriter<ReconciliationModel.Result> writer
    ) {
        return new StepBuilder("paymentReconciliationStep", jobRepository)
                .<ReconciliationModel.PaymentRow, ReconciliationModel.Result>chunk(
                        properties.chunkSize(),
                        transactionManager
                )
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .build();
    }

    @Bean
    Step reconciliationSummaryStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            ReconciliationSummaryTasklet tasklet
    ) {
        return new StepBuilder("reconciliationSummaryStep", jobRepository)
                .tasklet(tasklet, transactionManager)
                .build();
    }

    @Bean
    @StepScope
    PgSnapshotTasklet pgSnapshotTasklet(
            JdbcTemplate jdbc,
            RestClient.Builder builder,
            ReconciliationProperties properties,
            @Value("#{jobParameters['runKey']}") String runKey,
            @Value("#{jobParameters['pgFrom']}") String pgFrom
    ) {
        return new PgSnapshotTasklet(jdbc, builder, properties, runKey, Instant.parse(pgFrom));
    }

    @Bean
    @StepScope
    ItemStreamReader<ReconciliationModel.PaymentRow> reconciliationPaymentReader(
            JdbcTemplate jdbc,
            ReconciliationProperties properties,
            @Value("#{jobParameters['runKey']}") String runKey
    ) {
        return new ReconciliationPaymentReader(jdbc, runKey, properties.pageSize());
    }

    @Bean
    @StepScope
    ItemProcessor<ReconciliationModel.PaymentRow, ReconciliationModel.Result> reconciliationProcessor(
            ReconciliationFailureGate failureGate,
            @Value("#{jobParameters['runKey']}") String runKey,
            @Value("#{jobParameters['failAfter']}") Long failAfter
    ) {
        return new ReconciliationProcessor(runKey, failAfter == null ? 0 : failAfter, failureGate);
    }

    @Bean
    @StepScope
    ItemWriter<ReconciliationModel.Result> reconciliationWriter(
            JdbcTemplate jdbc,
            Clock clock,
            @Value("#{jobParameters['runKey']}") String runKey
    ) {
        return new ReconciliationWriter(jdbc, runKey, clock);
    }

    @Bean
    @StepScope
    ReconciliationSummaryTasklet reconciliationSummaryTasklet(
            JdbcTemplate jdbc,
            MeterRegistry metrics,
            Clock clock,
            @Value("#{jobParameters['runKey']}") String runKey
    ) {
        return new ReconciliationSummaryTasklet(jdbc, metrics, clock, runKey);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}