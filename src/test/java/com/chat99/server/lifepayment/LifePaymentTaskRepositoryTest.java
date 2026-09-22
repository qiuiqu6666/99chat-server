package com.chat99.server.lifepayment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chat99.server.lifepayment.LifePaymentEnums.ServiceType;
import com.chat99.server.lifepayment.LifePaymentEnums.TaskAction;
import com.chat99.server.lifepayment.LifePaymentEnums.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ContextConfiguration;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@ContextConfiguration(classes = LifePaymentTaskRepositoryTest.JpaTestConfiguration.class)
class LifePaymentTaskRepositoryTest {

    @Autowired
    private LifePaymentTaskRepository repository;

    @Test
    void shouldRejectSecondActiveTaskForSameUtilityAccount() {
        repository.saveAndFlush(task("task-query-1", "electric:4205602001867"));

        assertThatThrownBy(() ->
            repository.saveAndFlush(task("task-query-2", "electric:4205602001867")))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldReleaseAccountAfterTaskReachesTerminalStatus() {
        LifePaymentTask completed = repository.saveAndFlush(
            task("task-query-1", "electric:4205602001867"));
        completed.setStatus(TaskStatus.success);
        repository.saveAndFlush(completed);

        LifePaymentTask next = repository.saveAndFlush(
            task("task-query-2", "electric:4205602001867"));

        assertThat(next.getId()).isNotNull();
        assertThat(next.getActiveAccountKey()).isEqualTo("electric:4205602001867");
    }

    private static LifePaymentTask task(String taskNo, String activeAccountKey) {
        LifePaymentTask task = new LifePaymentTask();
        task.setTaskNo(taskNo);
        task.setQueryNo("query-" + taskNo);
        task.setAccountNo("4205602001867");
        task.setActiveAccountKey(activeAccountKey);
        task.setServiceType(ServiceType.electric);
        task.setTaskAction(TaskAction.query);
        task.setPaymentStatus("none");
        task.setPayloadJson("{}");
        task.setStatus(TaskStatus.ready);
        task.setAttemptCount(0);
        task.setMaxAttempts(3);
        return task;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = LifePaymentTask.class)
    @EnableJpaRepositories(
        basePackageClasses = LifePaymentTaskRepository.class,
        includeFilters = @ComponentScan.Filter(
            type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE,
            classes = LifePaymentTaskRepository.class
        )
    )
    static class JpaTestConfiguration {
    }
}
