package com.aishorts.engine.claude;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Claude, con razonamiento extendido, puede anteponer un bloque
 * "type":"thinking" al bloque "type":"text" real dentro de 'content'. Este
 * test cubre que sendMessage recorra todo el array y use el primer bloque de
 * TEXTO, no asumir que content[0] ya es texto (mismo servidor HTTP local
 * fake que usa HiggsfieldRestClientTest).
 */
class ClaudeMessagesClientTest {

    private static HttpServer server;
    private static int port;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", ClaudeMessagesClientTest::route);
        server.setExecutor(null);
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendMessage_extractsTheTextBlock_whenAThinkingBlockComesFirst() {
        ClaudeMessagesClient client = new ClaudeMessagesClient(config(), new ObjectMapper());

        String text = client.sendMessage("system prompt", "user message");

        assertThat(text).isEqualTo("la respuesta real después de pensar");
    }

    private static ClaudeConfig config() {
        return new ClaudeConfig("test-key", "test-model", "http://127.0.0.1:" + port, 16000);
    }

    private static void route(HttpExchange exchange) throws IOException {
        try {
            String body = """
                    {"content":[\
                    {"type":"thinking","thinking":"dejame pensar esto con cuidado..."},\
                    {"type":"text","text":"la respuesta real después de pensar"}\
                    ]}""";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } finally {
            exchange.close();
        }
    }
}
