package com.chat99.server.im;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.chat99.server.common.AppSettingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;

class WalletCardTransportTest {
    HttpServer server;
    ImAdminClient client;
    AtomicReference<String> response = new AtomicReference<>();
    AtomicReference<Map> request = new AtomicReference<>();
    ObjectMapper json = new ObjectMapper();

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            request.set(json.readValue(exchange.getRequestBody(), Map.class));
            byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        var settings = mock(AppSettingService.class);
        when(settings.getInt(AppSettingService.IM_SDK_APP_ID, 0)).thenReturn(123);
        when(settings.get(AppSettingService.IM_KEY)).thenReturn(Optional.of("test-only-key"));
        var props = mock(ImProperties.class);
        when(props.restBaseUrl()).thenReturn("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        when(props.restAdminAccount()).thenReturn("admin");
        client = new ImAdminClient(settings, props, mock(ImGroupRoleCache.class));
    }
    @AfterEach void cleanup() { server.stop(0); }

    @Test void continuationSendsBothTimestampAndKey() {
        response.set("{\"ErrorCode\":0,\"MsgList\":[],\"Complete\":1}");
        client.walletCardHistory(false, "u1", "u2", 100, "150|key");
        assertThat(request.get()).containsEntry("MaxTime", 150).containsEntry("LastMsgKey", "key")
            .containsEntry("Operator_Account", "u1").containsEntry("Peer_Account", "u2");
    }
    @Test void malformedSuccessCannotBeReadAsEmptyHistory() {
        response.set("{\"MsgList\":[]}");
        assertThatThrownBy(() -> client.walletCardHistory(false, "u1", "u2", 100, null))
            .isInstanceOf(ImRestException.class);
        response.set("{\"ErrorCode\":0}");
        assertThatThrownBy(() -> client.walletCardHistory(true, "u1", "g1", 100, null))
            .isInstanceOf(ImRestException.class);
    }
    @Test void groupHistoryRequestsDocumentedPageSizeAndCursor() {
        response.set("{\"ErrorCode\":0,\"RspMsgList\":[]}");
        client.walletCardHistory(true, "u1", "g1", 100, "88");
        assertThat(request.get()).containsEntry("ReqMsgNumber", 20).containsEntry("ReqMsgSeq", 88);
    }
}
