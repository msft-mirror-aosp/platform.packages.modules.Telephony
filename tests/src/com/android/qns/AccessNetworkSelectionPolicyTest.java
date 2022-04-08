/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.qns;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import android.telephony.AccessNetworkConstants;
import android.telephony.SignalThresholdInfo;
import android.telephony.data.ApnSetting;

import com.android.qns.AccessNetworkSelectionPolicy.PreCondition;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@RunWith(JUnit4.class)
public class AccessNetworkSelectionPolicyTest extends QnsTest {
    int apnType = ApnSetting.TYPE_IMS;
    int targetTransportType = AccessNetworkConstants.TRANSPORT_TYPE_WWAN;
    PreCondition preCondition = new PreCondition(
            QnsConstants.CALL_TYPE_IDLE, QnsConstants.CELL_PREF, QnsConstants.COVERAGE_HOME);
    List<Threshold> ths = new ArrayList<>();
    List<ThresholdGroup> thgroups = new ArrayList<>();
    @Mock private WifiQualityMonitor mockWifiQualityMonitor;
    @Mock private CellularQualityMonitor mockCellularQualityMonitor;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        super.setUp();
    }

    @Test
    public void testCanHandleApnType() {
        AccessNetworkSelectionPolicy ansp =
                new AccessNetworkSelectionPolicy(
                        apnType, targetTransportType, preCondition, thgroups);
        assertTrue(ansp.canHandleApnType(ApnSetting.TYPE_IMS));
        assertFalse(ansp.canHandleApnType(ApnSetting.TYPE_EMERGENCY));
        assertFalse(ansp.canHandleApnType(ApnSetting.TYPE_XCAP));
        assertFalse(ansp.canHandleApnType(ApnSetting.TYPE_MMS));
    }

    @Test
    public void testGetTargetTransportType() {
        AccessNetworkSelectionPolicy ansp =
                new AccessNetworkSelectionPolicy(
                        apnType, targetTransportType, preCondition, thgroups);
        assertEquals(targetTransportType, ansp.getTargetTransportType());
        assertNotEquals(AccessNetworkConstants.TRANSPORT_TYPE_WLAN, ansp.getTargetTransportType());
    }

    @Test
    public void testSatisfyPrecondition() {
        AccessNetworkSelectionPolicy ansp =
                new AccessNetworkSelectionPolicy(
                        apnType, targetTransportType, preCondition, thgroups);
        assertTrue(
                ansp.satisfyPrecondition(
                        new PreCondition(
                                QnsConstants.CALL_TYPE_IDLE,
                                QnsConstants.CELL_PREF,
                                QnsConstants.COVERAGE_HOME)));
        assertFalse(
                ansp.satisfyPrecondition(
                        new PreCondition(
                                QnsConstants.CALL_TYPE_VOICE,
                                QnsConstants.CELL_PREF,
                                QnsConstants.COVERAGE_HOME)));
        assertFalse(
                ansp.satisfyPrecondition(
                        new PreCondition(
                                QnsConstants.CALL_TYPE_IDLE,
                                QnsConstants.CELL_PREF,
                                QnsConstants.COVERAGE_ROAM)));
        assertFalse(
                ansp.satisfyPrecondition(
                        new PreCondition(
                                QnsConstants.CALL_TYPE_IDLE,
                                QnsConstants.WIFI_ONLY,
                                QnsConstants.COVERAGE_HOME)));
        assertFalse(
                ansp.satisfyPrecondition(
                        new PreCondition(
                                QnsConstants.CALL_TYPE_IDLE,
                                QnsConstants.WIFI_PREF,
                                QnsConstants.COVERAGE_HOME)));
    }

    @Test
    public void testSatisfiedByThreshold_thresholdgroup() {
        List<Threshold> ths = new ArrayList<>();
        ths.add(
                new Threshold(
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRP,
                        -117,
                        QnsConstants.THRESHOLD_EQUAL_OR_SMALLER,
                        QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER));
        ths.add(
                new Threshold(
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRQ,
                        -13,
                        QnsConstants.THRESHOLD_EQUAL_OR_LARGER,
                        QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER));
        thgroups.add(new ThresholdGroup(ths));

        when(mockCellularQualityMonitor.getCurrentQuality(
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRP))
                .thenReturn(-120);
        when(mockCellularQualityMonitor.getCurrentQuality(
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRQ))
                .thenReturn(-15)
                .thenReturn(-10);

        AccessNetworkSelectionPolicy ansp =
                new AccessNetworkSelectionPolicy(
                        apnType, targetTransportType, preCondition, thgroups);

        boolean result =
                ansp.satisfiedByThreshold(
                        mockWifiQualityMonitor,
                        mockCellularQualityMonitor,
                        false,
                        true,
                        AccessNetworkConstants.AccessNetworkType.EUTRAN);
        assertFalse(result);

        result =
                ansp.satisfiedByThreshold(
                        mockWifiQualityMonitor,
                        mockCellularQualityMonitor,
                        false,
                        true,
                        AccessNetworkConstants.AccessNetworkType.EUTRAN);
        assertTrue(result);
    }

    @Test
    public void testFindUnmatchedThresholds() {
        targetTransportType = AccessNetworkConstants.TRANSPORT_TYPE_WLAN;
        int expected = 3;
        thgroups = generateTestThresholdGroups();
        when(mockCellularQualityMonitor.getCurrentQuality(
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRP))
                .thenReturn(-90);
        when(mockCellularQualityMonitor.getCurrentQuality(
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRQ))
                .thenReturn(-15);
        when(mockCellularQualityMonitor.getCurrentQuality(
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSNR))
                .thenReturn(-5);
        when(mockWifiQualityMonitor.getCurrentQuality(
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSI))
                .thenReturn(-80);
        AccessNetworkSelectionPolicy ansp =
                new AccessNetworkSelectionPolicy(
                        apnType, targetTransportType, preCondition, thgroups);
        List<Threshold> unmatched =
                ansp.findUnmatchedThresholds(mockWifiQualityMonitor, mockCellularQualityMonitor);
        assertEquals(expected, unmatched.size());
    }

    private List<ThresholdGroup> generateTestThresholdGroups() {
        List<ThresholdGroup> thgroups = new ArrayList<>();
        List<Threshold> ths = new ArrayList<>();
        ths.add(
                new Threshold(
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRP,
                        -117,
                        QnsConstants.THRESHOLD_EQUAL_OR_SMALLER,
                        QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER));
        ths.add(
                new Threshold(
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSNR,
                        -15,
                        QnsConstants.THRESHOLD_EQUAL_OR_LARGER,
                        QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER));
        ths.add(
                new Threshold(
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSI,
                        -65,
                        QnsConstants.THRESHOLD_EQUAL_OR_LARGER,
                        QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER));
        thgroups.add(new ThresholdGroup(ths));

        ths = new ArrayList<>();
        ths.add(
                new Threshold(
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRP,
                        -91,
                        QnsConstants.THRESHOLD_EQUAL_OR_SMALLER,
                        QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER));
        ths.add(
                new Threshold(
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSNR,
                        -1,
                        QnsConstants.THRESHOLD_EQUAL_OR_SMALLER,
                        QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER));
        ths.add(
                new Threshold(
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSI,
                        -85,
                        QnsConstants.THRESHOLD_EQUAL_OR_LARGER,
                        QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER));
        thgroups.add(new ThresholdGroup(ths));
        return thgroups;
    }

    @After
    public void tearDown() {
        ths.clear();
        thgroups.clear();
    }
}
