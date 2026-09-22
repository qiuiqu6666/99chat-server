package com.chat99.server.lifepayment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.lifepayment.LifePaymentEnums.ServiceType;
import com.chat99.server.lifepayment.LifePaymentEnums.TaskAction;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class LifePaymentTaskServiceTest {

    private LifePaymentTaskRepository taskRepository;
    private LifePaymentTaskService service;

    @BeforeEach
    void setUp() {
        LifePaymentProperties properties = new LifePaymentProperties();
        taskRepository = mock(LifePaymentTaskRepository.class);
        service = new LifePaymentTaskService(
            properties,
            taskRepository,
            mock(LifePaymentOrderRepository.class),
            mock(LifePaymentMobileDetailRepository.class),
            mock(LifePaymentUtilityDetailRepository.class),
            mock(LifePaymentUtilityQueryRepository.class),
            mock(LifePaymentWorkerDeviceRepository.class),
            mock(LifePaymentAccountService.class),
            mock(LifePaymentLogService.class),
            mock(LifePaymentOrderUpdateNoticeService.class)
        );
    }

    @Test
    void shouldCreateActiveKeyForUtilityTask() {
        when(taskRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        LifePaymentTask task = service.enqueue(
            null,
            "query-1",
            ServiceType.electric,
            TaskAction.query,
            "none",
            Map.of("account_no", " 4205602001867 ")
        );

        assertThat(task.getAccountNo()).isEqualTo("4205602001867");
        assertThat(task.getActiveAccountKey()).isEqualTo("electric:4205602001867");
    }

    @Test
    void shouldRejectExistingActiveUtilityAccount() {
        when(taskRepository.existsByActiveAccountKey("electric:4205602001867")).thenReturn(true);

        assertThatThrownBy(() -> service.enqueue(
            null,
            "query-2",
            ServiceType.electric,
            TaskAction.query,
            "none",
            Map.of("account_no", "4205602001867")
        )).isInstanceOfSatisfying(ResponseStatusException.class, e -> {
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.getReason()).isEqualTo("account_task_already_active");
        });
        verify(taskRepository, never()).saveAndFlush(any());
    }

    @Test
    void shouldNotOccupyUtilityAccountSlotForMobileTask() {
        when(taskRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        LifePaymentTask task = service.enqueue(
            "order-1",
            null,
            ServiceType.mobile,
            TaskAction.recharge,
            "paid",
            Map.of("account_no", "13800138000")
        );

        assertThat(task.getAccountNo()).isEqualTo("13800138000");
        assertThat(task.getActiveAccountKey()).isNull();
        verify(taskRepository, never()).existsByActiveAccountKey(any());
    }
}
