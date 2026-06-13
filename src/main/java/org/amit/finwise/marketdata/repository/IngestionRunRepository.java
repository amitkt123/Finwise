package org.amit.finwise.marketdata.repository;

import org.amit.finwise.marketdata.model.IngestionRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface IngestionRunRepository extends JpaRepository<IngestionRun, Long> {

    Optional<IngestionRun> findByJobNameAndBusinessDate(String jobName, LocalDate businessDate);

    List<IngestionRun> findByJobNameAndBusinessDateBetween(
            String jobName, LocalDate from, LocalDate to);

    long countByJobNameAndStatus(String jobName, IngestionRun.Status status);

    List<IngestionRun> findByJobName(String jobName);

    List<IngestionRun> findByJobNameAndStatusAndRowCountLessThan(
            String jobName, IngestionRun.Status status, int rowCount);

    List<IngestionRun> findByJobNameAndStatus(String jobName, IngestionRun.Status status);
}
