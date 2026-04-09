package org.amit.finwise.expense.repository;

import org.amit.finwise.expense.model.Expense;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface ExpenseRepository extends JpaRepository<Expense, Long> {

    @Query("SELECT e FROM Expense e WHERE e.userId = :userId AND e.expenseDate BETWEEN :start AND :end ORDER BY e.expenseDate DESC")
    Page<Expense> findByUserInDateRange(@Param("userId") String userId,
                                        @Param("start") LocalDate start,
                                        @Param("end") LocalDate end,
                                        Pageable pageable);

    @Query("SELECT e FROM Expense e WHERE e.userId = :userId ORDER BY e.expenseDate DESC")
    Page<Expense> findByUserId(@Param("userId") String userId, Pageable pageable);

    @Query("SELECT e FROM Expense e WHERE e.userId = :userId AND e.source = :source ORDER BY e.expenseDate DESC")
    Page<Expense> findByUserIdAndSource(@Param("userId") String userId,
                                        @Param("source") Expense.ExpenseSource source,
                                        Pageable pageable);

    List<Expense> findByPdfUploadId(Long pdfUploadId);

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM Expense e WHERE e.userId = :userId AND YEAR(e.expenseDate) = :year AND MONTH(e.expenseDate) = :month")
    BigDecimal sumExpensesByMonth(@Param("userId") String userId,
                                  @Param("year") int year,
                                  @Param("month") int month);

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM Expense e WHERE e.userId = :userId AND YEAR(e.expenseDate) = :year AND MONTH(e.expenseDate) = :month AND e.category = :category")
    BigDecimal sumExpensesByMonthAndCategory(@Param("userId") String userId,
                                              @Param("year") int year,
                                              @Param("month") int month,
                                              @Param("category") Expense.ExpenseCategory category);

    @Query("SELECT COALESCE(AVG(e.amount), 0) FROM Expense e WHERE e.userId = :userId")
    BigDecimal averageExpense(@Param("userId") String userId);
}
