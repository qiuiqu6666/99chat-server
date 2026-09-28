package com.chat99.server.wallet;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImRestException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

class WalletCardDeliveryTest {
    LocalContainerEntityManagerFactoryBean factory;
    WalletCardOutboxRepository repo;
    TransactionTemplate tx;
    WalletCardDelivery delivery;
    ImAdminClient im;
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach void setup() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:card_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=2000");
        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(ds);
        factory.setManagedTypes(PersistenceManagedTypes.of(WalletCardOutbox.class.getName()));
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
        factory.afterPropertiesSet();
        EntityManager em = SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
        repo = new JpaRepositoryFactory(em).getRepository(WalletCardOutboxRepository.class);
        var manager = new JpaTransactionManager(factory.getObject());
        tx = new TransactionTemplate(manager);
        im = mock(ImAdminClient.class);
        delivery = new WalletCardDelivery(repo, im, json, manager);
    }
    @AfterEach void cleanup() { factory.destroy(); }

    WalletCardOutbox row(boolean group) {
        var r = new WalletCardOutbox();
        r.setId("rp:1"); r.setClientId("red_packet_a"); r.setSenderId("u1");
        r.setTargetId(group ? "g1" : "u2"); r.setGroupMessage(group); r.setRequestHash("hash"); r.setImRandom(12345);
        r.setPayload("{\"businessID\":\"wallet_order\",\"type\":\"wallet_red_packet\",\"orderId\":\"1\"}");
        return r;
    }
    void save(WalletCardOutbox row) { tx.executeWithoutResult(s -> repo.saveAndFlush(row)); }
    WalletCardOutbox read() { return repo.findById("rp:1").orElseThrow(); }
    void due() { tx.executeWithoutResult(s -> repo.lock("rp:1").orElseThrow().setNextAttemptAt(Instant.now().minusSeconds(1))); }
    String callback(boolean group, String target, int random) throws Exception {
        return json.writeValueAsString(Map.of("From_Account", "u1", group ? "GroupId" : "To_Account", target,
            group ? "Random" : "MsgRandom", random, "MsgKey", "receipt-key", "MsgSeq", 5L, "SendMsgResult", 0,
            "MsgBody", List.of(Map.of("MsgType", "TIMCustomElem", "MsgContent", Map.of("Data", row(group).getPayload())))));
    }

    @Test void rolledBackTransactionCannotLeaveSendableTask() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            repo.saveAndFlush(row(true));
            throw new IllegalStateException("money transaction aborted");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(repo.count()).isZero();
        delivery.run(); verifyNoInteractions(im);
    }
    @Test void uniqueClientIdProtectsAgainstDuplicateTasks() {
        save(row(true));
        var second = row(true); second.setId("rp:2");
        assertThatThrownBy(() -> save(second)).isInstanceOf(RuntimeException.class);
        assertThat(repo.count()).isEqualTo(1);
    }
    @Test void successfulDeliveryIsNeverSentAgain() {
        save(row(true));
        when(im.sendWalletCard(anyBoolean(), anyString(), anyString(), anyInt(), anyLong(), anyString()))
            .thenReturn(new ImAdminClient.NativeVideoSendResult(true, "key", 5L, 0));
        delivery.deliver("rp:1"); delivery.deliver("rp:1");
        assertThat(read().getState()).isEqualTo("SENT");
        assertThat(read().getMessageSeq()).isEqualTo(5L);
        verify(im, times(1)).sendWalletCard(anyBoolean(), anyString(), anyString(), anyInt(), anyLong(), anyString());
    }
    @Test void groupTimeoutReconcilesOriginalMessageInsteadOfResending() throws Exception {
        save(row(true));
        when(im.sendWalletCard(anyBoolean(), anyString(), anyString(), anyInt(), anyLong(), anyString()))
            .thenThrow(new ImRestException("timeout", 0))
            .thenReturn(new ImAdminClient.NativeVideoSendResult(true, "key", 1L, 0));
        delivery.deliver("rp:1");
        Map<String, Object> message = json.readValue(callback(true, "g1", 12345), Map.class);
        message.put("MsgRandom", 12345); message.put("MsgSeq", 1L);
        when(im.walletCardHistory(anyBoolean(), anyString(), anyString(), anyLong(), any()))
            .thenReturn(Map.of("RspMsgList", List.of(message)));
        var first = read(); due(); delivery.deliver("rp:1");
        verify(im, times(1)).sendWalletCard(true, "u1", "g1", 12345,
            first.getFirstAttemptAt().getEpochSecond(), first.getPayload());
        assertThat(read().getState()).isEqualTo("SENT");
    }
    @Test void expiredGroupDedupWindowDoesNotBlindlyResend() {
        var r = row(true); r.setState("SENDING"); r.setFirstAttemptAt(Instant.now().minusSeconds(300)); save(r);
        delivery.deliver(r.getId());
        assertThat(read().getState()).isEqualTo("RECONCILING");
        verify(im, never()).sendWalletCard(anyBoolean(), anyString(), anyString(), anyInt(), anyLong(), anyString());
    }
    @Test void c2cTimeoutWaitsForReceiptWithoutCreatingDuplicate() throws Exception {
        save(row(false));
        when(im.sendWalletCard(anyBoolean(), anyString(), anyString(), anyInt(), anyLong(), anyString()))
            .thenThrow(new ImRestException("timeout", 0));
        delivery.deliver("rp:1"); due(); delivery.deliver("rp:1");
        assertThat(read().getState()).isEqualTo("RECONCILING");
        delivery.receipt("C2C.CallbackAfterSendMsg", callback(false, "u2", 12345));
        assertThat(read().getState()).isEqualTo("SENT");
        verify(im, times(1)).sendWalletCard(anyBoolean(), anyString(), anyString(), anyInt(), anyLong(), anyString());
    }
    @Test void wrongRecipientOrRandomCannotAcknowledgeDelivery() throws Exception {
        var r = row(true); r.setFirstAttemptAt(Instant.now()); save(r);
        delivery.receipt("Group.CallbackAfterSendMsg", callback(true, "wrong", 12345));
        delivery.receipt("Group.CallbackAfterSendMsg", callback(true, "g1", 999));
        assertThat(read().getState()).isEqualTo("PENDING");
    }
    @Test void concurrentWorkersOnlyOneSendsAndReceiptWinsOverTimeout() throws Exception {
        save(row(true));
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(im.sendWalletCard(anyBoolean(), anyString(), anyString(), anyInt(), anyLong(), anyString()))
            .thenAnswer(call -> { entered.countDown(); release.await(5, TimeUnit.SECONDS); throw new ImRestException("timeout", 0); });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> delivery.deliver("rp:1"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            delivery.deliver("rp:1");
            delivery.receipt("Group.CallbackAfterSendMsg", callback(true, "g1", 12345));
            release.countDown(); first.get(5, TimeUnit.SECONDS);
            assertThat(read().getState()).isEqualTo("SENT");
            verify(im, times(1)).sendWalletCard(anyBoolean(), anyString(), anyString(), anyInt(), anyLong(), anyString());
        } finally { release.countDown(); executor.shutdownNow(); }
    }
}
