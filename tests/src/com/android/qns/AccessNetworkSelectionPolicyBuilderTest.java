/*
 * Copyright (C) 2022 The Android Open Source Project
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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;

import android.telephony.AccessNetworkConstants;
import android.telephony.Rlog;
import android.telephony.data.ApnSetting;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

@RunWith(JUnit4.class)
public class AccessNetworkSelectionPolicyBuilderTest extends QnsTest {

    @Mock private QnsCarrierConfigManager mConfig;

    private AccessNetworkSelectionPolicyBuilder mBuilder = null;

    // static final int WLAN = AccessNetworkConstants.TRANSPORT_TYPE_WLAN;
    // static final int WWAN = AccessNetworkConstants.TRANSPORT_TYPE_WWAN;
    static final int ROVE_IN = QnsConstants.ROVE_IN;
    static final int ROVE_OUT = QnsConstants.ROVE_OUT;
    static final int IDLE = QnsConstants.CALL_TYPE_IDLE;
    static final int VOICE = QnsConstants.CALL_TYPE_VOICE;
    static final int VIDEO = QnsConstants.CALL_TYPE_VIDEO;
    static final int WIFI_PREF = QnsConstants.WIFI_PREF;
    static final int CELL_PREF = QnsConstants.CELL_PREF;
    static final int HOME = QnsConstants.COVERAGE_HOME;
    static final int ROAM = QnsConstants.COVERAGE_ROAM;
    // static final int IWLAN = AccessNetworkConstants.AccessNetworkType.IWLAN;
    // static final int GUARDING_NONE = QnsConstants.GUARDING_NONE;
    // static final int GUARDING_CELL = QnsConstants.GUARDING_CELLULAR;
    // static final int GUARDING_WIFI = QnsConstants.GUARDING_WIFI;

    /*
    static final int AVAILABILITY = QnsConstants.SIGNAL_MEASUREMENT_AVAILABILITY;
    static final int EQUAL = QnsConstants.THRESHOLD_MATCH_TYPE_EQUAL_TO;
    static final int LARGER = QnsConstants.THRESHOLD_EQUAL_OR_LARGER;
    static final int SMALLER = QnsConstants.THRESHOLD_EQUAL_OR_SMALLER;
    */
    static final int NGRAN = AccessNetworkConstants.AccessNetworkType.NGRAN;
    static final int EUTRAN = AccessNetworkConstants.AccessNetworkType.EUTRAN;
    static final int UTRAN = AccessNetworkConstants.AccessNetworkType.UTRAN;
    static final int GERAN = AccessNetworkConstants.AccessNetworkType.GERAN;
    static final int IWLAN = AccessNetworkConstants.AccessNetworkType.IWLAN;
    /*
        static final int RSSI = SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSI;
        static final int SSRSRP = SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_SSRSRP;
        static final int SSRSRQ = SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_SSRSRQ;
        static final int SSSINR = SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_SSSINR;
        static final int RSRP = SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRP;
        static final int RSRQ = SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRQ;
        static final int RSSNR = SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSNR;
        static final int RSCP = SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSCP;
        static final int ECNO = SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_SSSINR + 2;
        static final int AVAIL = QnsConstants.SIGNAL_AVAILABLE;
        static final int UNAVAIL = QnsConstants.SIGNAL_UNAVAILABLE;
        static final int GOOD = QnsConstants.POLICY_GOOD;
        static final int BAD = QnsConstants.POLICY_BAD;
        static final int TOLERABLE = QnsConstants.POLICY_TOLERABLE;
    */

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        super.setUp();
        mBuilder = new AccessNetworkSelectionPolicyBuilder(mConfig, ApnSetting.TYPE_IMS);
    }

    @After
    public void tearDown() throws Exception {}

    protected static void slog(String log) {
        Rlog.d(AccessNetworkSelectionPolicyBuilderTest.class.getSimpleName(), log);
    }

    protected String[] getPolicy(int direction, int calltype, int preference, int coverage) {
        AccessNetworkSelectionPolicy.PreCondition condition =
                new AccessNetworkSelectionPolicy.PreCondition(calltype, preference, coverage);
        return mBuilder.getPolicy(direction, condition);
    }

    @Test
    public void test_PolicyMap_Default() {
        doReturn(null).when(mConfig).getPolicy(anyInt(), any());
        doReturn(false).when(mConfig).isTransportTypeSelWithoutSSInRoamSupported();
        doReturn(false).when(mConfig).isCurrentTransportTypeInVoiceCallSupported();
        doReturn(false).when(mConfig).isChooseWfcPreferredTransportInBothBadCondition(anyInt());

        String[] conditions;

        List<Integer> callTypeList = List.of(IDLE, VOICE, VIDEO);
        List<Integer> coverageList = List.of(HOME, ROAM);

        for (int calltype : callTypeList) {
            for (int coverage : coverageList) {
                conditions = getPolicy(ROVE_IN, calltype, WIFI_PREF, coverage);
                assertTrue(conditions.length == 1 && "Condition:WIFI_GOOD".equals(conditions[0]));
            }
        }

        for (int calltype : callTypeList) {
            for (int coverage : coverageList) {
                conditions = getPolicy(ROVE_OUT, calltype, WIFI_PREF, coverage);
                assertEquals(1, conditions.length);
                assertEquals("Condition:WIFI_BAD", conditions[0]);
            }
        }

        for (int calltype : callTypeList) {
            for (int coverage : coverageList) {
                conditions = getPolicy(ROVE_IN, calltype, CELL_PREF, coverage);
                assertEquals(1, conditions.length);
                assertEquals("Condition:WIFI_GOOD,CELLULAR_BAD", conditions[0]);
            }
        }

        for (int calltype : callTypeList) {
            for (int coverage : coverageList) {
                conditions = getPolicy(ROVE_OUT, calltype, CELL_PREF, coverage);
                assertEquals(2, conditions.length);
                assertEquals("Condition:CELLULAR_GOOD", conditions[0]);
                assertEquals("Condition:WIFI_BAD,CELLULAR_TOLERABLE", conditions[1]);
            }
        }
    }

    @Test
    public void test_PolicyMap_isTransportTypeSelWithoutSSInRoamSupported() {
        doReturn(null).when(mConfig).getPolicy(anyInt(), any());
        doReturn(true).when(mConfig).isTransportTypeSelWithoutSSInRoamSupported();
        doReturn(false).when(mConfig).isCurrentTransportTypeInVoiceCallSupported();
        doReturn(false).when(mConfig).isChooseWfcPreferredTransportInBothBadCondition(anyInt());
        doReturn(false).when(mConfig).allowImsOverIwlanCellularLimitedCase();

        String[] conditions;
        List<Integer> directionList = List.of(ROVE_IN, ROVE_OUT);
        List<Integer> callTypeList = List.of(IDLE, VOICE, VIDEO);
        List<Integer> preferenceList = List.of(WIFI_PREF, CELL_PREF);

        for (int preference : preferenceList) {
            for (int callType : callTypeList) {
                for (int direction : directionList) {
                    conditions = getPolicy(direction, callType, preference, ROAM);
                    if ((preference == CELL_PREF && direction == ROVE_OUT)
                            || (preference == WIFI_PREF && direction == ROVE_IN)) {
                        assertEquals(1, conditions.length);
                        assertEquals("Condition:WIFI_AVAILABLE", conditions[0]);
                    } else {
                        assertEquals(1, conditions.length);
                        assertEquals("Condition:", conditions[0]);
                    }
                }
            }
        }
    }

    @Test
    public void test_PolicyMap_isCurrentTransportTypeInVoiceCallSupported() {
        doReturn(null).when(mConfig).getPolicy(anyInt(), any());
        doReturn(false).when(mConfig).isTransportTypeSelWithoutSSInRoamSupported();
        doReturn(true).when(mConfig).isCurrentTransportTypeInVoiceCallSupported();
        doReturn(false).when(mConfig).isChooseWfcPreferredTransportInBothBadCondition(anyInt());

        String[] conditions;
        List<Integer> coverageList = List.of(HOME, ROAM);

        for (int coverage : coverageList) {
            conditions = getPolicy(ROVE_OUT, VOICE, CELL_PREF, coverage);
            assertEquals(1, conditions.length);
            assertEquals("Condition:WIFI_BAD", conditions[0]);
        }
    }

    @Test
    public void test_PolicyMap_isRoveOutOnCellularWifiBothBadSupported() {
        doReturn(null).when(mConfig).getPolicy(anyInt(), any());
        doReturn(false).when(mConfig).isTransportTypeSelWithoutSSInRoamSupported();
        doReturn(false).when(mConfig).isCurrentTransportTypeInVoiceCallSupported();
        doReturn(true).when(mConfig).isChooseWfcPreferredTransportInBothBadCondition(CELL_PREF);

        String[] conditions;
        List<Integer> callTypeList = List.of(IDLE, VOICE, VIDEO);
        List<Integer> coverageList = List.of(HOME, ROAM);

        for (int calltype : callTypeList) {
            for (int coverage : coverageList) {
                conditions = getPolicy(ROVE_OUT, calltype, CELL_PREF, coverage);
                assertEquals(2, conditions.length);
                assertEquals("Condition:WIFI_BAD", conditions[0]);
                assertEquals("Condition:CELLULAR_GOOD", conditions[1]);
            }
        }
    }

    @Test
    public void test_PolicyMap_isRoveInOnCellularWifiBothBadSupported() {
        doReturn(null).when(mConfig).getPolicy(anyInt(), any());
        doReturn(false).when(mConfig).isTransportTypeSelWithoutSSInRoamSupported();
        doReturn(false).when(mConfig).isCurrentTransportTypeInVoiceCallSupported();
        doReturn(true).when(mConfig).isChooseWfcPreferredTransportInBothBadCondition(WIFI_PREF);

        String[] conditions;
        List<Integer> callTypeList = List.of(IDLE, VOICE, VIDEO);
        List<Integer> coverageList = List.of(HOME, ROAM);

        for (int calltype : callTypeList) {
            for (int coverage : coverageList) {
                conditions = getPolicy(ROVE_IN, calltype, WIFI_PREF, coverage);
                assertEquals(2, conditions.length);
                assertEquals("Condition:WIFI_GOOD", conditions[0]);
                assertEquals("Condition:CELLULAR_BAD", conditions[1]);
            }
        }
    }

    @Test
    public void test_PolicyMap_allowImsOverIwlanCellularLimitedCase() {
        doReturn(null).when(mConfig).getPolicy(anyInt(), any());
        doReturn(true).when(mConfig).isTransportTypeSelWithoutSSInRoamSupported();
        doReturn(false).when(mConfig).isCurrentTransportTypeInVoiceCallSupported();
        doReturn(false).when(mConfig).isChooseWfcPreferredTransportInBothBadCondition(anyInt());
        doReturn(true).when(mConfig).allowImsOverIwlanCellularLimitedCase();
        doReturn(true).when(mConfig).isAccessNetworkAllowed(NGRAN, ApnSetting.TYPE_IMS);
        doReturn(true).when(mConfig).isAccessNetworkAllowed(EUTRAN, ApnSetting.TYPE_IMS);
        doReturn(false).when(mConfig).isAccessNetworkAllowed(UTRAN, ApnSetting.TYPE_IMS);
        doReturn(false).when(mConfig).isAccessNetworkAllowed(GERAN, ApnSetting.TYPE_IMS);
        doReturn(false).when(mConfig).isAccessNetworkAllowed(IWLAN, ApnSetting.TYPE_IMS);

        String[] conditions;
        List<Integer> directionList = List.of(ROVE_IN, ROVE_OUT);
        List<Integer> callTypeList = List.of(IDLE, VOICE, VIDEO);

        for (int callType : callTypeList) {
            for (int direction : directionList) {
                int preference = WIFI_PREF;
                conditions = getPolicy(direction, callType, preference, ROAM);
                if (direction == ROVE_IN) {
                    assertEquals(4, conditions.length);
                    assertEquals("Condition:WIFI_AVAILABLE,NGRAN_AVAILABLE", conditions[0]);
                    assertEquals("Condition:WIFI_AVAILABLE,EUTRAN_AVAILABLE", conditions[1]);
                    assertEquals("Condition:WIFI_AVAILABLE,UTRAN_AVAILABLE", conditions[2]);
                    assertEquals("Condition:WIFI_AVAILABLE,GERAN_AVAILABLE", conditions[3]);
                } else {
                    assertEquals(0, conditions.length);
                }
                preference = CELL_PREF;
                conditions = getPolicy(direction, callType, preference, ROAM);
                if (direction == ROVE_IN) {
                    assertEquals(2, conditions.length);
                    assertEquals("Condition:WIFI_AVAILABLE,UTRAN_AVAILABLE", conditions[0]);
                    assertEquals("Condition:WIFI_AVAILABLE,GERAN_AVAILABLE", conditions[1]);
                } else {
                    assertEquals(2, conditions.length);
                    assertEquals("Condition:WIFI_AVAILABLE,NGRAN_AVAILABLE", conditions[0]);
                    assertEquals("Condition:WIFI_AVAILABLE,EUTRAN_AVAILABLE", conditions[1]);
                }
            }
        }
    }
}
