package com.zd.sdq;

import com.company.cbf.starter.data.entity.MqttData;
import com.company.cbf.starter.data.service.forward.ForwardMqttClientService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zd.sdq.entity.MqttClientConfigEntity;
import com.zd.sdq.mapper.MqttConfigEntityMapper;
import com.zd.sdq.service.DeviceReportMqttSender;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.mqtt.MqttClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeviceReportMqttSenderTest {
    private final ForwardMqttClientService clients = mock(ForwardMqttClientService.class);
    private final MqttConfigEntityMapper gateways = mock(MqttConfigEntityMapper.class);
    private final MqttClient client = mock(MqttClient.class);
    private final ObjectMapper json = new ObjectMapper();
    private final Instant sentAt = Instant.parse("2026-11-01T02:00:02Z");
    private final DeviceReportMqttSender sender = new DeviceReportMqttSender(clients, gateways, json,
            Clock.fixed(sentAt, ZoneOffset.UTC));

    @Test
    void sendsBothTypesInOneMessageWithSharedSendTimeAndPreservesAlignedDataTime() throws Exception {
        connected();
        sender.sendOnce(1L, List.of(data("A-INC", "INC"), data("A-VIB", "VIB")));
        var payload = ArgumentCaptor.forClass(Buffer.class);
        verify(client, times(1)).publish(eq("test/topic"), payload.capture(), eq(MqttQoS.AT_MOST_ONCE),
                eq(false), eq(false), any());
        var body = json.readTree(payload.getValue().toString());
        assertEquals("test-tag", body.path("data_tag").asText());
        assertEquals(2, body.path("mqtt_data").size());
        for (var item : body.path("mqtt_data")) {
            assertEquals(sentAt.toEpochMilli(), item.path("sample_time").asLong());
            assertEquals("2026-11-01 10:00:00.000", item.path("value").get(0).get(0).asText());
        }
        verify(clients, never()).sendData(any(), anyString());
    }

    @Test
    void disconnectAndDisabledGatewaySkipWithoutPublishing() {
        connected();
        when(client.isConnected()).thenReturn(false);
        assertTrue(sender.sendOnce(1L, List.of(data("A", "INC"))).isCompletedExceptionally());
        verify(client, never()).publish(anyString(), any(), any(), anyBoolean(), anyBoolean(), any());
        when(client.isConnected()).thenReturn(true);
        var gateway = new MqttClientConfigEntity();
        gateway.setEnable(false);
        when(gateways.selectById(1L)).thenReturn(gateway);
        assertTrue(sender.sendOnce(1L, List.of(data("A", "INC"))).isCompletedExceptionally());
        verify(client, never()).publish(anyString(), any(), any(), anyBoolean(), anyBoolean(), any());
    }

    @Test
    void serializesOnlyLegacyFieldsAndPreservesProtocolColumns() throws Exception {
        connected();
        var vibration = MqttData.builder().deviceId("A-VIB").deviceType("VIB")
                .value(List.of(List.of("2026-11-01 10:00:00.000", "0.0060"))).build();
        sender.sendOnce(1L, List.of(data("A-INC", "INC"), vibration));
        var payload = ArgumentCaptor.forClass(Buffer.class);
        verify(client).publish(eq("test/topic"), payload.capture(), eq(MqttQoS.AT_MOST_ONCE),
                eq(false), eq(false), any());
        var body = json.readTree(payload.getValue().toString());
        java.util.Set<String> outer = new java.util.HashSet<>();
        body.fieldNames().forEachRemaining(outer::add);
        assertEquals(java.util.Set.of("data_tag", "mqtt_data"), outer);
        var items = body.path("mqtt_data");
        for (var item : items) {
            java.util.Set<String> fields = new java.util.HashSet<>();
            item.fieldNames().forEachRemaining(fields::add);
            assertEquals(java.util.Set.of("sample_time", "device_id", "device_type", "value"), fields);
            assertEquals(sentAt.toEpochMilli(), item.path("sample_time").asLong());
        }
        assertEquals(2, items.get(1).path("value").get(0).size());
        assertEquals("0.0060", items.get(1).path("value").get(0).get(1).asText());
    }

    @Test
    void asynchronousFailureDoesNotPublishAgain() {
        connected();
        doAnswer(call -> {
            io.vertx.core.Handler<io.vertx.core.AsyncResult<Integer>> callback = call.getArgument(5);
            callback.handle(Future.failedFuture("offline"));
            return client;
        }).when(client).publish(anyString(), any(), any(), anyBoolean(), anyBoolean(), any());
        assertTrue(sender.sendOnce(1L, List.of(data("A", "INC"))).isCompletedExceptionally());
        verify(client, times(1)).publish(anyString(), any(), any(), anyBoolean(), anyBoolean(), any());
    }

    @Test
    void synchronousFailureDoesNotPublishAgain() {
        connected();
        doThrow(new IllegalStateException("closed")).when(client)
                .publish(anyString(), any(), any(), anyBoolean(), anyBoolean(), any());
        assertTrue(sender.sendOnce(1L, List.of(data("A", "INC"))).isCompletedExceptionally());
        verify(client, times(1)).publish(anyString(), any(), any(), anyBoolean(), anyBoolean(), any());
    }

    @Test
    void completionWaitsForTheAsynchronousPublishResult() {
        connected();
        var completion = sender.sendOnce(1L, List.of(data("A", "INC")));
        assertFalse(completion.isDone());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<io.vertx.core.Handler<io.vertx.core.AsyncResult<Integer>>> callback = ArgumentCaptor.forClass(io.vertx.core.Handler.class);
        verify(client).publish(anyString(), any(), any(), anyBoolean(), anyBoolean(), callback.capture());
        callback.getValue().handle(Future.succeededFuture(1));
        assertDoesNotThrow(completion::join);
        assertTrue(completion.isDone());
    }

    private void connected() {
        var gateway = new MqttClientConfigEntity();
        gateway.setEnable(true);
        gateway.setTopic("test/topic");
        gateway.setDataTag("test-tag");
        when(gateways.selectById(1L)).thenReturn(gateway);
        when(clients.getMqttClient(1L)).thenReturn(client);
        when(client.isConnected()).thenReturn(true);
    }

    private MqttData data(String code, String type) {
        return MqttData.builder().deviceId(code).deviceType(type)
                .value(List.of(List.of("2026-11-01 10:00:00.000", "0.1"))).build();
    }
}
