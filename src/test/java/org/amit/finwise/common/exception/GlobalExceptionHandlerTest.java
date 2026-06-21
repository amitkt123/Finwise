package org.amit.finwise.common.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.amit.finwise.auth.AppUserDetailsService;
import org.amit.finwise.auth.JwtService;
import org.amit.finwise.auth.SecurityConfig;
import org.amit.finwise.expense.controller.ExpenseController;
import org.amit.finwise.expense.dto.RecordExpenseRequest;
import org.amit.finwise.expense.model.Expense;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ExpenseController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
class GlobalExceptionHandlerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    // Security filter chain dependencies (mocked so SecurityConfig can initialize)
    @MockitoBean
    JwtService jwtService;

    @MockitoBean
    AppUserDetailsService appUserDetailsService;

    // Controller dependencies
    @MockitoBean
    org.amit.finwise.expense.service.ExpenseService expenseService;

    @MockitoBean
    org.amit.finwise.expense.service.ExpenseDocumentIngestionService expenseDocumentIngestionService;

    @MockitoBean
    org.amit.finwise.budget.service.BudgetMonitorService budgetMonitorService;

    @MockitoBean
    org.amit.finwise.expense.repository.ExpenseRepository expenseRepository;

    @Test
    @WithMockUser
    void whenNullAmount_returns400WithFieldErrors() throws Exception {
        RecordExpenseRequest bad = new RecordExpenseRequest(
                null, Expense.ExpenseCategory.FOOD_DINING, "lunch");

        mockMvc.perform(post("/api/finance/expense")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bad)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.fieldErrors.amount").exists());
    }

    @Test
    @WithMockUser
    void whenNegativeAmount_returns400() throws Exception {
        RecordExpenseRequest bad = new RecordExpenseRequest(
                BigDecimal.valueOf(-100), Expense.ExpenseCategory.FOOD_DINING, "lunch");

        mockMvc.perform(post("/api/finance/expense")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bad)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.fieldErrors.amount").exists());
    }

    @Test
    @WithMockUser
    void whenMalformedJson_returns400() throws Exception {
        mockMvc.perform(post("/api/finance/expense")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-valid-json}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Malformed Request Body"));
    }

    @Test
    void whenNoAuth_returns401() throws Exception {
        RecordExpenseRequest req = new RecordExpenseRequest(
                BigDecimal.TEN, Expense.ExpenseCategory.FOOD_DINING, "test");

        mockMvc.perform(post("/api/finance/expense")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }
}
