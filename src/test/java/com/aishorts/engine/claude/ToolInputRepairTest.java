package com.aishorts.engine.claude;

import com.anthropic.core.ObjectMappers;
import com.anthropic.errors.AnthropicInvalidDataException;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ClaudeStreamingClient.ToolInputRepair con eventos SSE escritos a mano (el
 * JSON de cada "data:") y el MessageAccumulator real de anthropic-java.
 */
class ToolInputRepairTest {

    private static final String MESSAGE_START = """
            {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"m",
             "content":[],"stop_reason":null,"stop_sequence":null,
             "usage":{"input_tokens":10,"output_tokens":1}}}""";
    private static final String MESSAGE_END_1 = """
            {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":5}}""";
    private static final String MESSAGE_END_2 = "{\"type\":\"message_stop\"}";

    private static RawMessageStreamEvent event(String json) {
        try {
            return ObjectMappers.jsonMapper().readValue(json, RawMessageStreamEvent.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(json, e);
        }
    }

    private static List<RawMessageStreamEvent> events(String... json) {
        List<RawMessageStreamEvent> events = new ArrayList<>();
        events.add(event(MESSAGE_START));
        for (String j : json) {
            events.add(event(j));
        }
        events.add(event(MESSAGE_END_1));
        events.add(event(MESSAGE_END_2));
        return events;
    }

    private static Message accumulateWithRepair(List<RawMessageStreamEvent> events) {
        MessageAccumulator accumulator = MessageAccumulator.create();
        ClaudeStreamingClient.ToolInputRepair repair = new ClaudeStreamingClient.ToolInputRepair();
        for (RawMessageStreamEvent event : events) {
            repair.apply(event).forEach(accumulator::accumulate);
        }
        return accumulator.message();
    }

    private static Message accumulateWithoutRepair(List<RawMessageStreamEvent> events) {
        MessageAccumulator accumulator = MessageAccumulator.create();
        events.forEach(accumulator::accumulate);
        return accumulator.message();
    }

    private static Map<String, Object> block(Message message, int index) {
        return ClaudeStreamingClient.toMap(message.content().get(index));
    }

    /** Texto en 0 y 1, y en 2 un server_tool_use con el input completo en el start y SIN deltas (el caso del error). */
    private static List<RawMessageStreamEvent> serverToolUseWithInputInStart() {
        return events(
                """
                {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
                """
                {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Busco."}}""",
                """
                {"type":"content_block_stop","index":0}""",
                """
                {"type":"content_block_start","index":1,"content_block":{"type":"server_tool_use","id":"srvtoolu_1","name":"web_search","input":{}}}""",
                """
                {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"query\\":"}}""",
                """
                {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\\"tunguska\\"}"}}""",
                """
                {"type":"content_block_stop","index":1}""",
                """
                {"type":"content_block_start","index":2,"content_block":{"type":"server_tool_use","id":"srvtoolu_2","name":"code_execution",
                 "input":{"code":"print(len(results))","nested":{"a":[1,2],"b":null}}}}""",
                """
                {"type":"content_block_stop","index":2}""");
    }

    @Test
    void withoutTheRepair_theSdkFailsLikeInProduction() {
        assertThatThrownBy(() -> accumulateWithoutRepair(serverToolUseWithInputInStart()))
                .isInstanceOf(AnthropicInvalidDataException.class)
                .hasMessage("Missing input JSON for index 2.");
    }

    @Test
    void serverToolUseWithInputInStartAndNoDeltas_keepsThatInput() {
        Message message = accumulateWithRepair(serverToolUseWithInputInStart());

        Map<String, Object> repaired = block(message, 2);
        assertThat(repaired.get("type")).isEqualTo("server_tool_use");
        assertThat(repaired.get("id")).isEqualTo("srvtoolu_2");
        assertThat(repaired.get("name")).isEqualTo("code_execution");
        Map<String, Object> nested = new java.util.LinkedHashMap<>();
        nested.put("a", List.of(1, 2));
        nested.put("b", null);
        assertThat(repaired.get("input")).isEqualTo(Map.of("code", "print(len(results))", "nested", nested));
        // los otros bloques, como siempre
        assertThat(block(message, 0).get("text")).isEqualTo("Busco.");
        assertThat(block(message, 1).get("input")).isEqualTo(Map.of("query", "tunguska"));
    }

    @Test
    void toolUseWithDeltas_sameResultAsWithoutTheRepair_andNoEventIsAdded() {
        List<RawMessageStreamEvent> events = events(
                """
                {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"get_weather","input":{}}}""",
                """
                {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"city\\": \\"Li"}}""",
                """
                {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"ma\\"}"}}""",
                """
                {"type":"content_block_stop","index":0}""");

        Message repaired = accumulateWithRepair(events);
        Message original = accumulateWithoutRepair(events);

        assertThat(ClaudeStreamingClient.toJsonValue(repaired)).isEqualTo(ClaudeStreamingClient.toJsonValue(original));
        assertThat(block(repaired, 0).get("input")).isEqualTo(Map.of("city", "Lima"));
        ClaudeStreamingClient.ToolInputRepair repair = new ClaudeStreamingClient.ToolInputRepair();
        for (RawMessageStreamEvent event : events) {
            assertThat(repair.apply(event)).containsExactly(event);
        }
    }

    @Test
    void toolBlockWithoutInputNorDeltas_getsAnEmptyObject() {
        Message message = accumulateWithRepair(events(
                """
                {"type":"content_block_start","index":0,"content_block":{"type":"server_tool_use","id":"srvtoolu_3","name":"web_search"}}""",
                """
                {"type":"content_block_stop","index":0}"""));

        assertThat(block(message, 0).get("input")).isEqualTo(Map.of());
    }
}
