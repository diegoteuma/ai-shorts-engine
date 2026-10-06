package com.aishorts.engine.claude;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reconstrucción de la respuesta a partir de los eventos SSE, sin red. */
class ClaudeStreamAccumulatorTest {

    private final ClaudeStreamAccumulator accumulator = new ClaudeStreamAccumulator(new ObjectMapper());

    private void feed(String data) {
        accumulator.accept(null, data);
    }

    private void start() {
        feed("{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"content\":[],\"container\":{\"id\":\"cont_1\"},"
                + "\"usage\":{\"input_tokens\":120,\"output_tokens\":1}}}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void rebuildsBlocksFromDeltas_keepingOpaqueFieldsIntact_andMergingUsage() {
        start();
        feed("{\"type\":\"ping\"}");
        feed("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"server_tool_use\",\"id\":\"srvtoolu_1\",\"name\":\"web_search\",\"input\":{}}}");
        feed("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"query\\\": \\\"tamb\"}}");
        feed("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"ora 1815\\\"}\"}}");
        feed("{\"type\":\"content_block_stop\",\"index\":0}");
        feed("{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"web_search_tool_result\",\"tool_use_id\":\"srvtoolu_1\","
                + "\"content\":[{\"type\":\"web_search_result\",\"url\":\"https://volcano.si.edu/tambora\",\"encrypted_content\":\"ENC-123\"}]}}");
        feed("{\"type\":\"content_block_stop\",\"index\":1}");
        feed("{\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}");
        feed("{\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"citations_delta\",\"citation\":{\"type\":\"web_search_result_location\",\"url\":\"https://volcano.si.edu/tambora\",\"encrypted_index\":\"IDX\"}}}");
        feed("{\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hola \"}}");
        feed("{\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"text_delta\",\"text\":\"mundo\"}}");
        feed("{\"type\":\"content_block_stop\",\"index\":2}");
        feed("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":42,\"server_tool_use\":{\"web_search_requests\":1}}}");
        feed("{\"type\":\"message_stop\"}");

        Map<String, Object> result = accumulator.result();

        assertThat(result.get("stop_reason")).isEqualTo("end_turn");
        assertThat(result.get("container")).isEqualTo(Map.of("id", "cont_1"));
        assertThat(result.get("usage")).isEqualTo(Map.of("input_tokens", 120, "output_tokens", 42,
                "server_tool_use", Map.of("web_search_requests", 1)));
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertThat(content).hasSize(3);
        assertThat(content.get(0).get("input")).isEqualTo(Map.of("query", "tambora 1815"));
        assertThat(content.get(0).get("id")).isEqualTo("srvtoolu_1");
        assertThat(content.get(1).toString()).contains("ENC-123");
        assertThat(content.get(2).get("text")).isEqualTo("Hola mundo");
        assertThat((List<Map<String, Object>>) content.get(2).get("citations"))
                .singleElement().satisfies(c -> assertThat(c.get("encrypted_index")).isEqualTo("IDX"));
    }

    @Test
    void streamWithoutMessageStop_isAnError() {
        start();
        feed("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}");
        assertThatThrownBy(accumulator::result).isInstanceOf(ClaudeApiException.class).hasMessageContaining("message_stop");
    }

    @Test
    void unknownDeltaType_isAnError_notASilentlyBrokenBlock() {
        start();
        feed("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}");
        assertThatThrownBy(() -> feed("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"nuevo_delta\",\"x\":1}}"))
                .isInstanceOf(ClaudeApiException.class).hasMessageContaining("nuevo_delta");
    }

    @Test
    void deltaForABlockThatNeverStarted_isAnError() {
        start();
        assertThatThrownBy(() -> feed("{\"type\":\"content_block_delta\",\"index\":3,\"delta\":{\"type\":\"text_delta\",\"text\":\"x\"}}"))
                .isInstanceOf(ClaudeApiException.class).hasMessageContaining("content_block_start");
    }

    @Test
    void errorEvent_isAnErrorWithTheApiType() {
        start();
        assertThatThrownBy(() -> feed("{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}"))
                .isInstanceOf(ClaudeApiException.class).hasMessageContaining("overloaded_error");
    }
}
