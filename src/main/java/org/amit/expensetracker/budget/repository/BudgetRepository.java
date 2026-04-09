package org.amit.expensetracker.budget.repository;

import org.amit.expensetracker.budget.model.Budget;
import org.amit.expensetracker.expense.model.Expense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BudgetRepository extends JpaRepository<Budget, Long> {

    Optional<Budget> findByUserIdAndBudgetMonthAndCategory(String userId, String budgetMonth, Expense.ExpenseCategory category);

    List<Budget> findByUserIdAndBudgetMonth(String userId, String budgetMonth);

    @Query("SELECT b FROM Budget b WHERE b.userId = :userId AND b.budgetMonth = :month AND b.status IN ('WARNING', 'OVER_BUDGET')")
    List<Budget> findBudgetsNeedingAlerts(@Param("userId") String userId, @Param("month") String month);
}
