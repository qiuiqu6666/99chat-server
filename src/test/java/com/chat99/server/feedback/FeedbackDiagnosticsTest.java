package com.chat99.server.feedback;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.chat99.server.adminapi.*;
import com.chat99.server.group.GroupAvatarService;
import com.chat99.server.platform.PlatformProperties;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class FeedbackDiagnosticsTest {
    LocalContainerEntityManagerFactoryBean factory;
    UserFeedbackRepository feedback;
    FeedbackDiagnosticsRepository reports;
    TransactionTemplate tx;
    FeedbackService service;
    byte[] bytes = "Chat recovery report v1\n中文 diagnostic".getBytes(StandardCharsets.UTF_8);
    @BeforeEach void setup() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:feedback_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(ds);
        factory.setManagedTypes(PersistenceManagedTypes.of(UserFeedback.class.getName(), FeedbackDiagnostics.class.getName()));
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop")); factory.afterPropertiesSet();
        var repositories = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory.getObject()));
        feedback = repositories.getRepository(UserFeedbackRepository.class);
        reports = repositories.getRepository(FeedbackDiagnosticsRepository.class);
        tx = new TransactionTemplate(new JpaTransactionManager(factory.getObject()));
        service = new FeedbackService(feedback, mock(GroupAvatarService.class),
            new PlatformProperties(null,null,null,null,null,null,5,2000,null), reports);
    }
    @AfterEach void close() { factory.destroy(); }
    MockMultipartFile file(byte[] value) { return new MockMultipartFile("diagnostics", "report.txt", "text/plain", value); }
    FeedbackService.SubmitResult submit(boolean attach) {
        return tx.execute(status -> {
            try { return service.submit("123", FeedbackType.BUG, "scroll", "1", List.of(), attach ? file(bytes) : null, attach); }
            catch (Exception ex) { throw new RuntimeException(ex); }
        });
    }
    @Test void reportIsBoundToFeedbackAndDownloadedOnlyByAdmin() {
        var result = submit(true);
        assertThat(result.diagnosticsAttached()).isTrue();
        assertThat(reports.findById(result.id()).orElseThrow().getReport()).isEqualTo(bytes);
        var controller = new AdminFeedbackDiagnosticsController(reports);
        assertThatThrownBy(() -> controller.download(null, result.id())).isInstanceOf(AdminApiException.class);
        var user = UsernamePasswordAuthenticationToken.authenticated("123", "", List.of());
        assertThatThrownBy(() -> controller.download(user, result.id())).isInstanceOf(AdminApiException.class);
        var denied = UsernamePasswordAuthenticationToken.authenticated(new AdminPrincipal("limited", List.of()), "", List.of());
        assertThatThrownBy(() -> controller.download(denied, result.id())).isInstanceOf(AdminApiException.class);
        var admin = UsernamePasswordAuthenticationToken.authenticated(new AdminPrincipal("support", List.of("user.read")), "", List.of());
        var response = controller.download(admin, result.id());
        assertThat(response.getBody()).isEqualTo(bytes);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThatThrownBy(() -> controller.download(admin, 999)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void oldFeedbackWithoutReportStillWorks() {
        assertThat(submit(false).diagnosticsAttached()).isFalse();
        assertThat(feedback.count()).isEqualTo(1); assertThat(reports.count()).isZero();
    }
    @Test void attachmentFailureRollsBackFeedback() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            submit(true);
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(feedback.count()).isZero(); assertThat(reports.count()).isZero();
    }
    @Test void invalidOrUnconsentedFilesAreRejected() {
        assertThatThrownBy(() -> FeedbackDiagnosticsUpload.read(file(bytes), false)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> FeedbackDiagnosticsUpload.read(file(new byte[] {(byte)0xff}), true)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> FeedbackDiagnosticsUpload.read(file("not a report".getBytes()), true)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> FeedbackDiagnosticsUpload.read(file(new byte[2 * 1024 * 1024 + 1]), true)).isInstanceOf(ResponseStatusException.class);
    }
}
