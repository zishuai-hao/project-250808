package com.zd.sdq;

import cn.hutool.core.date.DateUtil;
import com.company.cbf.starter.data.service.forward.device.DeviceType;
import com.zd.sdq.config.VibrationFilterConfig;
import com.zd.sdq.config.InclineFluctuationConfig;
import com.zd.sdq.service.incline.InclineFluctuationService;
import com.zd.sdq.entity.DeviceInfoExt;
import com.zd.sdq.entity.dto.incline.InclineHistoryResponse;
import com.zd.sdq.service.DeviceLatestDataCache;
import com.zd.sdq.service.incline.InclineDataHandler;
import com.zd.sdq.service.incline.InclineApiService;
import com.zd.sdq.service.incline.InclineForwardDataService;
import com.zd.sdq.service.incline.InclineBaselineService;
import com.zd.sdq.mapper.DeviceInfoExtMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zd.sdq.service.incline.VibrationSpikeFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InclineVibrationFilterTest {
    private static final String CODE = "ZYXH-VIB-P01-002-02";
    private static final String SN = "1524020712";
    private static final String START = "2026-11-01 00:00:00";
    private static final String TIME = "2026-11-01 01:00:00";
    private final VibrationFilterConfig config = new VibrationFilterConfig();
    private final DeviceLatestDataCache cache = new DeviceLatestDataCache();
    private final InclineForwardDataService archive = mock(InclineForwardDataService.class);
    private final Clock clock = mock(Clock.class);
    private final DeviceInfoExtMapper baselineMapper = mock(DeviceInfoExtMapper.class);
    private final InclineBaselineService baselines = new InclineBaselineService(
            baselineMapper, new ObjectMapper(), null);
    private InclineDataHandler handler;
    private DeviceInfoExt vibration;

    @BeforeEach
    void setUp() {
        when(baselineMapper.getInclineBaselineJson(anyString())).thenReturn(
                "{\"angleX\":0,\"angleY\":0,\"updatedAt\":\"2026-11-01T00:00:00Z\"}");
        handler = new InclineDataHandler(null, null, null, cache,
                new VibrationSpikeFilter(config, clock), archive, baselines,
                new InclineFluctuationService(new InclineFluctuationConfig(), null, new com.fasterxml.jackson.databind.ObjectMapper()));
        Map<String, Date> lastTimes = lastTimes();
        lastTimes.put(SN, DateUtil.parse(START));
        vibration = device(CODE, DeviceType.VIB);
    }

    @ParameterizedTest
    @ValueSource(doubles = {6.0, -6.0, 2.2, -2.2})
    void rejectsOnlyConfirmedIsolatedSpikesButStillForwardsInclination(double acceleration) {
        DeviceInfoExt incline = device("ZYXH-INC-P01-002-02", DeviceType.INC);
        process(List.of(vibration, incline), record(0.1, TIME));
        InclineHistoryResponse.InclineRecord peak = record(acceleration, "2026-11-01 02:00:00");
        process(List.of(vibration, incline), peak);

        verifyStored(CODE, times(1));
        verify(archive, never()).saveCollectedData(eq(vibration), anyString(), same(peak), any());
        verifyStored(incline.getDeviceCode(), times(2));
        assertEquals(TIME, cache.getLatestData(CODE).getDataTime());
        assertNotNull(cache.getLatestData(incline.getDeviceCode()));
        assertEquals(DateUtil.parse(peak.getTm()), lastTimes().get(SN));

        process(List.of(vibration, incline), record(0.11, "2026-11-01 03:00:00"));
        verifyStored(CODE, times(2));
        verify(archive, never()).saveCollectedData(eq(vibration), anyString(), same(peak), any());
        assertEquals("2026-11-01 03:00:00", cache.getLatestData(CODE).getDataTime());
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 0.2245, -0.1783, 2.0, -2.0, 6.0})
    void forwardsFirstRecordWithoutInventingAPreviousNeighbor(double acceleration) {
        process(List.of(vibration), record(acceleration, TIME));

        verifyStored(CODE, times(1));
        assertEquals(acceleration,
                Double.parseDouble(cache.getLatestData(CODE).getLatestData().get("accMaxZ").toString()), 0.00005);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void rejectsMissingOrNonFiniteValuesWithoutLosingInclination(Double rawValue) {
        InclineHistoryResponse.InclineRecord record = record(0, TIME);
        record.setAccMaxZ(rawValue);
        DeviceInfoExt incline = device("ZYXH-INC-P01-002-02", DeviceType.INC);

        assertDoesNotThrow(() -> process(List.of(vibration, incline), record));
        verifyStored(CODE, never());
        verifyStored(incline.getDeviceCode(), times(1));
    }

    @Test
    void spikePreservesLastValidValueAndLaterValidRecordsRecover() {
        process(List.of(vibration), record(0.1, TIME));
        process(List.of(vibration), record(6, "2026-11-01 02:00:00"));
        assertEquals(TIME, cache.getLatestData(CODE).getDataTime());
        process(List.of(vibration), record(-0.2, "2026-11-01 03:00:00"));

        verifyStored(CODE, times(2));
        assertEquals(-0.2, Double.parseDouble(cache.getLatestData(CODE).getLatestData().get("accMaxZ").toString()));
        assertEquals("2026-11-01 03:00:00", cache.getLatestData(CODE).getDataTime());
    }

    @Test
    void leavesOtherVibrationDevicesUnchanged() {
        DeviceInfoExt other = device("ZYXH-VIB-P01-002-03", DeviceType.VIB);
        process(List.of(other), record(0.1, TIME), record(6, "2026-11-01 02:00:00"), record(0.1, "2026-11-01 03:00:00"));
        verifyStored(other.getDeviceCode(), times(3));
    }

    @Test
    void storesBothChannelsWithOriginalTimeAndSkipsDuplicates() {
        DeviceInfoExt incline = device("ZYXH-INC-P01-002-02", DeviceType.INC);
        InclineHistoryResponse.InclineRecord record = record(0.22451234, TIME);
        record.setAngleX(0.123456789);
        process(List.of(vibration, incline), record);

        var order = inOrder(archive);
        var vibrationData = org.mockito.ArgumentCaptor.forClass(com.company.cbf.starter.data.entity.MqttData.class);
        var inclineData = org.mockito.ArgumentCaptor.forClass(com.company.cbf.starter.data.entity.MqttData.class);
        order.verify(archive).saveCollectedData(eq(vibration), eq(SN), same(record), vibrationData.capture());

        order.verify(archive).saveCollectedData(eq(incline), eq(SN), same(record), inclineData.capture());

        assertTrue(vibrationData.getValue().getValue().get(0).contains("0.2245"));
        assertTrue(inclineData.getValue().getValue().get(0).contains("0.123457"));

        clearInvocations(archive);
        process(List.of(vibration, incline), record);
        verifyNoInteractions(archive);
    }

    @Test
    void databaseFailurePreventsForwardAndKeepsProgressForRetry() {
        InclineHistoryResponse.InclineRecord record = record(0.1, TIME);
        doThrow(new IllegalStateException("database unavailable")).doNothing()
                .when(archive).saveCollectedData(eq(vibration), eq(SN), same(record), any());

        assertThrows(IllegalStateException.class, () -> process(List.of(vibration), record));
        assertNull(cache.getLatestData(CODE));
        assertEquals(DateUtil.parse(START), lastTimes().get(SN));

        process(List.of(vibration), record);
        verifyStored(CODE, times(2));
        assertEquals(DateUtil.parse(TIME), lastTimes().get(SN));
    }

    @Test
    void preservesConsecutiveHighValuesWithOriginalSampleTimes() {
        process(List.of(vibration), record(0.1, TIME), record(6, "2026-11-01 02:00:00"),
                record(6.1, "2026-11-01 03:00:00"), record(0.11, "2026-11-01 04:00:00"));
        var data = org.mockito.ArgumentCaptor.forClass(com.company.cbf.starter.data.entity.MqttData.class);
        verify(archive, times(4)).saveCollectedData(eq(vibration), eq(SN), any(), data.capture());
        verifyStored(CODE, times(4));
        assertEquals("2026-11-01 02:00:00.000", data.getAllValues().get(1).getValue().get(0).get(0));
        assertEquals("2026-11-01 03:00:00.000", data.getAllValues().get(2).getValue().get(0).get(0));
    }

    @Test
    void preservesStepChangesAndSmallFluctuations() {
        process(List.of(vibration), record(0.1, TIME), record(6, "2026-11-01 02:00:00"),
                record(1.0, "2026-11-01 03:00:00"), record(0.1, "2026-11-01 04:00:00"),
                record(0.15, "2026-11-01 05:00:00"), record(0.1, "2026-11-01 06:00:00"));
        verifyStored(CODE, times(6));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void preservesPeaksWithReportedAlarms(int status) {
        var peak = record(6, "2026-11-01 02:00:00");
        peak.setAlarmStatus(status);
        process(List.of(vibration), record(0.1, TIME), peak, record(0.11, "2026-11-01 03:00:00"));
        verifyStored(CODE, times(3));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {1})
    void preservesPeaksWithFaultOrUnknownRunStatus(Integer status) {
        var peak = record(6, "2026-11-01 02:00:00");
        peak.setRunStatus(status);
        process(List.of(vibration), record(0.1, TIME), peak, record(0.11, "2026-11-01 03:00:00"));
        verifyStored(CODE, times(3));
    }

    @Test
    void preservesPendingPeakIfNextRecordReportsAlarm() {
        var next = record(0.11, "2026-11-01 03:00:00");
        next.setAlarmStatus(2);
        process(List.of(vibration), record(0.1, TIME), record(6, "2026-11-01 02:00:00"), next);
        verifyStored(CODE, times(3));
    }

    @Test
    void doesNotConfirmIsolationAcrossMissingOrWidelySpacedRecords() {
        var missing = record(0, "2026-11-01 03:00:00");
        missing.setAccMaxZ(null);
        process(List.of(vibration), record(0.1, TIME), record(6, "2026-11-01 02:00:00"), missing,
                record(0.11, "2026-11-01 04:00:00"), record(6, "2026-11-01 05:00:00"),
                record(0.1, "2026-11-01 07:00:00"));
        verifyStored(CODE, times(5));
    }

    @Test
    void doesNotStageAPeakWithoutARecentPreviousNeighbor() {
        process(List.of(vibration), record(0.1, TIME), record(6, "2026-11-01 03:00:00"),
                record(0.1, "2026-11-01 04:00:00"));
        verifyStored(CODE, times(3));
    }

    @Test
    void pendingPeakExpiresWithoutAnotherRecordAndCanRetryDatabaseFailure() {
        var peak = record(6, "2026-11-01 02:00:00");
        process(List.of(vibration), record(0.1, TIME), peak);
        handler.flushPendingVibrationData();
        verifyStored(CODE, times(1));

        when(clock.millis()).thenReturn(config.getMaxGapSeconds() * 1000L);
        doThrow(new IllegalStateException("database unavailable")).doNothing()
                .when(archive).saveCollectedData(eq(vibration), eq(SN), same(peak), any());
        assertThrows(IllegalStateException.class, handler::flushPendingVibrationData);
        handler.flushPendingVibrationData();
        handler.flushPendingVibrationData();
        verifyStored(CODE, times(3));
        verify(archive, times(2)).saveCollectedData(eq(vibration), eq(SN), same(peak), any());
        assertEquals(peak.getTm(), cache.getLatestData(CODE).getDataTime());
    }

    @Test
    void rollsBackSpikeConfirmationIfForwardingTheRecoveryPointFails() {
        var next = record(0.11, "2026-11-01 03:00:00");
        var peak = record(6, "2026-11-01 02:00:00");
        process(List.of(vibration), record(0.1, TIME), peak);
        doThrow(new IllegalStateException("database unavailable")).doNothing()
                .when(archive).saveCollectedData(eq(vibration), eq(SN), same(next), any());
        assertThrows(IllegalStateException.class, () -> process(List.of(vibration), next));
        assertEquals(DateUtil.parse(peak.getTm()), lastTimes().get(SN));
        process(List.of(vibration), next);
        verifyStored(CODE, times(3));
        verify(archive, never()).saveCollectedData(eq(vibration), eq(SN), same(peak), any());
        assertEquals(next.getTm(), cache.getLatestData(CODE).getDataTime());
    }

    @Test
    void sortsDescendingBatchesAndKeepsWindowAcrossDuplicateQueries() {
        var peak = record(6, "2026-11-01 02:00:00");
        var next = record(0.11, "2026-11-01 03:00:00");
        process(List.of(vibration), peak, record(0.1, TIME));
        process(List.of(vibration), next, peak);
        verifyStored(CODE, times(2));
        verify(archive, never()).saveCollectedData(eq(vibration), eq(SN), same(peak), any());
    }

    @Test
    void keepsSeparateWindowsForConfiguredDevices() {
        DeviceInfoExt other = device("ZYXH-VIB-P01-002-03", DeviceType.VIB);
        config.getDeviceCodes().add(other.getDeviceCode());
        process(List.of(vibration), record(0.1, TIME), record(6, "2026-11-01 02:00:00"));
        lastTimes().put("OTHER", DateUtil.parse(START));
        // 共用一次远程记录处理时，也不能把另一个测点的首条高值当作本测点的待确认点。
        var response = new InclineHistoryResponse();
        var data = new InclineHistoryResponse.InclineHistoryData();
        data.setResultData(List.of(record(6, TIME)));
        response.setData(data);
        ReflectionTestUtils.invokeMethod(handler, "processResponse", List.of(other), response, "OTHER");
        process(List.of(vibration), record(0.11, "2026-11-01 03:00:00"));
        verifyStored(CODE, times(2));
        verifyStored(other.getDeviceCode(), times(1));
    }

    @Test
    void requestsOldestUnprocessedRecordsWithTheSuppliedTimeRange() throws Exception {
        InclineApiService api = mock(InclineApiService.class);
        when(api.getLoginToken()).thenReturn("test-token");
        var queryHandler = new InclineDataHandler(null, null, api, cache,
                new VibrationSpikeFilter(config, clock), archive, baselines,
                new InclineFluctuationService(new InclineFluctuationConfig(), null, new com.fasterxml.jackson.databind.ObjectMapper()));
        queryHandler.fetchDeviceData(SN, TIME, "2026-11-01 04:00:00");
        var request = org.mockito.ArgumentCaptor.forClass(com.zd.sdq.entity.dto.incline.InclineHistoryRequest.class);
        verify(api).getHistoryData(request.capture());
        assertEquals(TIME, request.getValue().getStartTm());
        assertEquals("2026-11-01 04:00:00", request.getValue().getEndTm());
        assertEquals("asc", request.getValue().getSortMode());
        assertEquals(100, request.getValue().getPagesize());
    }

    @Test
    void canDisableOrAdjustSpikeDelta() {
        config.setEnabled(false);
        process(List.of(vibration), record(0.1, TIME), record(6, "2026-11-01 02:00:00"));
        config.setEnabled(true);
        config.setSpikeMinDelta(7);
        config.setSpikeRatio(100);
        process(List.of(vibration), record(0.1, "2026-11-01 03:00:00"), record(6, "2026-11-01 04:00:00"));
        verifyStored(CODE, times(4));
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, -1, Double.NaN, Double.POSITIVE_INFINITY})
    void rejectsInvalidThresholdConfiguration(double threshold) {
        config.setSpikeMinDelta(threshold);
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1, 2, 3, Double.NaN, Double.POSITIVE_INFINITY})
    void rejectsInvalidReturnTolerance(double tolerance) {
        config.setReturnTolerance(tolerance);
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void rejectsInvalidGapAndAcceptsValidDefaults() {
        assertDoesNotThrow(config::validate);
        config.setMaxGapSeconds(0);
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    void includesTheInvestigatedCbxhPointByDefault() {
        assertTrue(config.getDeviceCodes().contains("CBXH-VIB-P01-001-03"));
    }

    @Test
    void rejectsTheCbxhSizedSpikeOnlyAfterARecoverySample() {
        var peak = record(-13.95 * VibrationSpikeFilter.MG_TO_M_S2, "2026-11-01 02:00:00");
        process(List.of(vibration), record(-0.66 * VibrationSpikeFilter.MG_TO_M_S2, TIME), peak);

        verifyStored(CODE, times(1));
        verify(archive, never()).saveCollectedData(any(), anyString(), same(peak), any());
        process(List.of(vibration), record(-0.5 * VibrationSpikeFilter.MG_TO_M_S2, "2026-11-01 03:00:00"));

        verifyStored(CODE, times(2));
        verify(archive, never()).saveCollectedData(any(), anyString(), same(peak), any());
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4})
    void immediatelyReleasesTwoOrMoreElevatedSamplesAndKeepsTheWholeRun(int count) {
        process(List.of(vibration), record(0.006, TIME));
        double[] amplitudes = {0.137, 0.09, 1.5, 6.0};
        for (int i = 0; i < count; i++) {
            process(List.of(vibration), record(amplitudes[i], String.format("2026-11-01 %02d:00:00", i + 2)));
            verifyStored(CODE, times(i == 0 ? 1 : i + 2));
        }
        process(List.of(vibration), record(0.006, String.format("2026-11-01 %02d:00:00", count + 2)));
        verifyStored(CODE, times(count + 2));
    }

    @Test
    void keepsTwoElevatedSamplesEvenWhenTheFirstIsMuchLarger() {
        process(List.of(vibration), record(0.006, TIME), record(6, "2026-11-01 02:00:00"),
                record(0.137, "2026-11-01 03:00:00"), record(0.006, "2026-11-01 04:00:00"));
        verifyStored(CODE, times(4));
    }

    @Test
    void doesNotStageTinyValuesJustBecauseTheirRatioIsLarge() {
        process(List.of(vibration), record(0, TIME), record(0.0001, "2026-11-01 02:00:00"));
        verifyStored(CODE, times(2));
    }

    @Test
    void usesHistoricalMedianInsteadOfAnUnusuallySmallPreviousValue() {
        process(List.of(vibration), record(0.01, TIME), record(0.01, "2026-11-01 02:00:00"),
                record(0.01, "2026-11-01 03:00:00"), record(0.0001, "2026-11-01 04:00:00"),
                record(0.06, "2026-11-01 05:00:00"));
        verifyStored(CODE, times(5));
    }

    @Test
    void honorsTheConfiguredRatioMinimumIncrementAndNoiseFloor() {
        config.setSpikeRatio(20);
        process(List.of(vibration), record(0.006, TIME), record(0.1, "2026-11-01 02:00:00"));
        verifyStored(CODE, times(2));

        config.setSpikeRatio(10);
        config.setRelativeMinDelta(0.2);
        process(List.of(vibration), record(0.006, "2026-11-01 03:00:00"), record(0.137, "2026-11-01 04:00:00"));
        verifyStored(CODE, times(4));

        config.setRelativeMinDelta(0.05);
        config.setNoiseFloor(0.02);
        process(List.of(vibration), record(0, "2026-11-01 05:00:00"), record(0.137, "2026-11-01 06:00:00"));
        verifyStored(CODE, times(6));
    }

    @Test
    void rollsTheHistoricalWindowForward() {
        config.setBaselineWindowSize(3);
        process(List.of(vibration), record(0.1, TIME), record(0.1, "2026-11-01 02:00:00"),
                record(0.1, "2026-11-01 03:00:00"), record(0.01, "2026-11-01 04:00:00"),
                record(0.01, "2026-11-01 05:00:00"), record(0.01, "2026-11-01 06:00:00"),
                record(0.12, "2026-11-01 07:00:00"));
        verifyStored(CODE, times(6));
        process(List.of(vibration), record(0.01, "2026-11-01 08:00:00"));
        verifyStored(CODE, times(7));
    }

    @Test
    void keepsExcursionsOutOfTheBaselineAndStartsFilteringAgainAfterRecovery() {
        process(List.of(vibration), record(0.006, TIME), record(0.137, "2026-11-01 02:00:00"),
                record(0.09, "2026-11-01 03:00:00"), record(1.5, "2026-11-01 04:00:00"),
                record(0.006, "2026-11-01 05:00:00"));
        var isolated = record(0.137, "2026-11-01 06:00:00");
        process(List.of(vibration), isolated, record(0.006, "2026-11-01 07:00:00"));
        verifyStored(CODE, times(6));
        verify(archive, never()).saveCollectedData(any(), anyString(), same(isolated), any());
    }

    @Test
    void filteredRelativePeaksDoNotPolluteTheHistoricalMedian() {
        config.setBaselineWindowSize(3);
        var first = record(0.137, "2026-11-01 02:00:00");
        var second = record(0.137, "2026-11-01 04:00:00");
        process(List.of(vibration), record(0.006, TIME), first, record(0.006, "2026-11-01 03:00:00"),
                second, record(0.006, "2026-11-01 05:00:00"));
        verifyStored(CODE, times(3));
        verify(archive, never()).saveCollectedData(any(), anyString(), same(first), any());
        verify(archive, never()).saveCollectedData(any(), anyString(), same(second), any());
    }

    @Test
    void restoresTheConsecutiveRunWhenReleasingTheSecondHighSampleFails() {
        var first = record(0.137, "2026-11-01 02:00:00");
        var second = record(0.09, "2026-11-01 03:00:00");
        process(List.of(vibration), record(0.006, TIME), first);
        doThrow(new IllegalStateException("database unavailable")).doNothing()
                .when(archive).saveCollectedData(eq(vibration), eq(SN), same(second), any());

        assertThrows(IllegalStateException.class, () -> process(List.of(vibration), second));
        assertEquals(DateUtil.parse(first.getTm()), lastTimes().get(SN));
        process(List.of(vibration), second, record(1.5, "2026-11-01 04:00:00"),
                record(0.006, "2026-11-01 05:00:00"));

        verify(archive, times(2)).saveCollectedData(eq(vibration), eq(SN), same(first), any());
        verifyStored(CODE, times(7));
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, 1, -1, Double.NaN, Double.POSITIVE_INFINITY})
    void rejectsInvalidRatios(double ratio) {
        config.setSpikeRatio(ratio);
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, -1, Double.NaN, Double.POSITIVE_INFINITY})
    void rejectsInvalidRelativeMinimumsAndNoiseFloors(double value) {
        config.setRelativeMinDelta(value);
        assertThrows(IllegalArgumentException.class, config::validate);
        config.setRelativeMinDelta(0.05);
        config.setNoiseFloor(value);
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsInvalidBaselineWindowSizes(int size) {
        config.setBaselineWindowSize(size);
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    private void verifyStored(String deviceCode, org.mockito.verification.VerificationMode mode) {
        verify(archive, mode).saveCollectedData(
                argThat(device -> deviceCode.equals(device.getDeviceCode())), anyString(), any(), any());
    }

    private DeviceInfoExt device(String code, DeviceType type) {
        DeviceInfoExt device = new DeviceInfoExt();
        device.setDeviceCode(code);
        device.setDeviceType(type);
        device.setRemoteDeviceId(SN);
        return device;
    }

    private InclineHistoryResponse.InclineRecord record(double acceleration, String time) {
        InclineHistoryResponse.InclineRecord record = new InclineHistoryResponse.InclineRecord();
        record.setTm(time);
        record.setAccMaxZ(acceleration / 0.00980665);
        record.setAngleX(0.1);
        record.setAngleY(0.2);
        record.setTemp(20.0);
        record.setRunStatus(0);
        record.setAlarmStatus(0);
        return record;
    }

    private void process(List<DeviceInfoExt> devices, InclineHistoryResponse.InclineRecord... records) {
        InclineHistoryResponse.InclineHistoryData data = new InclineHistoryResponse.InclineHistoryData();
        data.setResultData(List.of(records));
        InclineHistoryResponse response = new InclineHistoryResponse();
        response.setData(data);
        ReflectionTestUtils.invokeMethod(handler, "processResponse", devices, response, SN);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Date> lastTimes() {
        return (Map<String, Date>) ReflectionTestUtils.getField(handler, "remoteDeviceIdLastDataTimeMap");
    }
}
