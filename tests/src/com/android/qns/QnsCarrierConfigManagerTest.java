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

import static android.hardware.radio.network.SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSCP;
import static android.hardware.radio.network.SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRQ;
import static android.hardware.radio.network.SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSI;
import static android.hardware.radio.network.SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSNR;
import static android.hardware.radio.network.SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_SSRSRP;
import static android.hardware.radio.network.SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_SSRSRQ;
import static android.telephony.AccessNetworkConstants.TRANSPORT_TYPE_WLAN;
import static android.telephony.AccessNetworkConstants.TRANSPORT_TYPE_WWAN;
import static android.telephony.SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRP;
import static android.telephony.SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_SSSINR;

import static com.android.qns.QnsConstants.CALL_TYPE_IDLE;
import static com.android.qns.QnsConstants.CALL_TYPE_VIDEO;
import static com.android.qns.QnsConstants.CALL_TYPE_VOICE;
import static com.android.qns.QnsConstants.CELL_PREF;
import static com.android.qns.QnsConstants.WIFI_ONLY;
import static com.android.qns.QnsConstants.WIFI_PREF;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.radio.V1_5.ApnTypes;
import android.net.ConnectivityManager;
import android.os.PersistableBundle;
import android.os.test.TestLooper;
import android.telephony.AccessNetworkConstants;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.data.ApnSetting;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;

@RunWith(JUnit4.class)
public class QnsCarrierConfigManagerTest {
    private static final String HANDOVER_POLICY_0 =
            "source=EUTRAN, target=IWLAN, type=allowed, capabilities=IMS|eims|MMS|cbs|xcap";
    private static final String HANDOVER_POLICY_1 =
            "source=GERAN|UTRAN|NGRAN, target=IWLAN, type=disallowed,"
                + " capabilities=IMS|MMS|cbs|xcap";
    private static final String HANDOVER_POLICY_2 =
            "source=IWLAN, target=GERAN|UTRAN|EUTRAN|NGRAN, roaming=true, type=disallowed,"
                + " capabilities=IMS";
    private static final String HANDOVER_POLICY_3 =
            "source=EUTRAN, target=IWLAN, type=allowed, capabilities=ims|eims|mms|CBS|XCAP";
    private static final String HANDOVER_POLICY_4 =
            "source=EUTRAN, target=IWLAN, type=disallowed, capabilities=IMS|EIMS|MMS|cbs|xcap";
    private static final String FALLBACK_RULE0 = "cause=321~378|1503, time=60000, preference=cell";
    private static final String FALLBACK_RULE1 = "cause=232|267|350~380|1503, time=90000";

    @Mock private Context mContext;
    @Mock private CarrierConfigManager carrierConfigManager;
    @Mock private ConnectivityManager mCm;
    @Mock private TelephonyManager mTelephonyManager;
    @Mock private SubscriptionManager mSubscriptionManager;
    @Mock private SubscriptionInfo mSubscriptionInfo;

    protected QnsCarrierConfigManager mConfigManager;

    // To set & validate QNS  Default Value
    @Before
    public void setup() throws IOException {
        MockitoAnnotations.initMocks(this);
        mContext = spy(ApplicationProvider.getApplicationContext());
        when(mContext.getSystemService(CarrierConfigManager.class))
                .thenReturn(carrierConfigManager);
        when(mContext.getSystemService(TelephonyManager.class)).thenReturn(mTelephonyManager);
        when(mContext.getSystemService(SubscriptionManager.class)).thenReturn(mSubscriptionManager);
        when(mContext.getSystemService(ConnectivityManager.class)).thenReturn(mCm);
        TestLooper looper = new TestLooper();
        when(mContext.getMainLooper()).thenReturn(looper.getLooper());
        when(mContext.checkPermission(anyString(), anyInt(), anyInt()))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        when(carrierConfigManager.getConfigForSubId(anyInt())).thenReturn(null);
        when(mTelephonyManager.createForSubscriptionId(anyInt())).thenReturn(mTelephonyManager);
        when(mTelephonyManager.getSimCarrierId()).thenReturn(0);
        doReturn(mSubscriptionInfo, mSubscriptionInfo, null)
                .when(mSubscriptionManager)
                .getActiveSubscriptionInfoForSimSlotIndex(0);
        when(mSubscriptionManager.getActiveSubscriptionInfoForSimSlotIndex(0))
                .thenReturn(mSubscriptionInfo);
        when(mSubscriptionInfo.getSubscriptionId()).thenReturn(0);
        mConfigManager = QnsCarrierConfigManager.getInstance(mContext, 0);
        mConfigManager.loadQnsConfigurations();
    }

    @Test
    public void testAllowWFCOnAirplaneModeOnWithDefaultValues() {
        boolean isWfcOnAirplaneModeAllowed;

        isWfcOnAirplaneModeAllowed = mConfigManager.allowWFCOnAirplaneModeOn();
        Assert.assertTrue(isWfcOnAirplaneModeAllowed);
    }

    @Test
    public void testIsInCallHoDecisionWlanToWwanWithoutVopsCondition() {
        // Test for the default setting
        assertFalse(mConfigManager.isInCallHoDecisionWlanToWwanWithoutVopsCondition());

        // Test for a new setting
        PersistableBundle bundle = new PersistableBundle();
        bundle.putBoolean(
                QnsCarrierConfigManager
                        .KEY_IN_CALL_HO_DECISION_WLAN_TO_WWAN_WITHOUT_VOPS_CONDITION_BOOL,
                true);
        mConfigManager.loadQnsAneSupportConfigurations(null, bundle);
        assertTrue(mConfigManager.isInCallHoDecisionWlanToWwanWithoutVopsCondition());
    }

    @Test
    public void testIsAccessNetworkAllowedWithDefaultValues() {
        boolean isAccessNetworkAllowedForRat;

        isAccessNetworkAllowedForRat =
                mConfigManager.isAccessNetworkAllowed(
                        AccessNetworkConstants.AccessNetworkType.EUTRAN, ApnTypes.IMS);
        Assert.assertTrue(isAccessNetworkAllowedForRat);

        isAccessNetworkAllowedForRat =
                mConfigManager.isAccessNetworkAllowed(
                        AccessNetworkConstants.AccessNetworkType.NGRAN, ApnTypes.IMS);
        Assert.assertTrue(isAccessNetworkAllowedForRat);

        isAccessNetworkAllowedForRat =
                mConfigManager.isAccessNetworkAllowed(
                        AccessNetworkConstants.AccessNetworkType.EUTRAN, ApnTypes.EMERGENCY);
        Assert.assertFalse(isAccessNetworkAllowedForRat);

        isAccessNetworkAllowedForRat =
                mConfigManager.isAccessNetworkAllowed(
                        AccessNetworkConstants.AccessNetworkType.NGRAN, ApnTypes.EMERGENCY);
        Assert.assertFalse(isAccessNetworkAllowedForRat);

        isAccessNetworkAllowedForRat =
                mConfigManager.isAccessNetworkAllowed(
                        AccessNetworkConstants.AccessNetworkType.GERAN, ApnTypes.IMS);
        Assert.assertFalse(isAccessNetworkAllowedForRat);

        isAccessNetworkAllowedForRat =
                mConfigManager.isAccessNetworkAllowed(
                        AccessNetworkConstants.AccessNetworkType.UTRAN, ApnTypes.MMS);
        Assert.assertFalse(isAccessNetworkAllowedForRat);
    }

    @Test
    public void testIsServiceBarringCheckSupportedWithDefaultValues() {
        boolean isServiceBarringCheck;

        isServiceBarringCheck = mConfigManager.isServiceBarringCheckSupported();
        Assert.assertFalse(isServiceBarringCheck);
    }

    @Test
    public void testIsGuardTimerHystersisOnPrefSupportedWithDefaultValues() {
        boolean isGuardTimerHystersisOn;

        isGuardTimerHystersisOn = mConfigManager.isGuardTimerHystersisOnPrefSupported();
        Assert.assertFalse(isGuardTimerHystersisOn);
    }

    @Test
    public void testIsHysteresisTimerEnabled() {
        boolean guardTimerEnabled;

        guardTimerEnabled = mConfigManager.isHysteresisTimerEnabled(QnsConstants.COVERAGE_HOME);
        Assert.assertTrue(guardTimerEnabled);
        guardTimerEnabled = mConfigManager.isHysteresisTimerEnabled(QnsConstants.COVERAGE_ROAM);
        Assert.assertTrue(guardTimerEnabled);
    }

    @Test
    public void testIsTransportTypeSelWithoutSSInRoamSupportedWithDefaultValues() {
        boolean isTransportTypeInRoamWithoutSS;

        isTransportTypeInRoamWithoutSS =
                mConfigManager.isTransportTypeSelWithoutSSInRoamSupported();
        Assert.assertFalse(isTransportTypeInRoamWithoutSS);
    }

    @Test
    public void testIsChooseWfcPreferredTransportInBothBadConditionWithDefaultValues() {
        boolean isChooseWfcPreferredTransport;

        isChooseWfcPreferredTransport =
                mConfigManager.isChooseWfcPreferredTransportInBothBadCondition(
                        QnsConstants.WIFI_PREF);
        Assert.assertFalse(isChooseWfcPreferredTransport);

        isChooseWfcPreferredTransport =
                mConfigManager.isChooseWfcPreferredTransportInBothBadCondition(
                        QnsConstants.CELL_PREF);
        Assert.assertFalse(isChooseWfcPreferredTransport);
    }

    @Test
    public void testIsOverrideImsPreferenceSupportedWithDefaultValues() {
        boolean isOverrideIMSPreferenceEnabled;

        isOverrideIMSPreferenceEnabled = mConfigManager.isOverrideImsPreferenceSupported();
        Assert.assertFalse(isOverrideIMSPreferenceEnabled);
    }

    @Test
    public void testIsCurrentTransportTypeInVoiceCallSupportedWithDefaultValues() {
        boolean isCurrTransportTypeInVoiceCall;

        isCurrTransportTypeInVoiceCall =
                mConfigManager.isCurrentTransportTypeInVoiceCallSupported();
        Assert.assertFalse(isCurrTransportTypeInVoiceCall);
    }

