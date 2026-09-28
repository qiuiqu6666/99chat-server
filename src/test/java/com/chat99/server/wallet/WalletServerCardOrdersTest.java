package com.chat99.server.wallet;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.chat99.server.user.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

class WalletServerCardOrdersTest {
    LocalContainerEntityManagerFactoryBean factory;
    EntityManager em;
    JpaTransactionManager manager;
    WalletCardOutboxRepository repo;
    WalletTransferService transfers;
    RedPacketService packets;
    WalletTransferRepository transferRepo;
    WalletRedPacketRepository packetRepo;
    EntityManager senderLock;
    WalletServerCardOrders orders;

    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        var ds = new JdbcDataSource(); ds.setURL("jdbc:h2:mem:orders_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        factory = new LocalContainerEntityManagerFactoryBean(); factory.setDataSource(ds);
        factory.setManagedTypes(PersistenceManagedTypes.of(WalletCardOutbox.class.getName(), WalletTransfer.class.getName()));
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop")); factory.afterPropertiesSet();
        em = SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
        manager = new JpaTransactionManager(factory.getObject());
        repo = new JpaRepositoryFactory(em).getRepository(WalletCardOutboxRepository.class);
        transfers = mock(WalletTransferService.class); packets = mock(RedPacketService.class);
        transferRepo = mock(WalletTransferRepository.class); packetRepo = mock(WalletRedPacketRepository.class);
        senderLock = mock(EntityManager.class);
        TypedQuery<User> query = mock(TypedQuery.class);
        when(senderLock.createQuery(anyString(), eq(User.class))).thenReturn(query);
        when(query.setParameter(eq("id"), any())).thenReturn(query);
        when(query.setLockMode(LockModeType.PESSIMISTIC_WRITE)).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(new User()));
        orders = proxied(repo);
    }
    WalletServerCardOrders proxied(WalletCardOutboxRepository outbox) {
        var profiles = mock(WalletPartyNameResolver.class);
        when(profiles.resolveProfiles(any())).thenReturn(Map.of());
        var target = new WalletServerCardOrders(transfers, packets, transferRepo, packetRepo, outbox, profiles, senderLock, new ObjectMapper());
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (WalletServerCardOrders) proxy.getProxy();
    }
    @AfterEach void cleanup() { factory.destroy(); }
    WalletController.TransferRequest request(String to, long amount) {
        return new WalletController.TransferRequest(to, WalletCurrency.PLATFORM, amount, "never-persist-this-pin", "memo", "transfer_test");
    }
    void payment() {
        when(transfers.transfer(anyString(), anyString(), any(), anyLong(), anyString(), anyString(), anyString()))
            .thenAnswer(call -> {
                var t = new WalletTransfer(); t.setFromUserId("u1"); t.setToUserId("u2");
                t.setCurrency(WalletCurrency.PLATFORM); t.setAmount(100); t.setMemo("memo");
                t.setStatus(WalletTransferStatus.COMPLETED); t.setClientOrderId("transfer_test");
                em.persist(t); em.flush(); return t;
            });
    }
    @Test void duplicateRequestReturnsSameCardWithoutSecondPayment() {
        payment();
        var first = orders.transfer("u1", request("u2", 100));
        var repeated = orders.transfer("u1", request("u2", 100));
        assertThat(first.get("orderId")).isEqualTo(repeated.get("orderId"));
        assertThat(first).containsEntry("serverManagedCard", true).containsEntry("currency", "99").containsEntry("amount", 100);
        assertThat(repo.count()).isEqualTo(1);
        assertThat(repo.findAll().get(0).getPayload()).doesNotContain("never-persist-this-pin");
        verify(transfers, times(1)).transfer(anyString(), anyString(), any(), anyLong(), anyString(), anyString(), anyString());
        assertThatThrownBy(() -> orders.transfer("u1", request("other", 100))).hasMessageContaining("CLIENT_ORDER_ID_CONFLICT");
    }
    @Test void enqueueFailureRollsBackPaymentServiceDatabaseWrites() {
        payment();
        var failing = mock(WalletCardOutboxRepository.class, org.mockito.AdditionalAnswers.delegatesTo(repo));
        doThrow(new IllegalStateException("outbox insert unavailable")).when(failing).saveAndFlush(any());
        assertThatThrownBy(() -> proxied(failing).transfer("u1", request("u2", 100))).isInstanceOf(IllegalStateException.class);
        Long count = new TransactionTemplate(manager).execute(s -> em.createQuery("select count(t) from WalletTransfer t", Long.class).getSingleResult());
        assertThat(count).isZero(); assertThat(repo.count()).isZero();
    }
    @Test void legacyOrdersAreNotBackfilledOrSentTwice() {
        when(transferRepo.findByFromUserIdAndClientOrderId("u1", "transfer_test")).thenReturn(Optional.of(new WalletTransfer()));
        assertThatThrownBy(() -> orders.transfer("u1", request("u2", 100))).hasMessageContaining("LEGACY_CARD_ORDER");
        verifyNoInteractions(transfers); assertThat(repo.count()).isZero();
    }
    @Test void orderLookupIsScopedToAuthenticatedSender() {
        payment(); orders.transfer("u1", request("u2", 100));
        assertThat(orders.status("u1", "transfer_test")).containsEntry("serverManagedCard", true);
        assertThatThrownBy(() -> orders.status("other", "transfer_test")).hasMessageContaining("ORDER_NOT_FOUND");
    }
    @ParameterizedTest @EnumSource(RedPacketType.class)
    void allPacketKindsUseOrderDataAndReplayOnlyOnce(RedPacketType type) {
        boolean group = type != RedPacketType.NORMAL_C2C;
        var p = new WalletRedPacket(); p.setId(7L); p.setPublicId("red_packet_test"); p.setSenderUserId("u1");
        p.setPacketType(type); p.setConversationType(group ? "GROUP" : "C2C"); p.setGroupId(group ? "g1" : null);
        p.setExclusiveUserId("u2"); p.setCurrency(WalletCurrency.PLATFORM); p.setTotalAmount(100);
        p.setPacketCount(1); p.setGreeting("hello"); p.setStatus(RedPacketStatus.COMPLETED);
        var req = new WalletController.RedPacketSendRequest(type, group ? "GROUP" : "C2C", group ? "g1" : null,
            "u2", WalletCurrency.PLATFORM, 100L, null, 1, "hello", "secret", "red_packet_test");
        when(packets.send(eq("u1"), eq(type), anyString(), nullable(String.class), eq("u2"), any(), eq(100L),
            isNull(), eq(1), eq("hello"), eq("secret"), eq("red_packet_test"))).thenReturn(p);
        var result = orders.redPacket("u1", req); orders.redPacket("u1", req);
        assertThat(result).containsEntry("packetType", type.name()).containsEntry("receiverId", "u2")
            .containsEntry("type", type == RedPacketType.GROUP_TRANSFER ? "wallet_group_transfer" : "wallet_red_packet");
        assertThat(repo.findAll().get(0).getTargetId()).isEqualTo(group ? "g1" : "u2");
        verify(packets, times(1)).send(eq("u1"), eq(type), anyString(), nullable(String.class), eq("u2"), any(), eq(100L),
            isNull(), eq(1), eq("hello"), eq("secret"), eq("red_packet_test"));
    }
}
