package org.amit.finwise.marketdata.repository;

import org.amit.finwise.marketdata.model.MfNav;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MfNavRepository extends JpaRepository<MfNav, Long> {

    Optional<MfNav> findTopByAmfiCodeOrderByNavDateDesc(String amfiCode);

    Optional<MfNav> findTopByAmfiCodeAndNavDateLessThanEqualOrderByNavDateDesc(
            String amfiCode, LocalDate navDate);

    List<MfNav> findByAmfiCodeAndNavDateBetweenOrderByNavDate(
            String amfiCode, LocalDate from, LocalDate to);

    long countByAmfiCode(String amfiCode);
}