    @Test
    public void testIsRoveOutWithWiFiLowQualityAtGuardingTimeWithDefaultValues() {
        boolean isRoveOutPoliciesForGuardTimerSupported;

        isRoveOutPoliciesForGuardTimerSupported =
                mConfigManager.isRoveOutWithWiFiLowQualityAtGuardingTime();
        Assert.assertFalse(isRoveOutPoliciesForGuardTimerSupported);
    }

    @Test
    public void testGetThresholdGapWithGuardTimerWithDefaultValues() {

        testEutranGuardTimerThresholdGapOffset();
        testNgranGuardTimerThresholdGapOffset();
        testUtranGuardTimerThresholdGapOffset();
        testGeranGuardTimerThresholdGapOffset();
        testIwlanGuardTimerThresholdGapOffset();
    }

    private void testEutranGuardTimerThresholdGapOffset() {
        loadAndValidateForGuardTImerThresholdGapOffset(
                AccessNetworkConstants.AccessNetworkType.EUTRAN, SIGNAL_MEASUREMENT_TYPE_RSRP);
        loadAndValidateForGuardTImerThresholdGapOffset(
                AccessNetworkConstants.AccessNetworkType.EUTRAN, SIGNAL_MEASUREMENT_TYPE_RSRP);
        loadAndValidateForGuardTImerThresholdGapOffset(
                AccessNetworkConstants.AccessNetworkType.EUTRAN, SIGNAL_MEASUREMENT_TYPE_RSRP);
        loadAndValidateForGuardTImerThresholdGapOffset(
                AccessNetworkConstants.AccessNetworkType.EUTRAN, SIGNAL_MEASUREMENT_TYPE_RSRQ);
        loadAndValidateForGuardTImerThresholdGapOffset(
                AccessNetworkConstants.AccessNetworkType.EUTRAN, SIGNAL_MEASUREMENT_TYPE_RSRQ);
        loadAndValidateForGuardTImerThresholdGapOffset(
                AccessNetworkConstants.AccessNetworkType.EUTRAN, SIGNAL_MEASUREMENT_TYPE_RSRQ);
        loadAndValidateForGuardTImerThresholdGapOffset(
                AccessNetworkConstants.AccessNetworkType.EUTRAN, SIGNAL_MEASUREMENT_TYPE_RSSNR);
        loadAndValidateForGuardTImerThresholdGapOffset(
                AccessNetworkConstants.AccessNetworkType.EUTRAN, SIGNAL_MEASUREMENT_TYPE_RSSNR);
        loadAndValidateForGuardTImerThresholdGapOffset(
                AccessNetworkConstants.AccessNetworkType.EUTRAN, SIGNAL_MEASUREMENT_TYPE_RSSNR);
    }

    private void testNgranGuardTimerThresholdGapOffset() {
        loadAndValidateForGuardTImerThresholdGapOffset(
                AccessNetworkConstants.AccessNetworkType.NGRAN, SIGNAL_MEASUREMENT_TYPE_SSRSRP);
        loadAndValidateForGuardTImerThresholdGapOffset(
                AccessNetworkConstants.AccessNetworkType.NGRAN, SIGNAL_MEASUREMENT_TYPE_SSRSRP);
        loadAndValidateForGuardTImerThresholdGapOffset(
                AccessNetworkConstants.AccessNetworkType.NGRAN, SIGNAL_MEASUREMENT_TYPE_SSRSRP);
        loadAndValidateForGuardTImerThresholdGapOffset(
                AccessNetworkConstants.AccessNetworkType.NGRAN, SIGNAL_MEASUREMENT_TYPE_SSRSRQ);
        loadAndValidateForGuardTImerThresholdGapOffset(
                AccessNetworkConstants.AccessNetworkType.NGRAN, SIGNAL_MEASUREMENT_TYPE_SSRSRQ);
        loadAndValidateForGuardTImerThresholdGapOffset(
                AccessNetworkConstants.AccessNetworkType.NGRAN, SIGNAL_MEASUREMENT_TYPE_SSRSRQ);
        loadAndValidateForGuardTImerThresholdGapOffset(
                AccessNetworkConstants.AccessNetworkType.NGRAN, SIGNAL_MEASUREMENT_TYPE_SSSINR);
        loadAndValidateForGuardTImerThresholdGapOffset(
                AccessNetworkConstants.AccessNetworkType.NGRAN, SIGNAL_MEASUREMENT_TYPE_SSSINR);
        loadAndValidateForGuardTImerThresholdGapOffset(
                AccessNetworkConstants.AccessNetworkType.NGRAN, SIGNAL_MEASUREMENT_TYPE_SSSINR);
    }

    private void testUtranGuardTimerThresholdGapOffset() {
        loadAndValidateForGuardTImerThresholdGapOffset(
                AccessNetworkConstants.AccessNetworkType.UTRAN, SIGNAL_MEASUREMENT_TYPE_RSCP);
        loadAndValidateForGuardTImerThresholdGapOffset(
                AccessNetworkConstants.AccessNetworkType.UTRAN, SIGNAL_MEASUREMENT_TYPE_RSCP);
        loadAndValidateForGuardTImerThresholdGapOffset(
                AccessNetworkConstants.AccessNetworkType.UTRAN, SIGNAL_MEASUREMENT_TYPE_RSCP);
    }

    private void testGeranGuardTimerThresholdGapOffset() {
        loadAndValidateForGuardTImerThresholdGapOffset(
                AccessNetworkConstants.AccessNetworkType.GERAN, SIGNAL_MEASUREMENT_TYPE_RSSI);
        loadAndValidateForGuardTImerThresholdGapOffset(
                AccessNetworkConstants.AccessNetworkType.GERAN, SIGNAL_MEASUREMENT_TYPE_RSSI);
        loadAndValidateForGuardTImerThresholdGapOffset(
                AccessNetworkConstants.AccessNetworkType.GERAN, SIGNAL_MEASUREMENT_TYPE_RSSI);
    }

    private void testIwlanGuardTimerThresholdGapOffset() {
        loadAndValidateForGuardTImerThresholdGapOffset(
                AccessNetworkConstants.AccessNetworkType.IWLAN, SIGNAL_MEASUREMENT_TYPE_RSSI);
        loadAndValidateForGuardTImerThresholdGapOffset(
                AccessNetworkConstants.AccessNetworkType.IWLAN, SIGNAL_MEASUREMENT_TYPE_RSSI);
        loadAndValidateForGuardTImerThresholdGapOffset(
                AccessNetworkConstants.AccessNetworkType.IWLAN, SIGNAL_MEASUREMENT_TYPE_RSSI);
    }

    private void loadAndValidateForGuardTImerThresholdGapOffset(
            @AccessNetworkConstants.RadioAccessNetworkType int an, int measurementType) {

        int thresholdGapOffsetForGuardTimers =
                mConfigManager.getThresholdGapWithGuardTimer(an, measurementType);

        Assert.assertEquals(QnsConstants.KEY_DEFAULT_VALUE, thresholdGapOffsetForGuardTimers);
    }

    @Test
    public void testHasThresholdGapWithGuardTimerWithDefaultValue() {
        assertFalse(mConfigManager.hasThresholdGapWithGuardTimer());
    }

    @Test
    public void testHasThresholdGapWithGuardTimerWithTestbundle() {
        PersistableBundle bundle = new PersistableBundle();
        bundle.putStringArray(
                QnsCarrierConfigManager.KEY_QNS_ROVEIN_THRESHOLD_GAP_WITH_GUARD_TIMER_STRING_ARRAY,
                new String[] {"eutran:rsrp:-2"});
        mConfigManager.loadQnsAneSupportConfigurations(bundle, null);
        assertTrue(mConfigManager.hasThresholdGapWithGuardTimer());
    }

    @Test
    public void testGetQnsSupportedApnTypesWithDefaultValues() {
        Assert.assertEquals(ApnSetting.TYPE_IMS, mConfigManager.getQnsSupportedApnTypes());
    }

    @Test
    public void testGetQnsSupportedApnTypesWithTestBundle() {
        PersistableBundle bundle = new PersistableBundle();
        bundle.putInt(
                QnsCarrierConfigManager.KEY_QNS_SOS_TRANSPORT_TYPE_INT,
                QnsConstants.TRANSPORT_TYPE_ALLOWED_IWLAN);
        mConfigManager.loadQnsAneSupportConfigurations(bundle, null);
        Assert.assertEquals(
                ApnSetting.TYPE_IMS | ApnSetting.TYPE_EMERGENCY,
                mConfigManager.getQnsSupportedApnTypes());
        bundle.putInt(
                QnsCarrierConfigManager.KEY_QNS_MMS_TRANSPORT_TYPE_INT,
                QnsConstants.TRANSPORT_TYPE_ALLOWED_IWLAN);
        bundle.putInt(
                QnsCarrierConfigManager.KEY_QNS_XCAP_TRANSPORT_TYPE_INT,
                QnsConstants.TRANSPORT_TYPE_ALLOWED_BOTH);
        bundle.putInt(
                QnsCarrierConfigManager.KEY_QNS_CBS_TRANSPORT_TYPE_INT,
                QnsConstants.TRANSPORT_TYPE_ALLOWED_IWLAN);
        mConfigManager.loadQnsAneSupportConfigurations(bundle, null);
        Assert.assertEquals(
                ApnSetting.TYPE_IMS
                        | ApnSetting.TYPE_EMERGENCY
                        | ApnSetting.TYPE_MMS
                        | ApnSetting.TYPE_XCAP
                        | ApnSetting.TYPE_CBS,
                mConfigManager.getQnsSupportedApnTypes());
    }

    @Test
    public void TestAllowImsOverIwlanCellularLimitedCaseWithDefaultValues() {
        assertFalse(mConfigManager.allowImsOverIwlanCellularLimitedCase());
    }

    @Test
    public void TestAllowImsOverIwlanCellularLimitedCaseWithTestBundle() {
        PersistableBundle bundle = new PersistableBundle();
        bundle.putBoolean(
                QnsCarrierConfigManager.KEY_QNS_ALLOW_IMS_OVER_IWLAN_CELLULAR_LIMITED_CASE_BOOL,
                true);
        mConfigManager.loadQnsAneSupportConfigurations(null, bundle);
        assertTrue(mConfigManager.allowImsOverIwlanCellularLimitedCase());
    }

    @Test
    public void testGetQnsMaxIwlanHoCountDuringCallWithDefaultValues() {
        int iwlanHoCountDuringCall;

        iwlanHoCountDuringCall = mConfigManager.getQnsMaxIwlanHoCountDuringCall();
        Assert.assertEquals(QnsConstants.MAX_COUNT_INVALID, iwlanHoCountDuringCall);
    }

