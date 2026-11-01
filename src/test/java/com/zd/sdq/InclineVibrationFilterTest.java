package com.zd.sdq;

import cn.hutool.core.date.DateUtil;
import com.company.cbf.starter.data.service.forward.BufferForwardMqttClientAdapter;
import com.company.cbf.starter.data.service.forward.device.DeviceType;
import com.zd.sdq.config.VibrationFilterConfig;
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
    private final BufferForwardMqttClientAdapter forward = mock(BufferForwardMqttClientAdapter.class);
    private final DeviceLatestDataCache cache = new DeviceLatestDataCache();
    private final InclineForwardDataService archive = mock(InclineForwardDataService.class);
    private final Clock clock = mock(Clock.class);
    private final InclineBaselineService baselines = new InclineBaselineService(
            mock(DeviceInfoExtMapper.class), new ObjectMapper(), null);
    private InclineDataHandler handler;
    private DeviceInfoExt vibration;

    @BeforeEach
    void setUp() {
        handler = new InclineDataHandler(null, null, null, forward, cache,
                new VibrationSpikeFilter(config, clock), archive, baselines);
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

        verify(forward).push(eq(CODE), any());
        verify(archive, never()).saveBeforeForward(eq(vibration), anyString(), same(peak), any());
        verify(forward, times(2)).push(eq(incline.getDeviceCode()), any());
        assertEquals(TIME, cache.getLatestData(CODE).getDataTime());
        assertNotNull(cache.getLatestData(incline.getDeviceCode()));
        assertEquals(DateUtil.parse(peak.getTm()), lastTimes().get(SN));

        process(List.of(vibration, incline), record(0.11, "2026-11-01 03:00:00"));
        verify(forward, times(2)).push(eq(CODE), any());
        verify(archive, never()).saveBeforeForward(eq(vibration), anyString(), same(peak), any());
        assertEquals("2026-11-01 03:00:00", cache.getLatestData(CODE).getDataTime());
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 0.2245, -0.1783, 2.0, -2.0, 6.0})
    void forwardsFirstRecordWithoutInventingAPreviousNeighbor(double acceleration) {
        process(List.of(vibration), record(acceleration, TIME));

        verify(forward).push(eq(CODE), any());
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
        verify(forward, never()).push(eq(CODE), any());
        verify(forward).push(eq(incline.getDeviceCode()), any());
    }

    @Test
    void spikePreservesLastValidValueAndLaterValidRecordsRecover() {
        process(List.of(vibration), record(0.1, TIME));
        process(List.of(vibration), record(6, "2026-11-01 02:00:00"));
        assertEquals(TIME, cache.getLatestData(CODE).getDataTime());
        process(List.of(vibration), record(-0.2, "2026-11-01 03:00:00"));

        verify(forward, times(2)).push(eq(CODE), any());
        assertEquals(-0.2, Double.parseDouble(cache.getLatestData(CODE).getLatestData().get("accMaxZ").toString()));
        assertEquals("2026-11-01 03:00:00", cache.getLatestData(CODE).getDataTime());
    }

    @Test
    void leavesOtherVibrationDevicesUnchanged() {
        DeviceInfoExt other = device("ZYXH-VIB-P01-002-03", DeviceType.VIB);
        process(List.of(other), record(0.1, TIME), record(6, "2026-11-01 02:00:00"), record(0.1, "2026-11-01 03:00:00"));
        verify(forward, times(3)).push(eq(other.getDeviceCode()), any());
    }

    @Test
    void savesExactPayloadBeforeForwardingBothChannelsAndSkipsDuplicates() {
        DeviceInfoExt incline = device("ZYXH-INC-P01-002-02", DeviceType.INC);
        InclineHistoryResponse.InclineRecord record = record(0.22451234, TIME);
        record.setAngleX(0.123456789);
        process(List.of(vibration, incline), record);

        var order = inOrder(archive, forward);
        var vibrationData = org.mockito.ArgumentCaptor.forClass(com.company.cbf.starter.data.entity.MqttData.class);
        var inclineData = org.mockito.ArgumentCaptor.forClass(com.company.cbf.starter.data.entity.MqttData.class);
        order.verify(archive).saveBeforeForward(eq(vibration), eq(SN), same(record), vibrationData.capture());
        order.verify(forward).push(eq(CODE), eq(vibrationData.getValue().getValue()));
        order.verify(archive).saveBeforeForward(eq(incline), eq(SN), same(record), inclineData.capture());
        order.verify(forward).push(eq(incline.getDeviceCode()), eq(inclineData.getValue().getValue()));
        assertTrue(vibrationData.getValue().getValue().get(0).contains("0.2245"));
        assertTrue(inclineData.getValue().getValue().get(0).contains("0.123457"));

        process(List.of(vibration, incline), record);
        verifyNoMoreInteractions(archive, forward);
    }

    @Test
    void databaseFailurePreventsForwardAndKeepsProgressForRetry() {
        InclineHistoryResponse.InclineRecord record = record(0.1, TIME);
        doThrow(new IllegalStateException("database unavailable")).doNothing()
                .when(archive).saveBeforeForward(eq(vibration), eq(SN), same(record), any());

        assertThrows(IllegalStateException.class, () -> process(List.of(vibration), record));
        verifyNoInteractions(forward);
        assertNull(cache.getLatestData(CODE));
        assertEquals(DateUtil.parse(START), lastTimes().get(SN));

        process(List.of(vibration), record);
        verify(forward).push(eq(CODE), any());
        assertEquals(DateUtil.parse(TIME), lastTimes().get(SN));
    }

    @Test
    void preservesConsecutiveHighValuesWithOriginalSampleTimes() {
        process(List.of(vibration), record(0.1, TIME), record(6, "2026-11-01 02:00:00"),
                record(6.1, "2026-11-01 03:00:00"), record(0.11, "2026-11-01 04:00:00"));
        var data = org.mockito.ArgumentCaptor.forClass(com.company.cbf.starter.data.entity.MqttData.class);
        verify(archive, times(4)).saveBeforeForward(eq(vibration), eq(SN), any(), data.capture());
        verify(forward, times(4)).push(eq(CODE), any());
        assertEquals("2026-11-01 02:00:00.000", data.getAllValues().get(1).getValue().get(0).get(0));
        assertEquals("2026-11-01 03:00:00.000", data.getAllValues().get(2).getValue().get(0).get(0));
    }

    @Test
    void preservesStepChangesAndSmallFluctuations() {
        process(List.of(vibration), record(0.1, TIME), record(6, "2026-11-01 02:00:00"),
                record(1.0, "2026-11-01 03:00:00"), record(0.1, "2026-11-01 04:00:00"),
                record(1.5, "2026-11-01 05:00:00"), record(0.1, "2026-11-01 06:00:00"));
        verify(forward, times(6)).push(eq(CODE), any());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void preservesPeaksWithReportedAlarms(int status) {
        var peak = record(6, "2026-11-01 02:00:00");
        peak.setAlarmStatus(status);
        process(List.of(vibration), record(0.1, TIME), peak, record(0.11, "2026-11-01 03:00:00"));
        verify(forward, times(3)).push(eq(CODE), any());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {1})
    void preservesPeaksWithFaultOrUnknownRunStatus(Integer status) {
        var peak = record(6, "2026-11-01 02:00:00");
        peak.setRunStatus(status);
        process(List.of(vibration), record(0.1, TIME), peak, record(0.11, "2026-11-01 03:00:00"));
        verify(forward, times(3)).push(eq(CODE), any());
    }

    @Test
    void preservesPendingPeakIfNextRecordReportsAlarm() {
        var next = record(0.11, "2026-11-01 03:00:00");
        next.setAlarmStatus(2);
        process(List.of(vibration), record(0.1, TIME), record(6, "2026-11-01 02:00:00"), next);
        verify(forward, times(3)).push(eq(CODE), any());
    }

    @Test
    void doesNotConfirmIsolationAcrossMissingOrWidelySpacedRecords() {
        var missing = record(0, "2026-11-01 03:00:00");
        missing.setAccMaxZ(null);
        process(List.of(vibration), record(0.1, TIME), record(6, "2026-11-01 02:00:00"), missing,
                record(0.11, "2026-11-01 04:00:00"), record(6, "2026-11-01 05:00:00"),
                record(0.1, "2026-11-01 07:00:00"));
        verify(forward, times(5)).push(eq(CODE), any());
    }

    @Test
    void doesNotStageAPeakWithoutARecentPreviousNeighbor() {
        process(List.of(vibration), record(0.1, TIME), record(6, "2026-11-01 03:00:00"),
                record(0.1, "2026-11-01 04:00:00"));
        verify(forward, times(3)).push(eq(CODE), any());
    }

    @Test
    void pendingPeakExpiresWithoutAnotherRecordAndCanRetryDatabaseFailure() {
        var peak = record(6, "2026-11-01 02:00:00");
        process(List.of(vibration), record(0.1, TIME), peak);
        handler.flushPendingVibrationData();
        verify(forward).push(eq(CODE), any());

        when(clock.millis()).thenReturn(config.getMaxGapSeconds() * 1000L);
        doThrow(new IllegalStateException("database unavailable")).doNothing()
                .when(archive).saveBeforeForward(eq(vibration), eq(SN), same(peak), any());
        assertThrows(IllegalStateException.class, handler::flushPendingVibrationData);
        handler.flushPendingVibrationData();
        handler.flushPendingVibrationData();
        verify(forward, times(2)).push(eq(CODE), any());
        verify(archive, times(2)).saveBeforeForward(eq(vibration), eq(SN), same(peak), any());
        assertEquals(peak.getTm(), cache.getLatestData(CODE).getDataTime());
    }

    @Test
    void rollsBackSpikeConfirmationIfForwardingTheRecoveryPointFails() {
        var next = record(0.11, "2026-11-01 03:00:00");
        var peak = record(6, "2026-11-01 02:00:00");
        process(List.of(vibration), record(0.1, TIME), peak);
        doThrow(new IllegalStateException("database unavailable")).doNothing()
                .when(archive).saveBeforeForward(eq(vibration), eq(SN), same(next), any());
        assertThrows(IllegalStateException.class, () -> process(List.of(vibration), next));
        assertEquals(DateUtil.parse(peak.getTm()), lastTimes().get(SN));
        process(List.of(vibration), next);
        verify(forward, times(2)).push(eq(CODE), any());
        verify(archive, never()).saveBeforeForward(eq(vibration), eq(SN), same(peak), any());
        assertEquals(next.getTm(), cache.getLatestData(CODE).getDataTime());
    }

    @Test
    void sortsDescendingBatchesAndKeepsWindowAcrossDuplicateQueries() {
        var peak = record(6, "2026-11-01 02:00:00");
        var next = record(0.11, "2026-11-01 03:00:00");
        process(List.of(vibration), peak, record(0.1, TIME));
        process(List.of(vibration), next, peak);
        verify(forward, times(2)).push(eq(CODE), any());
        verify(archive, never()).saveBeforeForward(eq(vibration), eq(SN), same(peak), any());
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
        verify(forward, times(2)).push(eq(CODE), any());
        verify(forward).push(eq(other.getDeviceCode()), any());
    }

    @Test
    void requestsOldestUnprocessedRecordsWithTheSuppliedTimeRange() throws Exception {
        InclineApiService api = mock(InclineApiService.class);
        when(api.getLoginToken()).thenReturn("test-token");
        var queryHandler = new InclineDataHandler(null, null, api, forward, cache,
                new VibrationSpikeFilter(config, clock), archive, baselines);
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
        process(List.of(vibration), record(0.1, "2026-11-01 03:00:00"), record(6, "2026-11-01 04:00:00"));
        verify(forward, times(4)).push(eq(CODE), any());
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
