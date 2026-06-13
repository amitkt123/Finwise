package org.amit.finwise.marketdata.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Ledger of every ingestion attempt: one row per (job, business date).
 * Drives idempotent re-runs (skip SUCCESS/NO_DATA) and gap repair
 * (re-attempt FAILED / missing weekdays).
 */
@Entity
@Table(name = "md_ingestion_run",
        indexes = {@Index(name = "idx_md_ingestion_job_date", columnList = "job_name, business_date DESC")},
        uniqueConstraints = {@UniqueConstraint(name = "uq_md_ingestion_job_date",
                columnNames = {"job_name", "business_date"})})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IngestionRun {

    public enum Status { SUCCESS, NO_DATA, FAILED }

    public static final String JOB_BHAVCOPY = "cm_bhavcopy";
    public static final String JOB_INDEX_CLOSE = "index_close_all";
    public static final String JOB_DELIVERY = "sec_bhavdata_full";
    public static final String JOB_CORPORATE_ACTION = "corporate_action";
    public static final String JOB_EVENT_CALENDAR = "event_calendar";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_name", nullable = false, length = 40)
    private String jobName;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status;

    private Integer rowCount;

    @Column(length = 500)
    private String message;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;
}