    @Test
    public void testGetQnsIwlanHoRestrictReasonWithDefaultValues() {
        int iwlanHoFallbackReasonSupported;

        iwlanHoFallbackReasonSupported = mConfigManager.getQnsIwlanHoRestrictReason();
        Assert.assertEquals(QnsConstants.FALLBACK_REASON_INVALID, iwlanHoFallbackReasonSupported);
    }

    @Test
    public void testGetWaitingTimeForPreferredTransportOnPowerOnWithDefaultValues() {
        int waitingTimer;

        waitingTimer =
                mConfigManager.getWaitingTimerForPreferredTransportOnPowerOn(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        Assert.assertEquals(QnsConstants.KEY_DEFAULT_VALUE, waitingTimer);

        waitingTimer =
                mConfigManager.getWaitingTimerForPreferredTransportOnPowerOn(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
        Assert.assertEquals(QnsConstants.KEY_DEFAULT_VALUE, waitingTimer);
    }

    @Test
    public void testGetWIFIRssiBackHaulTimerWithDefaultValues() {
        int wifiBackhaulDefaultTimer;

        wifiBackhaulDefaultTimer = mConfigManager.getWIFIRssiBackHaulTimer();
        Assert.assertEquals(QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER, wifiBackhaulDefaultTimer);
        Assert.assertNotEquals(QnsConstants.KEY_DEFAULT_VALUE, wifiBackhaulDefaultTimer);
    }

    @Test
    public void testGetCellularSSBackHaulTimerWithDefaultValues() {
        int CellularBackhaulDefaultTimer;

        CellularBackhaulDefaultTimer = mConfigManager.getCellularSSBackHaulTimer();
        Assert.assertEquals(QnsConstants.KEY_DEFAULT_VALUE, CellularBackhaulDefaultTimer);
        Assert.assertNotEquals(
                QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER, CellularBackhaulDefaultTimer);
    }

    @Test
    public void testGetHoRestrictedTimeOnLowRtpQualityWithDefaultValues() {
        int hoRestrictTime;

        hoRestrictTime = mConfigManager.getHoRestrictedTimeOnLowRTPQuality(TRANSPORT_TYPE_WLAN);
        Assert.assertEquals(
                QnsConstants.KEY_DEFAULT_IWLAN_AVOID_TIME_LOW_RTP_QUALITY_MILLIS, hoRestrictTime);
        Assert.assertNotEquals(QnsConstants.KEY_DEFAULT_VALUE, hoRestrictTime);
        hoRestrictTime = mConfigManager.getHoRestrictedTimeOnLowRTPQuality(TRANSPORT_TYPE_WWAN);
        Assert.assertEquals(QnsConstants.KEY_DEFAULT_VALUE, hoRestrictTime);
        Assert.assertNotEquals(
                QnsConstants.KEY_DEFAULT_IWLAN_AVOID_TIME_LOW_RTP_QUALITY_MILLIS, hoRestrictTime);
    }

    @Test
    public void testGetRatPreference() {
        int ratPreference;

        ratPreference = mConfigManager.getRatPreference(ApnTypes.IMS);
        Assert.assertEquals(QnsConstants.RAT_PREFERENCE_DEFAULT, ratPreference);
        ratPreference = mConfigManager.getRatPreference(ApnTypes.EMERGENCY);
        Assert.assertEquals(QnsConstants.RAT_PREFERENCE_DEFAULT, ratPreference);
        ratPreference = mConfigManager.getRatPreference(ApnTypes.MMS);
        Assert.assertEquals(QnsConstants.RAT_PREFERENCE_DEFAULT, ratPreference);
        ratPreference = mConfigManager.getRatPreference(ApnTypes.XCAP);
        Assert.assertEquals(QnsConstants.RAT_PREFERENCE_DEFAULT, ratPreference);
        ratPreference = mConfigManager.getRatPreference(ApnTypes.CBS);
        Assert.assertEquals(QnsConstants.RAT_PREFERENCE_DEFAULT, ratPreference);
    }

    @Test
    public void testGetQnsSupportedTransportTypeWithDefaultValues() {
        int qnsTransportType;
        qnsTransportType = mConfigManager.getQnsSupportedTransportType(ApnTypes.IMS);
        Assert.assertEquals(QnsConstants.TRANSPORT_TYPE_ALLOWED_BOTH, qnsTransportType);
        qnsTransportType = mConfigManager.getQnsSupportedTransportType(ApnTypes.MMS);
        Assert.assertEquals(QnsConstants.TRANSPORT_TYPE_ALLOWED_WWAN, qnsTransportType);
        qnsTransportType = mConfigManager.getQnsSupportedTransportType(ApnTypes.XCAP);
        Assert.assertEquals(QnsConstants.TRANSPORT_TYPE_ALLOWED_WWAN, qnsTransportType);
        qnsTransportType = mConfigManager.getQnsSupportedTransportType(ApnTypes.EMERGENCY);
        Assert.assertEquals(QnsConstants.TRANSPORT_TYPE_ALLOWED_WWAN, qnsTransportType);
        qnsTransportType = mConfigManager.getQnsSupportedTransportType(ApnTypes.CBS);
        Assert.assertEquals(QnsConstants.TRANSPORT_TYPE_ALLOWED_WWAN, qnsTransportType);

        qnsTransportType = mConfigManager.getQnsSupportedTransportType(ApnTypes.NONE);
        Assert.assertEquals(QnsConstants.INVALID_ID, qnsTransportType);
        qnsTransportType = mConfigManager.getQnsSupportedTransportType(ApnTypes.IMS);
        Assert.assertNotEquals(QnsConstants.TRANSPORT_TYPE_ALLOWED_IWLAN, qnsTransportType);
        qnsTransportType = mConfigManager.getQnsSupportedTransportType(ApnTypes.EMERGENCY);
        Assert.assertNotEquals(QnsConstants.TRANSPORT_TYPE_ALLOWED_BOTH, qnsTransportType);
    }

    @Test
    public void testGetWwanHysteresisTimerWithDefaultValues() {
        int wwanHysteresisTimer;

        wwanHysteresisTimer = mConfigManager.getWwanHysteresisTimer(ApnTypes.IMS, CALL_TYPE_IDLE);
        Assert.assertEquals(QnsConstants.KEY_DEFAULT_HYST_TIMER, wwanHysteresisTimer);
        wwanHysteresisTimer =
                mConfigManager.getWwanHysteresisTimer(ApnTypes.EMERGENCY, CALL_TYPE_IDLE);
        Assert.assertEquals(QnsConstants.KEY_DEFAULT_HYST_TIMER, wwanHysteresisTimer);
        wwanHysteresisTimer = mConfigManager.getWwanHysteresisTimer(ApnTypes.IMS, CALL_TYPE_VOICE);
        Assert.assertEquals(QnsConstants.KEY_DEFAULT_HYST_TIMER, wwanHysteresisTimer);
        wwanHysteresisTimer =
                mConfigManager.getWwanHysteresisTimer(ApnTypes.EMERGENCY, CALL_TYPE_VOICE);
        Assert.assertEquals(QnsConstants.KEY_DEFAULT_HYST_TIMER, wwanHysteresisTimer);
        wwanHysteresisTimer = mConfigManager.getWwanHysteresisTimer(ApnTypes.IMS, CALL_TYPE_VIDEO);
        Assert.assertEquals(QnsConstants.KEY_DEFAULT_HYST_TIMER, wwanHysteresisTimer);
        wwanHysteresisTimer =
                mConfigManager.getWwanHysteresisTimer(ApnTypes.EMERGENCY, CALL_TYPE_VIDEO);
        Assert.assertEquals(QnsConstants.KEY_DEFAULT_HYST_TIMER, wwanHysteresisTimer);
        wwanHysteresisTimer = mConfigManager.getWwanHysteresisTimer(ApnTypes.NONE, CALL_TYPE_VIDEO);
        Assert.assertEquals(QnsConstants.KEY_DEFAULT_VALUE, wwanHysteresisTimer);
        wwanHysteresisTimer = mConfigManager.getWwanHysteresisTimer(ApnTypes.IMS, -1);
        Assert.assertNotEquals(QnsConstants.KEY_DEFAULT_HYST_TIMER, wwanHysteresisTimer);
        wwanHysteresisTimer = mConfigManager.getWwanHysteresisTimer(ApnTypes.EMERGENCY, 3);
        Assert.assertNotEquals(QnsConstants.KEY_DEFAULT_HYST_TIMER, wwanHysteresisTimer);
        wwanHysteresisTimer = mConfigManager.getWwanHysteresisTimer(ApnTypes.MMS, CALL_TYPE_IDLE);
        Assert.assertEquals(QnsConstants.KEY_DEFAULT_VALUE, wwanHysteresisTimer);
        wwanHysteresisTimer = mConfigManager.getWwanHysteresisTimer(ApnTypes.MMS, CALL_TYPE_VOICE);
        Assert.assertEquals(QnsConstants.KEY_DEFAULT_VALUE, wwanHysteresisTimer);
        wwanHysteresisTimer = mConfigManager.getWwanHysteresisTimer(ApnTypes.MMS, CALL_TYPE_VIDEO);
        Assert.assertEquals(QnsConstants.KEY_DEFAULT_VALUE, wwanHysteresisTimer);
        wwanHysteresisTimer = mConfigManager.getWwanHysteresisTimer(ApnTypes.CBS, CALL_TYPE_IDLE);
        Assert.assertEquals(QnsConstants.KEY_DEFAULT_VALUE, wwanHysteresisTimer);
        wwanHysteresisTimer = mConfigManager.getWwanHysteresisTimer(ApnTypes.CBS, CALL_TYPE_VOICE);
        Assert.assertEquals(QnsConstants.KEY_DEFAULT_VALUE, wwanHysteresisTimer);
        wwanHysteresisTimer = mConfigManager.getWwanHysteresisTimer(ApnTypes.CBS, CALL_TYPE_VIDEO);
        Assert.assertEquals(QnsConstants.KEY_DEFAULT_VALUE, wwanHysteresisTimer);
        wwanHysteresisTimer = mConfigManager.getWwanHysteresisTimer(ApnTypes.XCAP, CALL_TYPE_IDLE);
        Assert.assertEquals(QnsConstants.KEY_DEFAULT_VALUE, wwanHysteresisTimer);
        wwanHysteresisTimer = mConfigManager.getWwanHysteresisTimer(ApnTypes.XCAP, CALL_TYPE_VOICE);
        Assert.assertEquals(QnsConstants.KEY_DEFAULT_VALUE, wwanHysteresisTimer);
        wwanHysteresisTimer = mConfigManager.getWwanHysteresisTimer(ApnTypes.XCAP, CALL_TYPE_VIDEO);
        Assert.assertEquals(QnsConstants.KEY_DEFAULT_VALUE, wwanHysteresisTimer);
        wwanHysteresisTimer = mConfigManager.getWwanHysteresisTimer(ApnTypes.MMS, -1);
        Assert.assertEquals(QnsConstants.KEY_DEFAULT_VALUE, wwanHysteresisTimer);
        wwanHysteresisTimer = mConfigManager.getWwanHysteresisTimer(ApnTypes.EMERGENCY, 3);
        Assert.assertEquals(QnsConstants.KEY_DEFAULT_VALUE, wwanHysteresisTimer);
    }

    @Test
    public void testGetWlanHysteresisTimerWithDefaultValues() {
        int wlanHysteresisTimer;

        wlanHysteresisTimer = mConfigManager.getWlanHysteresisTimer(ApnTypes.IMS, CALL_TYPE_IDLE);
        Assert.assertEquals(QnsConstants.KEY_DEFAULT_HYST_TIMER, wlanHysteresisTimer);
        wlanHysteresisTimer =
                mConfigManager.getWlanHysteresisTimer(ApnTypes.EMERGENCY, CALL_TYPE_IDLE);
        Assert.assertEquals(QnsConstants.KEY_DEFAULT_HYST_TIMER, wlanHysteresisTimer);
        wlanHysteresisTimer = mConfigManager.getWlanHysteresisTimer(ApnTypes.IMS, CALL_TYPE_VOICE);
        Assert.assertEquals(QnsConstants.KEY_DEFAULT_HYST_TIMER, wlanHysteresisTimer);
        wlanHysteresisTimer =
                mConfigManager.getWlanHysteresisTimer(ApnTypes.EMERGENCY, CALL_TYPE_VOICE);
        Assert.assertEquals(QnsConstants.KEY_DEFAULT_HYST_TIMER, wlanHysteresisTimer);
        wlanHysteresisTimer = mConfigManager.getWlanHysteresisTimer(ApnTypes.IMS, CALL_TYPE_VIDEO);
        Assert.assertEquals(QnsConstants.KEY_DEFAULT_HYST_TIMER, wlanHysteresisTimer);
        wlanHysteresisTimer =
                mConfigManager.getWlanHysteresisTimer(ApnTypes.EMERGENCY, CALL_TYPE_VIDEO);
        Assert.assertEquals(QnsConstants.KEY_DEFAULT_HYST_TIMER, wlanHysteresisTimer);
        wlanHysteresisTimer = mConfigManager.getWlanHysteresisTimer(ApnTypes.NONE, CALL_TYPE_VIDEO);
        Assert.assertEquals(QnsConstants.KEY_DEFAULT_VALUE, wlanHysteresisTimer);
        wlanHysteresisTimer = mConfigManager.getWlanHysteresisTimer(ApnTypes.IMS, -1);
        Assert.assertNotEquals(QnsConstants.KEY_DEFAULT_HYST_TIMER, wlanHysteresisTimer);
        wlanHysteresisTimer = mConfigManager.getWlanHysteresisTimer(ApnTypes.EMERGENCY, 3);
        Assert.assertNotEquals(QnsConstants.KEY_DEFAULT_HYST_TIMER, wlanHysteresisTimer);
        wlanHysteresisTimer = mConfigManager.getWlanHysteresisTimer(ApnTypes.MMS, CALL_TYPE_IDLE);
        Assert.assertEquals(QnsConstants.KEY_DEFAULT_VALUE, wlanHysteresisTimer);
        wlanHysteresisTimer = mConfigManager.getWlanHysteresisTimer(ApnTypes.MMS, CALL_TYPE_VOICE);
        Assert.assertEquals(QnsConstants.KEY_DEFAULT_VALUE, wlanHysteresisTimer);
        wlanHysteresisTimer = mConfigManager.getWlanHysteresisTimer(ApnTypes.MMS, CALL_TYPE_VIDEO);
        Assert.assertEquals(QnsConstants.KEY_DEFAULT_VALUE, wlanHysteresisTimer);
        wlanHysteresisTimer = mConfigManager.getWlanHysteresisTimer(ApnTypes.CBS, CALL_TYPE_IDLE);
        Assert.assertEquals(QnsConstants.KEY_DEFAULT_VALUE, wlanHysteresisTimer);
        wlanHysteresisTimer = mConfigManager.getWlanHysteresisTimer(ApnTypes.CBS, CALL_TYPE_VOICE);
        Assert.assertEquals(QnsConstants.KEY_DEFAULT_VALUE, wlanHysteresisTimer);
        wlanHysteresisTimer = mConfigManager.getWlanHysteresisTimer(ApnTypes.CBS, CALL_TYPE_VIDEO);
        Assert.assertEquals(QnsConstants.KEY_DEFAULT_VALUE, wlanHysteresisTimer);
        wlanHysteresisTimer = mConfigManager.getWlanHysteresisTimer(ApnTypes.XCAP, CALL_TYPE_IDLE);
        Assert.assertEquals(QnsConstants.KEY_DEFAULT_VALUE, wlanHysteresisTimer);
        wlanHysteresisTimer = mConfigManager.getWlanHysteresisTimer(ApnTypes.XCAP, CALL_TYPE_VOICE);
        Assert.assertEquals(QnsConstants.KEY_DEFAULT_VALUE, wlanHysteresisTimer);
        wlanHysteresisTimer = mConfigManager.getWlanHysteresisTimer(ApnTypes.XCAP, CALL_TYPE_VIDEO);
        Assert.assertEquals(QnsConstants.KEY_DEFAULT_VALUE, wlanHysteresisTimer);
        wlanHysteresisTimer = mConfigManager.getWlanHysteresisTimer(ApnTypes.MMS, -1);
        Assert.assertEquals(QnsConstants.KEY_DEFAULT_VALUE, wlanHysteresisTimer);
        wlanHysteresisTimer = mConfigManager.getWlanHysteresisTimer(ApnTypes.EMERGENCY, 3);
        Assert.assertEquals(QnsConstants.KEY_DEFAULT_VALUE, wlanHysteresisTimer);
    }

    @Test
    public void testTransportNetworkToString() {
        String transportType_str = null;

        transportType_str =
                QnsCarrierConfigManager.transportNetworkToString(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        Assert.assertEquals("CELLULAR", transportType_str);
        Assert.assertNotEquals("WIFI", transportType_str);

        transportType_str =
                QnsCarrierConfigManager.transportNetworkToString(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
        Assert.assertNotEquals("CELLULAR", transportType_str);
        Assert.assertEquals("WIFI", transportType_str);

        transportType_str =
                QnsCarrierConfigManager.transportNetworkToString(
                        AccessNetworkConstants.TRANSPORT_TYPE_INVALID);
        Assert.assertEquals("", transportType_str);
        transportType_str = QnsCarrierConfigManager.transportNetworkToString(3);
        Assert.assertEquals("", transportType_str);
    }

    @Test
    public void testGetThresholdWithDefaultValues() {

        testEutranThresholdConfigArray();
        testNgranThresholdConfigsArray();
        testUtranThresholdConfigsArray();
        testGeranThresholdConfigsArray();
        testIwlanThresholdConfigsArray();
    }

    private void testEutranThresholdConfigArray() {
        loadAndValidateForThresholds(
                AccessNetworkConstants.AccessNetworkType.EUTRAN,
                CALL_TYPE_IDLE,
                SIGNAL_MEASUREMENT_TYPE_RSRP);
        loadAndValidateForThresholds(
                AccessNetworkConstants.AccessNetworkType.EUTRAN,
                CALL_TYPE_VOICE,
                SIGNAL_MEASUREMENT_TYPE_RSRP);
        loadAndValidateForThresholds(
                AccessNetworkConstants.AccessNetworkType.EUTRAN,
                QnsConstants.CALL_TYPE_VIDEO,
                SIGNAL_MEASUREMENT_TYPE_RSRP);
        loadAndValidateForThresholds(
                AccessNetworkConstants.AccessNetworkType.EUTRAN,
                CALL_TYPE_IDLE,
                SIGNAL_MEASUREMENT_TYPE_RSRQ);
        loadAndValidateForThresholds(
                AccessNetworkConstants.AccessNetworkType.EUTRAN,
                CALL_TYPE_VOICE,
                SIGNAL_MEASUREMENT_TYPE_RSRQ);
        loadAndValidateForThresholds(
                AccessNetworkConstants.AccessNetworkType.EUTRAN,
                QnsConstants.CALL_TYPE_VIDEO,
                SIGNAL_MEASUREMENT_TYPE_RSRQ);
        loadAndValidateForThresholds(
                AccessNetworkConstants.AccessNetworkType.EUTRAN,
                CALL_TYPE_IDLE,
                SIGNAL_MEASUREMENT_TYPE_RSSNR);
        loadAndValidateForThresholds(
                AccessNetworkConstants.AccessNetworkType.EUTRAN,
                CALL_TYPE_VOICE,
                SIGNAL_MEASUREMENT_TYPE_RSSNR);
        loadAndValidateForThresholds(
                AccessNetworkConstants.AccessNetworkType.EUTRAN,
                QnsConstants.CALL_TYPE_VIDEO,
                SIGNAL_MEASUREMENT_TYPE_RSSNR);
    }

    private void testNgranThresholdConfigsArray() {
        loadAndValidateForThresholds(
                AccessNetworkConstants.AccessNetworkType.NGRAN,
                CALL_TYPE_IDLE,
                SIGNAL_MEASUREMENT_TYPE_SSRSRP);
        loadAndValidateForThresholds(
                AccessNetworkConstants.AccessNetworkType.NGRAN,
                CALL_TYPE_VOICE,
                SIGNAL_MEASUREMENT_TYPE_SSRSRP);
        loadAndValidateForThresholds(
                AccessNetworkConstants.AccessNetworkType.NGRAN,
                QnsConstants.CALL_TYPE_VIDEO,
                SIGNAL_MEASUREMENT_TYPE_SSRSRP);
        loadAndValidateForThresholds(
                AccessNetworkConstants.AccessNetworkType.NGRAN,
                CALL_TYPE_IDLE,
                SIGNAL_MEASUREMENT_TYPE_SSRSRQ);
        loadAndValidateForThresholds(
                AccessNetworkConstants.AccessNetworkType.NGRAN,
                CALL_TYPE_VOICE,
                SIGNAL_MEASUREMENT_TYPE_SSRSRQ);
        loadAndValidateForThresholds(
                AccessNetworkConstants.AccessNetworkType.NGRAN,
                QnsConstants.CALL_TYPE_VIDEO,
                SIGNAL_MEASUREMENT_TYPE_SSRSRQ);
        loadAndValidateForThresholds(
                AccessNetworkConstants.AccessNetworkType.NGRAN,
                CALL_TYPE_IDLE,
                SIGNAL_MEASUREMENT_TYPE_SSSINR);
        loadAndValidateForThresholds(
                AccessNetworkConstants.AccessNetworkType.NGRAN,
                CALL_TYPE_VOICE,
                SIGNAL_MEASUREMENT_TYPE_SSSINR);
        loadAndValidateForThresholds(
                AccessNetworkConstants.AccessNetworkType.NGRAN,
                QnsConstants.CALL_TYPE_VIDEO,
                SIGNAL_MEASUREMENT_TYPE_SSSINR);
    }

    private void testUtranThresholdConfigsArray() {
        loadAndValidateForThresholds(
                AccessNetworkConstants.AccessNetworkType.UTRAN,
                CALL_TYPE_IDLE,
                SIGNAL_MEASUREMENT_TYPE_RSCP);
        loadAndValidateForThresholds(
                AccessNetworkConstants.AccessNetworkType.UTRAN,
                CALL_TYPE_VOICE,
                SIGNAL_MEASUREMENT_TYPE_RSCP);
        loadAndValidateForThresholds(
                AccessNetworkConstants.AccessNetworkType.UTRAN,
                QnsConstants.CALL_TYPE_VIDEO,
                SIGNAL_MEASUREMENT_TYPE_RSCP);
    }

    private void testGeranThresholdConfigsArray() {
        loadAndValidateForThresholds(
                AccessNetworkConstants.AccessNetworkType.GERAN,
                CALL_TYPE_IDLE,
                SIGNAL_MEASUREMENT_TYPE_RSSI);
        loadAndValidateForThresholds(
                AccessNetworkConstants.AccessNetworkType.GERAN,
                CALL_TYPE_VOICE,
                SIGNAL_MEASUREMENT_TYPE_RSSI);
        loadAndValidateForThresholds(
                AccessNetworkConstants.AccessNetworkType.GERAN,
                QnsConstants.CALL_TYPE_VIDEO,
                SIGNAL_MEASUREMENT_TYPE_RSSI);
    }

    private void testIwlanThresholdConfigsArray() {
        loadAndValidateForThresholds(
                AccessNetworkConstants.AccessNetworkType.IWLAN,
                CALL_TYPE_IDLE,
                SIGNAL_MEASUREMENT_TYPE_RSSI);
        loadAndValidateForThresholds(
                AccessNetworkConstants.AccessNetworkType.IWLAN,
                CALL_TYPE_VOICE,
                SIGNAL_MEASUREMENT_TYPE_RSSI);
        loadAndValidateForThresholds(
                AccessNetworkConstants.AccessNetworkType.IWLAN,
                QnsConstants.CALL_TYPE_VIDEO,
                SIGNAL_MEASUREMENT_TYPE_RSSI);
    }

    private void loadAndValidateForThresholds(
            @AccessNetworkConstants.RadioAccessNetworkType int an,
            int callType,
            int measurementType) {

        QnsCarrierConfigManager.QnsConfigArray threshold =
                mConfigManager.getThreshold(an, callType, measurementType);

        assert threshold != null;

        if ((callType == CALL_TYPE_IDLE || callType == CALL_TYPE_VOICE)
                && (measurementType == SIGNAL_MEASUREMENT_TYPE_RSRP
                        || measurementType == SIGNAL_MEASUREMENT_TYPE_SSRSRP)) {
            if (an == AccessNetworkConstants.AccessNetworkType.NGRAN) {
                Assert.assertEquals(
                        QnsConstants.KEY_DEFAULT_THRESHOLD_SSRSRP_GOOD, threshold.mGood);
                Assert.assertEquals(QnsConstants.KEY_DEFAULT_THRESHOLD_SSRSRP_BAD, threshold.mBad);
            } else if (an == AccessNetworkConstants.AccessNetworkType.NGRAN) {
                Assert.assertEquals(QnsConstants.KEY_DEFAULT_THRESHOLD_RSRP_GOOD, threshold.mGood);
                Assert.assertEquals(QnsConstants.KEY_DEFAULT_THRESHOLD_RSRP_BAD, threshold.mBad);
            }
            Assert.assertEquals(threshold.INVALID, threshold.mWorst);
        } else if ((callType == CALL_TYPE_IDLE || callType == CALL_TYPE_VOICE)
                && (measurementType == SIGNAL_MEASUREMENT_TYPE_RSCP)) {
            if (an == AccessNetworkConstants.AccessNetworkType.NGRAN) {
                Assert.assertEquals(QnsConstants.KEY_DEFAULT_THRESHOLD_RSCP_GOOD, threshold.mGood);
                Assert.assertEquals(QnsConstants.KEY_DEFAULT_THRESHOLD_RSCP_BAD, threshold.mBad);
            }
            Assert.assertEquals(threshold.INVALID, threshold.mWorst);
        } else if ((callType == CALL_TYPE_IDLE || callType == CALL_TYPE_VOICE)
                && (measurementType == SIGNAL_MEASUREMENT_TYPE_RSSI)) {
            if (an == AccessNetworkConstants.AccessNetworkType.NGRAN) {
                Assert.assertEquals(
                        QnsConstants.KEY_DEFAULT_THRESHOLD_GERAN_RSSI_GOOD, threshold.mGood);
                Assert.assertEquals(
                        QnsConstants.KEY_DEFAULT_THRESHOLD_GERAN_RSSI_BAD, threshold.mBad);
            }
            Assert.assertEquals(threshold.INVALID, threshold.mWorst);
        } else if (an == AccessNetworkConstants.AccessNetworkType.IWLAN) {
            if (callType == CALL_TYPE_IDLE || callType == CALL_TYPE_VOICE) {
                Assert.assertEquals(
                        QnsConstants.KEY_DEFAULT_THRESHOLD_WIFI_RSSI_GOOD, threshold.mGood);
                Assert.assertEquals(
                        QnsConstants.KEY_DEFAULT_THRESHOLD_WIFI_RSSI_BAD, threshold.mBad);
            } else if (callType == QnsConstants.CALL_TYPE_VIDEO) {
                Assert.assertEquals(
                        QnsConstants.KEY_DEFAULT_THRESHOLD_VIDEO_WIFI_RSSI_GOOD, threshold.mGood);
                Assert.assertEquals(
                        QnsConstants.KEY_DEFAULT_THRESHOLD_VIDEO_WIFI_RSSI_BAD, threshold.mBad);
            }
        } else {
            Assert.assertEquals(threshold.INVALID, threshold.mGood);
            Assert.assertEquals(threshold.INVALID, threshold.mBad);
            Assert.assertEquals(threshold.INVALID, threshold.mWorst);
        }
    }

    @Test
    public void testGetThresholdByPref() {

        testEutranThresholdConfigArrayByPref();
        testNgranThresholdConfigsArrayByPref();
        testUtranThresholdConfigsArrayByPref();
        testGeranThresholdConfigsArrayByPref();
        testIwlanThresholdConfigsArrayByPref();
    }

    private void testEutranThresholdConfigArrayByPref() {
        loadAndValidateForThresholdsByPref(
                AccessNetworkConstants.AccessNetworkType.EUTRAN,
                CALL_TYPE_IDLE,
                SIGNAL_MEASUREMENT_TYPE_RSRP,
                WIFI_PREF);
        loadAndValidateForThresholdsByPref(
                AccessNetworkConstants.AccessNetworkType.EUTRAN,
                CALL_TYPE_VOICE,
                SIGNAL_MEASUREMENT_TYPE_RSRP,
                WIFI_PREF);
        loadAndValidateForThresholdsByPref(
                AccessNetworkConstants.AccessNetworkType.EUTRAN,
                QnsConstants.CALL_TYPE_VIDEO,
                SIGNAL_MEASUREMENT_TYPE_RSRP,
                WIFI_PREF);
        loadAndValidateForThresholdsByPref(
                AccessNetworkConstants.AccessNetworkType.EUTRAN,
                CALL_TYPE_IDLE,
                SIGNAL_MEASUREMENT_TYPE_RSRQ,
                WIFI_PREF);
        loadAndValidateForThresholdsByPref(
                AccessNetworkConstants.AccessNetworkType.EUTRAN,
                CALL_TYPE_VOICE,
                SIGNAL_MEASUREMENT_TYPE_RSRQ,
                WIFI_PREF);
        loadAndValidateForThresholdsByPref(
                AccessNetworkConstants.AccessNetworkType.EUTRAN,
                QnsConstants.CALL_TYPE_VIDEO,
                SIGNAL_MEASUREMENT_TYPE_RSRQ,
                WIFI_PREF);
        loadAndValidateForThresholdsByPref(
                AccessNetworkConstants.AccessNetworkType.EUTRAN,
                CALL_TYPE_IDLE,
                SIGNAL_MEASUREMENT_TYPE_RSSNR,
                WIFI_PREF);
        loadAndValidateForThresholdsByPref(
                AccessNetworkConstants.AccessNetworkType.EUTRAN,
                CALL_TYPE_VOICE,
                SIGNAL_MEASUREMENT_TYPE_RSSNR,
                WIFI_PREF);
        loadAndValidateForThresholdsByPref(
                AccessNetworkConstants.AccessNetworkType.EUTRAN,
                QnsConstants.CALL_TYPE_VIDEO,
                SIGNAL_MEASUREMENT_TYPE_RSSNR,
                WIFI_PREF);
    }

    private void testNgranThresholdConfigsArrayByPref() {
        loadAndValidateForThresholdsByPref(
                AccessNetworkConstants.AccessNetworkType.NGRAN,
                CALL_TYPE_IDLE,
                SIGNAL_MEASUREMENT_TYPE_SSRSRP,
                WIFI_PREF);
        loadAndValidateForThresholdsByPref(
                AccessNetworkConstants.AccessNetworkType.NGRAN,
                CALL_TYPE_VOICE,
                SIGNAL_MEASUREMENT_TYPE_SSRSRP,
                WIFI_PREF);
        loadAndValidateForThresholdsByPref(
                AccessNetworkConstants.AccessNetworkType.NGRAN,
                QnsConstants.CALL_TYPE_VIDEO,
                SIGNAL_MEASUREMENT_TYPE_SSRSRP,
                WIFI_PREF);
        loadAndValidateForThresholdsByPref(
                AccessNetworkConstants.AccessNetworkType.NGRAN,
                CALL_TYPE_IDLE,
                SIGNAL_MEASUREMENT_TYPE_SSRSRQ,
                WIFI_PREF);
        loadAndValidateForThresholdsByPref(
                AccessNetworkConstants.AccessNetworkType.NGRAN,
                CALL_TYPE_VOICE,
                SIGNAL_MEASUREMENT_TYPE_SSRSRQ,
                WIFI_PREF);
        loadAndValidateForThresholdsByPref(
                AccessNetworkConstants.AccessNetworkType.NGRAN,
                QnsConstants.CALL_TYPE_VIDEO,
                SIGNAL_MEASUREMENT_TYPE_SSRSRQ,
                WIFI_PREF);
        loadAndValidateForThresholdsByPref(
                AccessNetworkConstants.AccessNetworkType.NGRAN,
                CALL_TYPE_IDLE,
                SIGNAL_MEASUREMENT_TYPE_SSSINR,
                WIFI_PREF);
        loadAndValidateForThresholdsByPref(
                AccessNetworkConstants.AccessNetworkType.NGRAN,
                CALL_TYPE_VOICE,
                SIGNAL_MEASUREMENT_TYPE_SSSINR,
                WIFI_PREF);
        loadAndValidateForThresholdsByPref(
                AccessNetworkConstants.AccessNetworkType.NGRAN,
                QnsConstants.CALL_TYPE_VIDEO,
                SIGNAL_MEASUREMENT_TYPE_SSSINR,
                WIFI_PREF);
    }

    private void testUtranThresholdConfigsArrayByPref() {
        loadAndValidateForThresholdsByPref(
                AccessNetworkConstants.AccessNetworkType.UTRAN,
                CALL_TYPE_IDLE,
                SIGNAL_MEASUREMENT_TYPE_RSCP,
                WIFI_PREF);
        loadAndValidateForThresholdsByPref(
                AccessNetworkConstants.AccessNetworkType.UTRAN,
                CALL_TYPE_VOICE,
                SIGNAL_MEASUREMENT_TYPE_RSCP,
                WIFI_PREF);
        loadAndValidateForThresholdsByPref(
                AccessNetworkConstants.AccessNetworkType.UTRAN,
                QnsConstants.CALL_TYPE_VIDEO,
                SIGNAL_MEASUREMENT_TYPE_RSCP,
                WIFI_PREF);
    }

    private void testGeranThresholdConfigsArrayByPref() {
        loadAndValidateForThresholdsByPref(
                AccessNetworkConstants.AccessNetworkType.GERAN,
                CALL_TYPE_IDLE,
                SIGNAL_MEASUREMENT_TYPE_RSSI,
                WIFI_PREF);
        loadAndValidateForThresholdsByPref(
                AccessNetworkConstants.AccessNetworkType.GERAN,
                CALL_TYPE_VOICE,
                SIGNAL_MEASUREMENT_TYPE_RSSI,
                WIFI_PREF);
        loadAndValidateForThresholdsByPref(
                AccessNetworkConstants.AccessNetworkType.GERAN,
                QnsConstants.CALL_TYPE_VIDEO,
                SIGNAL_MEASUREMENT_TYPE_RSSI,
                WIFI_PREF);
    }

    private void testIwlanThresholdConfigsArrayByPref() {
        loadAndValidateForThresholdsByPref(
                AccessNetworkConstants.AccessNetworkType.IWLAN,
                CALL_TYPE_IDLE,
                SIGNAL_MEASUREMENT_TYPE_RSSI,
                WIFI_PREF);
        loadAndValidateForThresholdsByPref(
                AccessNetworkConstants.AccessNetworkType.IWLAN,
                CALL_TYPE_VOICE,
                SIGNAL_MEASUREMENT_TYPE_RSSI,
                WIFI_PREF);
        loadAndValidateForThresholdsByPref(
                AccessNetworkConstants.AccessNetworkType.IWLAN,
                QnsConstants.CALL_TYPE_VIDEO,
                SIGNAL_MEASUREMENT_TYPE_RSSI,
                WIFI_PREF);
    }

    private void loadAndValidateForThresholdsByPref(
            @AccessNetworkConstants.RadioAccessNetworkType int an,
            int callType,
            int measurementType,
            int preference) {

        QnsCarrierConfigManager.QnsConfigArray threshold =
                mConfigManager.getThresholdByPref(an, callType, measurementType, preference);

        assert threshold != null;

        if ((callType == CALL_TYPE_IDLE || callType == CALL_TYPE_VOICE)
                && (measurementType == SIGNAL_MEASUREMENT_TYPE_RSRP
                        || measurementType == SIGNAL_MEASUREMENT_TYPE_SSRSRP)) {
            if (an == AccessNetworkConstants.AccessNetworkType.NGRAN) {
                Assert.assertEquals(
                        QnsConstants.KEY_DEFAULT_THRESHOLD_SSRSRP_GOOD, threshold.mGood);
                Assert.assertEquals(QnsConstants.KEY_DEFAULT_THRESHOLD_SSRSRP_BAD, threshold.mBad);
            } else if (an == AccessNetworkConstants.AccessNetworkType.NGRAN) {
                Assert.assertEquals(QnsConstants.KEY_DEFAULT_THRESHOLD_RSRP_GOOD, threshold.mGood);
                Assert.assertEquals(QnsConstants.KEY_DEFAULT_THRESHOLD_RSRP_BAD, threshold.mBad);
            }
            Assert.assertEquals(threshold.INVALID, threshold.mWorst);
        } else if ((callType == CALL_TYPE_IDLE || callType == CALL_TYPE_VOICE)
                && (measurementType == SIGNAL_MEASUREMENT_TYPE_RSCP)) {
            if (an == AccessNetworkConstants.AccessNetworkType.NGRAN) {
                Assert.assertEquals(QnsConstants.KEY_DEFAULT_THRESHOLD_RSCP_GOOD, threshold.mGood);
                Assert.assertEquals(QnsConstants.KEY_DEFAULT_THRESHOLD_RSCP_BAD, threshold.mBad);
            }
            Assert.assertEquals(threshold.INVALID, threshold.mWorst);
        } else if ((callType == CALL_TYPE_IDLE || callType == CALL_TYPE_VOICE)
                && (measurementType == SIGNAL_MEASUREMENT_TYPE_RSSI)) {
            if (an == AccessNetworkConstants.AccessNetworkType.NGRAN) {
                Assert.assertEquals(
                        QnsConstants.KEY_DEFAULT_THRESHOLD_GERAN_RSSI_GOOD, threshold.mGood);
                Assert.assertEquals(
                        QnsConstants.KEY_DEFAULT_THRESHOLD_GERAN_RSSI_BAD, threshold.mBad);
            }
            Assert.assertEquals(threshold.INVALID, threshold.mWorst);
        } else if (an == AccessNetworkConstants.AccessNetworkType.IWLAN) {
            if (callType == CALL_TYPE_IDLE || callType == CALL_TYPE_VOICE) {
                Assert.assertEquals(
                        QnsConstants.KEY_DEFAULT_THRESHOLD_WIFI_RSSI_GOOD, threshold.mGood);
                Assert.assertEquals(
                        QnsConstants.KEY_DEFAULT_THRESHOLD_WIFI_RSSI_BAD, threshold.mBad);
            } else if (callType == QnsConstants.CALL_TYPE_VIDEO) {
                Assert.assertEquals(
                        QnsConstants.KEY_DEFAULT_THRESHOLD_VIDEO_WIFI_RSSI_GOOD, threshold.mGood);
                Assert.assertEquals(
                        QnsConstants.KEY_DEFAULT_THRESHOLD_VIDEO_WIFI_RSSI_BAD, threshold.mBad);
            }
        } else {
            Assert.assertEquals(threshold.INVALID, threshold.mGood);
            Assert.assertEquals(threshold.INVALID, threshold.mBad);
            Assert.assertEquals(threshold.INVALID, threshold.mWorst);
        }
    }

    @Test
    public void testGetWifiRssiThresholdWithoutCellular() {
        QnsCarrierConfigManager.QnsConfigArray threshold;

        threshold = mConfigManager.getWifiRssiThresholdWithoutCellular(CALL_TYPE_IDLE);
        assert threshold != null;
        Assert.assertEquals(threshold.INVALID, threshold.mGood);
        Assert.assertEquals(threshold.INVALID, threshold.mGood);

        threshold = mConfigManager.getWifiRssiThresholdWithoutCellular(CALL_TYPE_VOICE);
        assert threshold != null;
        Assert.assertEquals(threshold.INVALID, threshold.mBad);
        Assert.assertEquals(threshold.INVALID, threshold.mBad);

        threshold = mConfigManager.getWifiRssiThresholdWithoutCellular(CALL_TYPE_VIDEO);
        assert threshold != null;
        Assert.assertEquals(threshold.INVALID, threshold.mGood);
        Assert.assertEquals(threshold.INVALID, threshold.mBad);
    }

    @Test
    public void testGetRTPMetricsData() {
        QnsCarrierConfigManager.RtpMetricsConfig rtpMetricsData =
                mConfigManager.getRTPMetricsData();

        Assert.assertEquals(QnsConstants.KEY_DEFAULT_JITTER, rtpMetricsData.mJitter);
        Assert.assertEquals(QnsConstants.KEY_DEFAULT_PACKET_LOSS_RATE, rtpMetricsData.mPktLossRate);
        Assert.assertEquals(
                QnsConstants.KEY_DEFAULT_PACKET_LOSS_TIME_MILLIS, rtpMetricsData.mPktLossTime);
        Assert.assertEquals(
                QnsConstants.KEY_DEFAULT_NO_RTP_INTERVAL_MILLIS, rtpMetricsData.mNoRtpInterval);

        Assert.assertNotEquals(QnsConstants.KEY_DEFAULT_VALUE, rtpMetricsData.mJitter);
        Assert.assertNotEquals(QnsConstants.KEY_DEFAULT_VALUE, rtpMetricsData.mPktLossRate);
        Assert.assertNotEquals(QnsConstants.KEY_DEFAULT_VALUE, rtpMetricsData.mPktLossTime);
        Assert.assertNotEquals(QnsConstants.KEY_DEFAULT_VALUE, rtpMetricsData.mNoRtpInterval);
    }

    @Test
    public void testIsHandoverAllowedByPolicyWithTestBundle() {
        PersistableBundle bundle = new PersistableBundle();
        bundle.putStringArray(
                CarrierConfigManager.KEY_IWLAN_HANDOVER_POLICY_STRING_ARRAY,
                new String[] {HANDOVER_POLICY_0, HANDOVER_POLICY_1, HANDOVER_POLICY_2});
        doReturn(bundle).when(carrierConfigManager).getConfigForSubId(anyInt());
        mConfigManager.loadHandoverRules(
                bundle, null, CarrierConfigManager.KEY_IWLAN_HANDOVER_POLICY_STRING_ARRAY);
        assertTrue(
                mConfigManager.isHandoverAllowedByPolicy(
                        ApnSetting.TYPE_IMS,
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        QnsConstants.COVERAGE_HOME));
        assertTrue(
                mConfigManager.isHandoverAllowedByPolicy(
                        ApnSetting.TYPE_EMERGENCY,
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        QnsConstants.COVERAGE_HOME));
        assertTrue(
                mConfigManager.isHandoverAllowedByPolicy(
                        ApnSetting.TYPE_IMS,
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        QnsConstants.COVERAGE_HOME));
        assertFalse(
                mConfigManager.isHandoverAllowedByPolicy(
                        ApnSetting.TYPE_MMS,
                        AccessNetworkConstants.AccessNetworkType.NGRAN,
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        QnsConstants.COVERAGE_HOME));
        assertTrue(
                mConfigManager.isHandoverAllowedByPolicy(
                        ApnSetting.TYPE_CBS,
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        QnsConstants.COVERAGE_HOME));
        assertTrue(
                mConfigManager.isHandoverAllowedByPolicy(
                        ApnSetting.TYPE_XCAP,
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        QnsConstants.COVERAGE_HOME));
        assertFalse(
                mConfigManager.isHandoverAllowedByPolicy(
                        ApnSetting.TYPE_IMS,
                        AccessNetworkConstants.AccessNetworkType.GERAN,
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        QnsConstants.COVERAGE_HOME));
        assertFalse(
                mConfigManager.isHandoverAllowedByPolicy(
                        ApnSetting.TYPE_MMS,
                        AccessNetworkConstants.AccessNetworkType.GERAN,
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        QnsConstants.COVERAGE_HOME));
        assertFalse(
                mConfigManager.isHandoverAllowedByPolicy(
                        ApnSetting.TYPE_CBS,
                        AccessNetworkConstants.AccessNetworkType.GERAN,
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        QnsConstants.COVERAGE_HOME));
        assertFalse(
                mConfigManager.isHandoverAllowedByPolicy(
                        ApnSetting.TYPE_XCAP,
                        AccessNetworkConstants.AccessNetworkType.GERAN,
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        QnsConstants.COVERAGE_HOME));
        assertTrue(
                mConfigManager.isHandoverAllowedByPolicy(
                        ApnSetting.TYPE_IMS,
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        AccessNetworkConstants.AccessNetworkType.NGRAN,
                        QnsConstants.COVERAGE_HOME));
        assertFalse(
                mConfigManager.isHandoverAllowedByPolicy(
                        ApnSetting.TYPE_IMS,
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        AccessNetworkConstants.AccessNetworkType.NGRAN,
                        QnsConstants.COVERAGE_ROAM));
        assertTrue(
                mConfigManager.isHandoverAllowedByPolicy(
                        ApnSetting.TYPE_IMS,
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        AccessNetworkConstants.AccessNetworkType.UTRAN,
                        QnsConstants.COVERAGE_HOME));
        assertFalse(
                mConfigManager.isHandoverAllowedByPolicy(
                        ApnSetting.TYPE_EMERGENCY,
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        QnsConstants.COVERAGE_HOME));
    }

    @Test
    public void testIsHandoverAllowedByPolicyWithDefaultValues() {
        assertArrayEquals(
                (String[]) null,
                QnsUtils.getConfig(
                        null, null, CarrierConfigManager.KEY_IWLAN_HANDOVER_POLICY_STRING_ARRAY));
    }

    @Test
    public void testGetFallbackTimeImsUnreigstered() {
        PersistableBundle bundle = new PersistableBundle();
        bundle.putStringArray(
                QnsCarrierConfigManager
                        .KEY_QNS_FALLBACK_WWAN_IMS_UNREGISTRATION_REASON_STRING_ARRAY,
                new String[] {FALLBACK_RULE0, FALLBACK_RULE1});
        mConfigManager.loadFallbackPolicyWithImsRegiFail(bundle, null);
        assertEquals(60000, mConfigManager.getFallbackTimeImsUnreigstered(1503, CELL_PREF));
        assertEquals(0, mConfigManager.getFallbackTimeImsUnreigstered(1502, WIFI_PREF));
        assertEquals(0, mConfigManager.getFallbackTimeImsUnreigstered(1503, WIFI_ONLY));
        assertEquals(60000, mConfigManager.getFallbackTimeImsUnreigstered(321, CELL_PREF));
        assertEquals(60000, mConfigManager.getFallbackTimeImsUnreigstered(378, CELL_PREF));
        assertEquals(90000, mConfigManager.getFallbackTimeImsUnreigstered(380, CELL_PREF));
        assertEquals(60000, mConfigManager.getFallbackTimeImsUnreigstered(370, CELL_PREF));
        assertEquals(90000, mConfigManager.getFallbackTimeImsUnreigstered(370, WIFI_PREF));
        assertEquals(90000, mConfigManager.getFallbackTimeImsUnreigstered(350, WIFI_PREF));
        assertEquals(0, mConfigManager.getFallbackTimeImsUnreigstered(349, WIFI_PREF));
        assertEquals(0, mConfigManager.getFallbackTimeImsUnreigstered(360, WIFI_ONLY));
        assertEquals(90000, mConfigManager.getFallbackTimeImsUnreigstered(267, CELL_PREF));
        assertEquals(90000, mConfigManager.getFallbackTimeImsUnreigstered(232, WIFI_PREF));
        assertEquals(0, mConfigManager.getFallbackTimeImsHoRegisterFailed(1503, CELL_PREF));
        assertEquals(0, mConfigManager.getFallbackTimeImsHoRegisterFailed(232, WIFI_PREF));
    }

    @Test
    public void testGetFallbackTimeImsHoRegisterFailed() {
        PersistableBundle bundle = new PersistableBundle();
        bundle.putStringArray(
                QnsCarrierConfigManager
                        .KEY_QNS_FALLBACK_WWAN_IMS_HO_REGISTER_FAIL_REASON_STRING_ARRAY,
                new String[] {FALLBACK_RULE0, FALLBACK_RULE1});
        mConfigManager.loadFallbackPolicyWithImsRegiFail(bundle, null);
        assertEquals(60000, mConfigManager.getFallbackTimeImsHoRegisterFailed(1503, CELL_PREF));
        assertEquals(0, mConfigManager.getFallbackTimeImsHoRegisterFailed(1502, WIFI_PREF));
        assertEquals(0, mConfigManager.getFallbackTimeImsHoRegisterFailed(1503, WIFI_ONLY));
        assertEquals(60000, mConfigManager.getFallbackTimeImsHoRegisterFailed(321, CELL_PREF));
        assertEquals(60000, mConfigManager.getFallbackTimeImsHoRegisterFailed(378, CELL_PREF));
        assertEquals(90000, mConfigManager.getFallbackTimeImsHoRegisterFailed(380, CELL_PREF));
        assertEquals(60000, mConfigManager.getFallbackTimeImsHoRegisterFailed(370, CELL_PREF));
        assertEquals(90000, mConfigManager.getFallbackTimeImsHoRegisterFailed(370, WIFI_PREF));
        assertEquals(90000, mConfigManager.getFallbackTimeImsHoRegisterFailed(350, WIFI_PREF));
        assertEquals(0, mConfigManager.getFallbackTimeImsHoRegisterFailed(349, WIFI_PREF));
        assertEquals(0, mConfigManager.getFallbackTimeImsHoRegisterFailed(360, WIFI_ONLY));
        assertEquals(90000, mConfigManager.getFallbackTimeImsHoRegisterFailed(267, CELL_PREF));
        assertEquals(90000, mConfigManager.getFallbackTimeImsHoRegisterFailed(232, WIFI_PREF));
        assertEquals(0, mConfigManager.getFallbackTimeImsUnreigstered(1503, CELL_PREF));
        assertEquals(0, mConfigManager.getFallbackTimeImsUnreigstered(232, WIFI_PREF));
    }

    @Test
    public void testIsMmtelCapabilityRequiredWithDefaultValues() {
        mConfigManager.loadCarrierConfig(null, null);
        assertTrue(mConfigManager.isMmtelCapabilityRequired(QnsConstants.COVERAGE_HOME));
        assertTrue(mConfigManager.isMmtelCapabilityRequired(QnsConstants.COVERAGE_ROAM));
    }

    @Test
    public void testIsMmtelCapabilityRequiredWithTestBundle() {
        assertTrue(mConfigManager.isMmtelCapabilityRequired(QnsConstants.COVERAGE_HOME));
        assertTrue(mConfigManager.isMmtelCapabilityRequired(QnsConstants.COVERAGE_ROAM));
        PersistableBundle bundle = new PersistableBundle();
        bundle.putIntArray(
                CarrierConfigManager.Ims.KEY_IMS_PDN_ENABLED_IN_NO_VOPS_SUPPORT_INT_ARRAY,
                new int[] {0, 1});
        doReturn(bundle).when(carrierConfigManager).getConfigForSubId(anyInt());
        mConfigManager.loadCarrierConfig(bundle, null);
        assertFalse(mConfigManager.isMmtelCapabilityRequired(QnsConstants.COVERAGE_HOME));
        assertFalse(mConfigManager.isMmtelCapabilityRequired(QnsConstants.COVERAGE_ROAM));
    }

    @Test
    public void testCheckHandoverRuleConfigChangeWithDefaultValues() {
        assertFalse(
                mConfigManager.checkHandoverRuleConfigChange(
                        null, null, CarrierConfigManager.KEY_IWLAN_HANDOVER_POLICY_STRING_ARRAY));
        assertFalse(mConfigManager.isQnsConfigChanged());
    }

    @Test
    public void testCheckHandoverRuleConfigChangeWithTestBundle() {
        PersistableBundle bundleCurrent = new PersistableBundle();
        PersistableBundle bundleNew = new PersistableBundle();
        String key = CarrierConfigManager.KEY_IWLAN_HANDOVER_POLICY_STRING_ARRAY;
        bundleCurrent.putStringArray(key, new String[] {HANDOVER_POLICY_3});
        doReturn(bundleCurrent).when(carrierConfigManager).getConfigForSubId(anyInt());
        mConfigManager.loadHandoverRules(bundleCurrent, null, key);
        assertTrue(
                mConfigManager.isHandoverAllowedByPolicy(
                        ApnSetting.TYPE_IMS,
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        QnsConstants.COVERAGE_HOME));
        assertTrue(
                mConfigManager.isHandoverAllowedByPolicy(
                        ApnSetting.TYPE_IMS,
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        QnsConstants.COVERAGE_HOME));
        assertTrue(
                mConfigManager.isHandoverAllowedByPolicy(
                        ApnSetting.TYPE_EMERGENCY,
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        QnsConstants.COVERAGE_HOME));
        assertTrue(
                mConfigManager.isHandoverAllowedByPolicy(
                        ApnSetting.TYPE_MMS,
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        QnsConstants.COVERAGE_HOME));
        assertTrue(
                mConfigManager.isHandoverAllowedByPolicy(
                        ApnSetting.TYPE_CBS,
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        QnsConstants.COVERAGE_HOME));
        assertTrue(
                mConfigManager.isHandoverAllowedByPolicy(
                        ApnSetting.TYPE_XCAP,
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        QnsConstants.COVERAGE_HOME));
        bundleNew.putStringArray(key, new String[] {HANDOVER_POLICY_4});
        doReturn(bundleNew).when(carrierConfigManager).getConfigForSubId(anyInt());
        assertTrue(mConfigManager.checkHandoverRuleConfigChange(bundleNew, null, key));
        bundleNew.clear();
        assertFalse(
                mConfigManager.isHandoverAllowedByPolicy(
                        ApnSetting.TYPE_IMS,
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        QnsConstants.COVERAGE_HOME));
        assertFalse(
                mConfigManager.isHandoverAllowedByPolicy(
                        ApnSetting.TYPE_EMERGENCY,
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        QnsConstants.COVERAGE_HOME));
        assertFalse(
                mConfigManager.isHandoverAllowedByPolicy(
                        ApnSetting.TYPE_MMS,
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        QnsConstants.COVERAGE_HOME));
        assertFalse(
                mConfigManager.isHandoverAllowedByPolicy(
                        ApnSetting.TYPE_CBS,
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        QnsConstants.COVERAGE_HOME));
        assertFalse(
                mConfigManager.isHandoverAllowedByPolicy(
                        ApnSetting.TYPE_XCAP,
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        QnsConstants.COVERAGE_HOME));
        bundleNew.putStringArray(key, new String[] {HANDOVER_POLICY_4});
        doReturn(bundleNew).when(carrierConfigManager).getConfigForSubId(anyInt());
        assertFalse(mConfigManager.checkHandoverRuleConfigChange(bundleNew, null, key));
        bundleNew.clear();
        bundleNew.putStringArray(key, new String[] {HANDOVER_POLICY_0, HANDOVER_POLICY_1});
        doReturn(bundleNew).when(carrierConfigManager).getConfigForSubId(anyInt());
        assertTrue(mConfigManager.checkHandoverRuleConfigChange(bundleNew, null, key));
    }

    @Test
    public void testCheckThresholdConfigChangeWithDefaultValues() {
        assertFalse(mConfigManager.checkThresholdConfigChange(null, null));
        assertFalse(mConfigManager.isQnsConfigChanged());
    }

    @Test
    public void testCheckThresholdConfigChangeWithTestBundle() {
        PersistableBundle bundleNew = new PersistableBundle();
        bundleNew.putIntArray(
                QnsCarrierAnspSupportConfig.KEY_VIDEO_WIFI_RSSI_INT_ARRAY, new int[] {-60, -70});
        doReturn(bundleNew).when(carrierConfigManager).getConfigForSubId(anyInt());
        assertTrue(mConfigManager.checkThresholdConfigChange(bundleNew, null));
        bundleNew.putIntArray(
                QnsCarrierAnspSupportConfig.KEY_IDLE_EUTRAN_RSRP_INT_ARRAY,
                new int[] {-90, -110, QnsCarrierConfigManager.QnsConfigArray.INVALID});
        doReturn(bundleNew).when(carrierConfigManager).getConfigForSubId(anyInt());
        assertTrue(mConfigManager.checkThresholdConfigChange(bundleNew, null));
    }

    @Test
    public void testIsVolteRoamingSupported() {
        PersistableBundle bundle = new PersistableBundle();
        bundle.putBoolean(
                CarrierConfigManager.ImsVoice.KEY_CARRIER_VOLTE_ROAMING_AVAILABLE_BOOL, false);
        doReturn(bundle).when(carrierConfigManager).getConfigForSubId(anyInt());
        mConfigManager.loadCarrierConfig(bundle, null);
        assertTrue(mConfigManager.isVolteRoamingSupported(QnsConstants.COVERAGE_HOME));
        assertFalse(mConfigManager.isVolteRoamingSupported(QnsConstants.COVERAGE_ROAM));
    }

    @Test
    public void testNeedToCheckInternationalRoaming() {
        PersistableBundle bundle = new PersistableBundle();
        bundle.putStringArray(
                QnsCarrierConfigManager
                        .KEY_APN_TYPES_WITH_INTERNATIONAL_ROAMING_CONDITION_STRING_ARRAY,
                new String[] {"ims", "mms"});
        mConfigManager.loadQnsAneSupportConfigurations(bundle, null);
        assertTrue(mConfigManager.needToCheckInternationalRoaming(ApnSetting.TYPE_IMS));
        assertFalse(mConfigManager.needToCheckInternationalRoaming(ApnSetting.TYPE_EMERGENCY));
        assertTrue(mConfigManager.needToCheckInternationalRoaming(ApnSetting.TYPE_MMS));
        assertFalse(mConfigManager.needToCheckInternationalRoaming(ApnSetting.TYPE_XCAP));
    }

    @Test
    public void testIsDefinedInternationalRoamingPlmn() {
        PersistableBundle bundle = new PersistableBundle();
        bundle.putStringArray(
                QnsCarrierConfigManager
                        .KEY_PLMN_LIST_REGARDED_AS_INTERNATIONAL_ROAMING_STRING_ARRAY,
                new String[] {"316030", "320", "272119", "25203"});
        mConfigManager.loadQnsAneSupportConfigurations(bundle, null);
        assertTrue(mConfigManager.isDefinedInternationalRoamingPlmn("32033"));
        assertFalse(mConfigManager.isDefinedInternationalRoamingPlmn("32133"));
        assertTrue(mConfigManager.isDefinedInternationalRoamingPlmn("31630"));
        assertTrue(mConfigManager.isDefinedInternationalRoamingPlmn("252003"));
        assertFalse(mConfigManager.isDefinedInternationalRoamingPlmn("252030"));
        assertTrue(mConfigManager.isDefinedInternationalRoamingPlmn("272119"));
        assertFalse(mConfigManager.isDefinedInternationalRoamingPlmn("273020"));
        assertFalse(mConfigManager.isDefinedDomesticRoamingPlmn("32033"));
    }

    @Test
    public void testIsDefinedDomesticRoamingPlmn() {
        PersistableBundle bundle = new PersistableBundle();
        bundle.putStringArray(
                QnsCarrierConfigManager.KEY_PLMN_LIST_REGARDED_AS_DOMESTIC_ROAMING_STRING_ARRAY,
                new String[] {"316030", "320", "272119", "25203"});
        mConfigManager.loadQnsAneSupportConfigurations(bundle, null);
        assertTrue(mConfigManager.isDefinedDomesticRoamingPlmn("32033"));
        assertFalse(mConfigManager.isDefinedDomesticRoamingPlmn("32133"));
        assertTrue(mConfigManager.isDefinedDomesticRoamingPlmn("31630"));
        assertTrue(mConfigManager.isDefinedDomesticRoamingPlmn("252003"));
        assertFalse(mConfigManager.isDefinedDomesticRoamingPlmn("252030"));
        assertTrue(mConfigManager.isDefinedDomesticRoamingPlmn("272119"));
        assertFalse(mConfigManager.isDefinedDomesticRoamingPlmn("273020"));
        assertFalse(mConfigManager.isDefinedInternationalRoamingPlmn("252003"));
    }

    @Test
    public void testBlockIwlanInInternationalRoamWithoutWwan() {
        // Test for the default setting
        assertFalse(mConfigManager.blockIwlanInInternationalRoamWithoutWwan());

        // Test for a new setting
        PersistableBundle bundle = new PersistableBundle();
        bundle.putBoolean(
                QnsCarrierConfigManager.KEY_BLOCK_IWLAN_IN_INTERNATIONAL_ROAMING_WITHOUT_WWAN_BOOL,
                true);
        mConfigManager.loadQnsAneSupportConfigurations(null, bundle);
        assertTrue(mConfigManager.blockIwlanInInternationalRoamWithoutWwan());
    }

    @Test
    public void testGetSubId() {
        assertEquals(QnsConstants.KEY_DEFAULT_VALUE, mConfigManager.getSubId());
    }

    @Test
    public void testGetCarrierId() {
        assertEquals(QnsConstants.KEY_DEFAULT_VALUE, mConfigManager.getCarrierId());
    }

    @After
    public void tearDown() {
        QnsCarrierConfigManager.closeQnsCarrierConfigManager(0);
    }
}