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

class WalletCardStateSyncTest {
    LocalContainerEntityManagerFactoryBean factory;
    WalletCardOutboxRepository outbox;
    WalletRedPacketRepository packets;
    WalletRedPacketClaimRepository claims = mock(WalletRedPacketClaimRepository.class);
    TransactionTemplate tx;
    WalletCardStateSync sync;
    ImAdminClient im;
    final ObjectMapper json = new ObjectMapper();
    long packetId;

    @BeforeEach void setup() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:state_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        factory = new LocalContainerEntityManagerFactoryBean(); factory.setDataSource(ds);
        factory.setManagedTypes(PersistenceManagedTypes.of(WalletCardOutbox.class.getName(), WalletRedPacket.class.getName()));
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop")); factory.afterPropertiesSet();
        EntityManager em = SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
        var repositories = new JpaRepositoryFactory(em);
        outbox = repositories.getRepository(WalletCardOutboxRepository.class);
        packets = repositories.getRepository(WalletRedPacketRepository.class);
        var manager = new JpaTransactionManager(factory.getObject()); tx = new TransactionTemplate(manager);
        im = mock(ImAdminClient.class); sync = new WalletCardStateSync(outbox, packets, claims, im, json, manager);
        tx.executeWithoutResult(s -> {
            var p = new WalletRedPacket(); p.setSenderUserId("u1"); p.setPublicId(UUID.randomUUID().toString());
            p.setPacketType(RedPacketType.LUCKY_GROUP); p.setConversationType("GROUP"); p.setGroupId("g1");
            p.setCurrency(WalletCurrency.PLATFORM); p.setTotalAmount(100); p.setPacketCount(2);
            p.setRemainingAmount(100); p.setRemainingCount(2); p.setStatus(RedPacketStatus.ACTIVE);
            packetId = packets.saveAndFlush(p).getId();
            var r = new WalletCardOutbox(); r.setId(id()); r.setSenderId("u1"); r.setClientId("cid");
            r.setTargetId("g1"); r.setGroupMessage(true); r.setRequestHash("h"); r.setState("SENT");
            r.setMessageSeq(88L); r.setPayload("{\"type\":\"wallet_red_packet\",\"amount\":100}");
            outbox.saveAndFlush(r);
        });
    }
    @AfterEach void cleanup() { factory.destroy(); }
    String id() { return "rp:" + packetId; }
    WalletCardOutbox read() { return outbox.findById(id()).orElseThrow(); }
    void due() { tx.executeWithoutResult(s -> outbox.lock(id()).orElseThrow().setNextSyncAt(Instant.now().minusSeconds(1))); }
    void claim(int remaining, RedPacketStatus state) { tx.executeWithoutResult(s -> {
        var p = packets.findById(packetId).orElseThrow(); p.setRemainingCount(remaining);
        p.setRemainingAmount(remaining * 50L); p.setStatus(state);
    }); }

    @Test void partialClaimAndFullyClaimedReplaceSameOriginalCard() throws Exception {
        sync.sync(id()); claim(1, RedPacketStatus.ACTIVE); due(); sync.sync(id());
        assertThat(json.readTree(read().getSyncedPayload()).get("claimedCount").asInt()).isEqualTo(1);
        claim(0, RedPacketStatus.COMPLETED); due(); sync.sync(id());
        assertThat(json.readTree(read().getSyncedPayload()).get("status").asText()).isEqualTo("empty");
        verify(im, times(3)).updateWalletCard(eq(true), eq("u1"), eq("g1"), isNull(), eq(88L), anyString());
        verify(im, never()).sendWalletCard(anyBoolean(), anyString(), anyString(), anyInt(), anyLong(), anyString());
    }
    @Test void failedUpdateSurvivesRestartAndRetriesLatestState() throws Exception {
        doThrow(new ImRestException("timeout", 20004)).doNothing().when(im)
            .updateWalletCard(anyBoolean(), anyString(), anyString(), any(), any(), anyString());
        sync.sync(id()); assertThat(read().getSyncedPayload()).isNull();
        assertThat(read().getSyncError()).isEqualTo("IM_20004");
        claim(0, RedPacketStatus.COMPLETED); due();
        new WalletCardStateSync(outbox, packets, claims, im, json, tx.getTransactionManager()).run();
        assertThat(json.readTree(read().getSyncedPayload()).get("status").asText()).isEqualTo("empty");
        assertThat(read().getSyncError()).isNull();
    }
    @Test void missingLocatorAndUnsentCardNeverCreateReplacementMessages() {
        tx.executeWithoutResult(s -> outbox.lock(id()).orElseThrow().setMessageSeq(null));
        sync.sync(id()); assertThat(read().getSyncError()).isEqualTo("MESSAGE_LOCATOR_MISSING");
        tx.executeWithoutResult(s -> { var r = outbox.lock(id()).orElseThrow(); r.setState("PENDING"); r.setMessageSeq(88L); });
        due(); sync.run(); verifyNoInteractions(im);
    }
    @Test void expiryIsSynchronizedAndTerminalTaskRetiresAfterRepair() throws Exception {
        when(claims.countByPacketId(packetId)).thenReturn(1L);
        claim(0, RedPacketStatus.REFUNDED); sync.sync(id());
        assertThat(json.readTree(read().getSyncedPayload()).get("status").asText()).isEqualTo("refunded");
        assertThat(json.readTree(read().getSyncedPayload()).get("claimedCount").asLong()).isEqualTo(1L);
        tx.executeWithoutResult(s -> outbox.lock(id()).orElseThrow().setLastSyncAt(Instant.now().minusSeconds(301)));
        due(); sync.sync(id()); assertThat(read().getNextSyncAt()).isNull();
        sync.run(); verify(im, times(2)).updateWalletCard(anyBoolean(), anyString(), anyString(), any(), any(), anyString());
    }
    @Test void unchangedActiveStateDoesNotSpamIm() {
        sync.sync(id()); due(); sync.sync(id());
        verify(im, times(1)).updateWalletCard(anyBoolean(), anyString(), anyString(), any(), any(), anyString());
    }
    @Test void rolledBackClaimIsNeverPublished() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            var p = packets.findById(packetId).orElseThrow(); p.setRemainingCount(0); p.setStatus(RedPacketStatus.COMPLETED);
            throw new IllegalStateException("credit failed");
        })).isInstanceOf(IllegalStateException.class);
        sync.sync(id()); assertThat(read().getSyncedPayload()).contains("\"status\":\"active\"");
    }
    @Test void concurrentInstancesCannotOvertakeAnInFlightUpdate() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); assertThat(release.await(3, TimeUnit.SECONDS)).isTrue(); return null; })
            .when(im).updateWalletCard(anyBoolean(), anyString(), anyString(), any(), any(), anyString());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> sync.sync(id()));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> sync.sync(id()));
            release.countDown(); first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
            verify(im, times(1)).updateWalletCard(anyBoolean(), anyString(), anyString(), any(), any(), anyString());
        } finally { release.countDown(); pool.shutdownNow(); }
    }
}
