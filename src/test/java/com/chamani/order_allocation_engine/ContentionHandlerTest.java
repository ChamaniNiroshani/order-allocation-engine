package com.chamani.order_allocation_engine;

import com.chamani.order_allocation_engine.controller.OrderController;
import com.chamani.order_allocation_engine.exception.GlobalExceptionHandler;
import com.chamani.order_allocation_engine.exception.StockContentionException;
import com.chamani.order_allocation_engine.service.OrderAllocationFacade;
import com.chamani.order_allocation_engine.service.OrderQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
@Import(GlobalExceptionHandler.class)
class ContentionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderAllocationFacade facade;

    @MockitoBean
    private OrderQueryService orderQueryService;

    @Test
    void stockContention_returns503WithRetryAfter() throws Exception {
        when(facade.allocate(any(), any(), anyString()))
                .thenThrow(new StockContentionException(5, 1, new RuntimeException("conflict")));

        mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"" + UUID.randomUUID() + "\",\"quantityRequested\":1}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.status").value(503));
    }
}
