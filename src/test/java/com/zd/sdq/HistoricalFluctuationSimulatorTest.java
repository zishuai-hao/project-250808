package com.zd.sdq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zd.sdq.config.DeviceReportConfig;
import com.zd.sdq.config.InclineFluctuationConfig;
import com.zd.sdq.service.incline.InclineFluctuationService;
import com.zd.sdq.entity.InclineForwardData;
import com.zd.sdq.entity.dto.DeviceReportSample;
import com.zd.sdq.mapper.InclineForwardDataMapper;
import com.zd.sdq.service.incline.HistoricalFluctuationSimulator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HistoricalFluctuationSimulatorTest {
    private static final Instant SOURCE = Instant.parse("2026-11-01T02:00:00Z");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));
    private final ObjectMapper json = new ObjectMapper();
    private final InclineForwardDataMapper mapper = mock(InclineForwardDataMapper.class);
    private final DeviceReportConfig.Simulation config = new DeviceReportConfig.Simulation();
    private final HistoricalFluctuationSimulator simulator = new HistoricalFluctuationSimulator(mapper, json,
                    new InclineFluctuationService(new InclineFluctuationConfig()));
    private List<InclineForwardData> rows;

    @BeforeEach
    void historyQuery() {
        when(mapper.selectSimulationHistory(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenAnswer(call -> rows);
    }

    @Test
    void learnsChangingValuesWithinHistoricalBoundsAndReproducesTheSamePathAfterRestart() throws Exception {
        DeviceReportSample sample = history(false, new double[]{0.006, 0.0068, 0.0062, 0.0067, 0.0061, 0.0066, 0.0063, 0.0065});
        var distinct = new HashSet<Double>();
        double previous = Double.NaN;
        for (int hour = 2; hour <= 4; hour++) {
            var result = simulator.simulate(sample, SOURCE.plusSeconds(hour * 3600L), config).orElseThrow();
            var restarted = new HistoricalFluctuationSimulator(mapper, json,
                    new InclineFluctuationService(new InclineFluctuationConfig()))
                    .simulate(sample, SOURCE.plusSeconds(hour * 3600L), config).orElseThrow();
            assertEquals(result, restarted);
            assertEquals(8, result.trainingCount());
            double value = Double.parseDouble(result.numbers().get(0));
            distinct.add(value);
            assertTrue(value >= 0.006 && value <= 0.0068);
            if (Double.isFinite(previous)) assertTrue(Math.abs(value - previous) <= 0.0008 + 0.00001);
            previous = value;
        }
        assertTrue(distinct.size() > 1, "学习的波动路径不应退化为原值保持");
        assertTrue(distinct.stream().anyMatch(value -> value != 0.0065));
        assertTrue(simulator.simulate(sample, SOURCE.plusSeconds(4 * 3600 + 1), config).isEmpty());
        verify(mapper, never()).insert(any(InclineForwardData.class));
    }

    @Test
    void changesAmplitudeWithTheTrainingDataRatherThanUsingAFixedNoiseRange() throws Exception {
        double[] small = {1.6, 2.2, 1.8, 2.4, 1.8, 2.0, 2.3, 2.1};
        var sample = history(false, small);
        List<Double> first = new ArrayList<>();
        for (int hour = 2; hour <= 4; hour++) {
            first.add(Double.parseDouble(simulator.simulate(sample, SOURCE.plusSeconds(hour * 3600L), config)
                    .orElseThrow().numbers().get(0)));
        }
        double[] large = java.util.Arrays.stream(small).map(value -> value * 10).toArray();
        sample = history(false, large);
        for (int hour = 2; hour <= 4; hour++) {
            double value = Double.parseDouble(simulator.simulate(sample, SOURCE.plusSeconds(hour * 3600L), config)
                    .orElseThrow().numbers().get(0));
            assertEquals(first.get(hour - 2) * 10, value, 0.0006);
        }
    }

    @Test
    void jointlyResamplesInclinationAxesAndKeepsTheirLearnedRelationship() throws Exception {
        var sample = history(true, new double[]{0.01, 0.012, 0.011, 0.014, 0.013, 0.011, 0.015, 0.012});
        for (int hour = 2; hour <= 4; hour++) {
            var values = simulator.simulate(sample, SOURCE.plusSeconds(hour * 3600L), config).orElseThrow().numbers();
            assertEquals(3, values.size());
            assertEquals(2 * Double.parseDouble(values.get(0)), Double.parseDouble(values.get(1)), 0.0000015);
            assertEquals("20.000", values.get(2));
        }
    }

    @Test
    void insufficientFlatOrDisconnectedHistoryDoesNotFallBackToHolding() throws Exception {
        var sample = history(false, new double[]{1, 2, 1, 2, 1});
        assertTrue(simulator.simulate(sample, SOURCE.plusSeconds(7200), config).isEmpty());
        sample = history(false, new double[]{1, 1, 1, 1, 1, 1, 1, 1});
        assertTrue(simulator.simulate(sample, SOURCE.plusSeconds(7200), config).isEmpty());
        sample = history(false, new double[]{1, 2, 1, 2, 1, 2, 1, 2});
        rows.get(1).setDataTime(TIME.format(SOURCE.minusSeconds(3 * 3600)));
        assertTrue(simulator.simulate(sample, SOURCE.plusSeconds(7200), config).isEmpty());
    }

    @Test
    void doesNotTrainAcrossAlarmsFaultsOrInclinationBaselineChanges() throws Exception {
        for (String field : List.of("alarmStatus", "runStatus")) {
            var sample = history(false, new double[]{1, 2, 1, 2, 1, 2, 1, 2});
            var raw = (ObjectNode) json.readTree(rows.get(1).getRawData());
            raw.put(field, 1);
            rows.get(1).setRawData(raw.toString());
            assertTrue(simulator.simulate(sample, SOURCE.plusSeconds(7200), config).isEmpty());
        }
        var sample = history(true, new double[]{0.01, 0.012, 0.011, 0.014, 0.013, 0.011, 0.015, 0.012});
        var raw = (ObjectNode) json.readTree(rows.get(1).getRawData());
        raw.put("angleX", raw.path("angleX").asDouble() + 1);
        rows.get(1).setRawData(raw.toString());
        assertTrue(simulator.simulate(sample, SOURCE.plusSeconds(7200), config).isEmpty());
    }

    @Test
    void learnsAlreadyAmplifiedPayloadWithoutApplyingTheFactorAgainAndStopsAtOldUnscaledHistory() throws Exception {
        var amplification = new InclineFluctuationConfig();
        amplification.setEnabled(true);
        var center = new InclineFluctuationConfig.Center();
        center.setX(0.0);
        center.setY(0.0);
        amplification.getCenters().put("TEST", center);
        var learner = new HistoricalFluctuationSimulator(mapper, json,
                new InclineFluctuationService(amplification));
        var sample = history(true, new double[]{0.01, 0.012, 0.011, 0.014, 0.013, 0.011, 0.015, 0.012});
        for (var row : rows) row.setDeviceCode("TEST");
        assertTrue(learner.simulate(sample, SOURCE.plusSeconds(7200), config).isEmpty(),
                "不能把旧的未放大历史与当前2倍处理视为同一零点窗口");
        for (var row : rows) {
            var payload = json.readTree(row.getPayload());
            var values = (com.fasterxml.jackson.databind.node.ArrayNode) payload.get(0);
            values.set(1, json.getNodeFactory().textNode(Double.toString(values.get(1).asDouble()*2)));
            values.set(2, json.getNodeFactory().textNode(Double.toString(values.get(2).asDouble()*2)));
            row.setPayload(payload.toString());
        }
        sample.setPayload(rows.get(0).getPayload());
        var learned = learner.simulate(sample, SOURCE.plusSeconds(7200), config).orElseThrow();
        assertEquals(8, learned.trainingCount());
        double x = Double.parseDouble(learned.numbers().get(0));
        assertTrue(x >= .02 && x <= .03, "在已放大历史范围内生成，不再额外放大一次");
        assertEquals(x*2, Double.parseDouble(learned.numbers().get(1)), .0000015);
        var boundary = json.readTree(rows.get(1).getPayload());
        ((com.fasterxml.jackson.databind.node.ArrayNode) boundary.get(0)).set(1,
                json.getNodeFactory().textNode("0.015"));
        rows.get(1).setPayload(boundary.toString());
        assertTrue(learner.simulate(sample, SOURCE.plusSeconds(7200), config).isEmpty());
    }

    private DeviceReportSample history(boolean incline, double[] values) throws Exception {
        rows = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            var row = new InclineForwardData();
            row.setDataTime(TIME.format(SOURCE.minusSeconds((values.length - 1L - i) * 3600)));
            List<String> numbers = new ArrayList<>(List.of(row.getDataTime(), Double.toString(values[i])));
            if (incline) numbers.addAll(List.of(Double.toString(2 * values[i]), "20.000"));
            row.setPayload(json.writeValueAsString(List.of(numbers)));
            row.setRawData(json.createObjectNode().put("runStatus", 0).put("alarmStatus", 0)
                    .put("angleX", values[i]).put("angleY", 2 * values[i]).put("zero", 0).toString());
            rows.add(row);
        }
        Collections.reverse(rows);
        var sample = new DeviceReportSample();
        sample.setDeviceCode("TEST");
        sample.setRemoteDeviceId("SN");
        sample.setDeviceType(incline ? "INC" : "VIB");
        sample.setDataTime(TIME.format(SOURCE));
        sample.setPayload(rows.get(0).getPayload());
        return sample;
    }
}
