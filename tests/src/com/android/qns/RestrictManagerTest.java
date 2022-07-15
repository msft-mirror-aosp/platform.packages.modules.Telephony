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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.qns.DataConnectionStatusTracker.EVENT_DATA_CONNECTION_CONNECTED;
import static com.android.qns.DataConnectionStatusTracker.EVENT_DATA_CONNECTION_DISCONNECTED;
import static com.android.qns.DataConnectionStatusTracker.EVENT_DATA_CONNECTION_FAILED;
import static com.android.qns.DataConnectionStatusTracker.EVENT_DATA_CONNECTION_HANDOVER_FAILED;
import static com.android.qns.DataConnectionStatusTracker.EVENT_DATA_CONNECTION_HANDOVER_STARTED;
import static com.android.qns.DataConnectionStatusTracker.EVENT_DATA_CONNECTION_HANDOVER_SUCCESS;
import static com.android.qns.DataConnectionStatusTracker.EVENT_DATA_CONNECTION_STARTED;
import static com.android.qns.RestrictManager.GUARDING_TIMER_HANDOVER_INIT;
import static com.android.qns.RestrictManager.RELEASE_EVENT_CALL_END;
import static com.android.qns.RestrictManager.RELEASE_EVENT_DISCONNECT;
import static com.android.qns.RestrictManager.RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL;
import static com.android.qns.RestrictManager.RESTRICT_TYPE_FALLBACK_TO_WWAN_IMS_REGI_FAIL;
import static com.android.qns.RestrictManager.RESTRICT_TYPE_GUARDING;
import static com.android.qns.RestrictManager.RESTRICT_TYPE_NON_PREFERRED_TRANSPORT;
import static com.android.qns.RestrictManager.RESTRICT_TYPE_RESTRICT_IWLAN_CS_CALL;
import static com.android.qns.RestrictManager.RESTRICT_TYPE_RESTRICT_IWLAN_IN_CALL;
import static com.android.qns.RestrictManager.RESTRICT_TYPE_RTP_LOW_QUALITY;
import static com.android.qns.RestrictManager.RESTRICT_TYPE_THROTTLING;
import static com.android.qns.RestrictManager.sReleaseEventMap;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.test.TestLooper;
import android.telephony.AccessNetworkConstants;
import android.telephony.TelephonyManager;
import android.telephony.data.ApnSetting;
import android.telephony.ims.ImsReasonInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;

import java.util.concurrent.CountDownLatch;

@RunWith(JUnit4.class)
public class RestrictManagerTest extends QnsTest {
    @Mock private DataConnectionStatusTracker mMockDcst;
    @Mock private QnsCarrierConfigManager mConfigManager;
    @Mock private CellularNetworkStatusTracker mCellularNetworkStatusTracker;
    private MockitoSession mMockSession;
    private AlternativeEventListener mAltListener;
    private QnsTelephonyListener mTelephonyListener;

    private static final int DEFAULT_GUARDING_TIME = 30000;
    private static final int DEFAULT_RESTRICT_NON_PREFERRED_TRANSPORT_TIME = 45000;
    private static final int DEFAULT_RESTRICT_WITH_LOW_RTP_QUALITY_TIME = 60000;
    private RestrictManager mRestrictManager;
    private ImsStatusListener mImsStatusListener;
    private CountDownLatch mLatch;

    protected TestLooper mTestLooper;

    HandlerThread mHandlerThread =
            new HandlerThread("") {
                @Override
                protected void onLooperPrepared() {
                    super.onLooperPrepared();
                    mAltListener = AlternativeEventListener.getInstance(sMockContext, 0);
                    mTelephonyListener = QnsTelephonyListener.getInstance(sMockContext, 0);
                    mImsStatusListener = ImsStatusListener.getInstance(sMockContext, 0);
                    setReady(true);
                }
            };

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        super.setUp();
        mMockDcst = mock(DataConnectionStatusTracker.class);
        mConfigManager = mock(QnsCarrierConfigManager.class);
        mMockSession =
                mockitoSession()
                        .mockStatic(CellularNetworkStatusTracker.class)
                        .mockStatic(QnsUtils.class)
                        .startMocking();
        lenient()
                .when(CellularNetworkStatusTracker.getInstance(sMockContext, 0))
                .thenReturn(mCellularNetworkStatusTracker);
        when(mConfigManager.getWaitingTimerForPreferredTransportOnPowerOn(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN))
                .thenReturn(0);
        when(mConfigManager.getWaitingTimerForPreferredTransportOnPowerOn(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN))
                .thenReturn(0);
        mTestLooper = new TestLooper();
        mHandlerThread.start();
        mLatch = new CountDownLatch(1);

        waitUntilReady();
        mRestrictManager =
                new RestrictManager(
                        sMockContext,
                        mTestLooper.getLooper(),
                        0,
                        ApnSetting.TYPE_IMS,
                        mMockDcst,
                        mConfigManager,
                        mAltListener);
        // To avoid failures due to Qns Event dispatcher notifications for Wfc Settings
        mTestLooper.moveTimeForward(2000);
        mTestLooper.dispatchAll();
        assertFalse(mRestrictManager.isRestricted(AccessNetworkConstants.TRANSPORT_TYPE_WLAN));
        assertFalse(mRestrictManager.isRestricted(AccessNetworkConstants.TRANSPORT_TYPE_WWAN));
    }

    @After
    public void tearDown() {
        if (mHandlerThread != null) {
            mHandlerThread.quit();
        }
        if (mRestrictManager != null) {
            mRestrictManager.close();
        }
        if (mMockSession != null) {
            mMockSession.finishMocking();
            mMockSession = null;
        }
    }

    @Test
    public void testRequestGuardingRestrictionWithStartGuarding() {
        validateGuardingRestriction(
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
        validateGuardingRestriction(
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
    }

    private void validateGuardingRestriction(int SrcTransportType, int targetTransportType) {
        when(mMockDcst.isActiveState()).thenReturn(true);
        mRestrictManager.startGuarding(GUARDING_TIMER_HANDOVER_INIT, SrcTransportType);
        mTestLooper.moveTimeForward(DEFAULT_GUARDING_TIME / 2);
        mTestLooper.dispatchAll();
        mRestrictManager.startGuarding(GUARDING_TIMER_HANDOVER_INIT, targetTransportType);
        assertFalse(mRestrictManager.isRestricted(SrcTransportType));
        assertTrue(mRestrictManager.isRestricted(targetTransportType));
        assertTrue(
                mRestrictManager.hasRestrictionType(targetTransportType, RESTRICT_TYPE_GUARDING));
    }

    @Test
    public void testAddRestriction() {
        mRestrictManager.addRestriction(
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                RESTRICT_TYPE_NON_PREFERRED_TRANSPORT,
                sReleaseEventMap.get(RESTRICT_TYPE_NON_PREFERRED_TRANSPORT),
                DEFAULT_RESTRICT_NON_PREFERRED_TRANSPORT_TIME);
        assertTrue(mRestrictManager.isRestricted(AccessNetworkConstants.TRANSPORT_TYPE_WLAN));
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_NON_PREFERRED_TRANSPORT));
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                        RESTRICT_TYPE_NON_PREFERRED_TRANSPORT));
        mTestLooper.moveTimeForward(DEFAULT_RESTRICT_NON_PREFERRED_TRANSPORT_TIME / 2);
        mTestLooper.dispatchAll();
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_NON_PREFERRED_TRANSPORT));
        mTestLooper.moveTimeForward(DEFAULT_RESTRICT_NON_PREFERRED_TRANSPORT_TIME / 2);
        mTestLooper.dispatchAll();
        assertFalse(mRestrictManager.isRestricted(AccessNetworkConstants.TRANSPORT_TYPE_WLAN));
    }

    @Test
    public void testAddRestrictionOnRestriction() {
        mRestrictManager.addRestriction(
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                RESTRICT_TYPE_NON_PREFERRED_TRANSPORT,
                sReleaseEventMap.get(RESTRICT_TYPE_NON_PREFERRED_TRANSPORT),
                DEFAULT_RESTRICT_NON_PREFERRED_TRANSPORT_TIME);
        assertTrue(mRestrictManager.isRestricted(AccessNetworkConstants.TRANSPORT_TYPE_WLAN));
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_NON_PREFERRED_TRANSPORT));
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                        RESTRICT_TYPE_NON_PREFERRED_TRANSPORT));
        mTestLooper.moveTimeForward(DEFAULT_RESTRICT_NON_PREFERRED_TRANSPORT_TIME / 2);
        mTestLooper.dispatchAll();
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_NON_PREFERRED_TRANSPORT));
        mRestrictManager.addRestriction(
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                RESTRICT_TYPE_NON_PREFERRED_TRANSPORT,
                sReleaseEventMap.get(RESTRICT_TYPE_NON_PREFERRED_TRANSPORT),
                DEFAULT_RESTRICT_NON_PREFERRED_TRANSPORT_TIME);
        mTestLooper.moveTimeForward(DEFAULT_RESTRICT_NON_PREFERRED_TRANSPORT_TIME / 2);
        mTestLooper.dispatchAll();
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_NON_PREFERRED_TRANSPORT));
        mTestLooper.moveTimeForward(DEFAULT_RESTRICT_NON_PREFERRED_TRANSPORT_TIME / 2);
        mTestLooper.dispatchAll();
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_NON_PREFERRED_TRANSPORT));
    }

    @Test
    public void testReleaseRestriction() {
        mRestrictManager.addRestriction(
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                RESTRICT_TYPE_NON_PREFERRED_TRANSPORT,
                sReleaseEventMap.get(RESTRICT_TYPE_NON_PREFERRED_TRANSPORT),
                DEFAULT_RESTRICT_NON_PREFERRED_TRANSPORT_TIME);
        assertTrue(mRestrictManager.isRestricted(AccessNetworkConstants.TRANSPORT_TYPE_WLAN));
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_NON_PREFERRED_TRANSPORT));
        mTestLooper.moveTimeForward(DEFAULT_RESTRICT_NON_PREFERRED_TRANSPORT_TIME / 2);
        mTestLooper.dispatchAll();
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_NON_PREFERRED_TRANSPORT));
        mRestrictManager.releaseRestriction(
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN, RESTRICT_TYPE_NON_PREFERRED_TRANSPORT);
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_NON_PREFERRED_TRANSPORT));
        mRestrictManager.addRestriction(
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                RESTRICT_TYPE_NON_PREFERRED_TRANSPORT,
                sReleaseEventMap.get(RESTRICT_TYPE_NON_PREFERRED_TRANSPORT),
                DEFAULT_RESTRICT_NON_PREFERRED_TRANSPORT_TIME);
        mTestLooper.moveTimeForward(DEFAULT_RESTRICT_NON_PREFERRED_TRANSPORT_TIME / 2);
        mTestLooper.dispatchAll();
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_NON_PREFERRED_TRANSPORT));
        mTestLooper.moveTimeForward(DEFAULT_RESTRICT_NON_PREFERRED_TRANSPORT_TIME / 2);
        mTestLooper.dispatchAll();
        assertFalse(mRestrictManager.isRestricted(AccessNetworkConstants.TRANSPORT_TYPE_WLAN));
    }

    @Test
    public void testProcessReleaseEvent() {
        assertFalse(mRestrictManager.isRestricted(AccessNetworkConstants.TRANSPORT_TYPE_WLAN));
        assertFalse(mRestrictManager.isRestricted(AccessNetworkConstants.TRANSPORT_TYPE_WWAN));
        mRestrictManager.addRestriction(
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                RESTRICT_TYPE_RTP_LOW_QUALITY,
                sReleaseEventMap.get(RESTRICT_TYPE_RTP_LOW_QUALITY),
                DEFAULT_RESTRICT_WITH_LOW_RTP_QUALITY_TIME);
        mRestrictManager.addRestriction(
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                RESTRICT_TYPE_RTP_LOW_QUALITY,
                sReleaseEventMap.get(RESTRICT_TYPE_RTP_LOW_QUALITY),
                DEFAULT_RESTRICT_WITH_LOW_RTP_QUALITY_TIME);
        mRestrictManager.addRestriction(
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                RESTRICT_TYPE_RESTRICT_IWLAN_IN_CALL,
                sReleaseEventMap.get(RESTRICT_TYPE_RESTRICT_IWLAN_IN_CALL),
                0);
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN, RESTRICT_TYPE_RTP_LOW_QUALITY));
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_RTP_LOW_QUALITY));
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_RESTRICT_IWLAN_IN_CALL));
        mTestLooper.moveTimeForward(DEFAULT_RESTRICT_WITH_LOW_RTP_QUALITY_TIME / 2);
        mTestLooper.dispatchAll();
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN, RESTRICT_TYPE_RTP_LOW_QUALITY));
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_RTP_LOW_QUALITY));
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_RESTRICT_IWLAN_IN_CALL));
        mRestrictManager.processReleaseEvent(
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN, RELEASE_EVENT_CALL_END);
        mRestrictManager.processReleaseEvent(
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RELEASE_EVENT_CALL_END);
        assertFalse(mRestrictManager.isRestricted(AccessNetworkConstants.TRANSPORT_TYPE_WLAN));
        assertFalse(mRestrictManager.isRestricted(AccessNetworkConstants.TRANSPORT_TYPE_WWAN));
    }

    @Test
    public void testAddRestrictionWithoutReleaseTime() {
        assertFalse(mRestrictManager.isRestricted(AccessNetworkConstants.TRANSPORT_TYPE_WLAN));
        mRestrictManager.addRestriction(
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                RESTRICT_TYPE_RESTRICT_IWLAN_IN_CALL,
                sReleaseEventMap.get(RESTRICT_TYPE_RESTRICT_IWLAN_IN_CALL),
                0);
        mTestLooper.moveTimeForward(DEFAULT_RESTRICT_WITH_LOW_RTP_QUALITY_TIME * 10);
        mTestLooper.dispatchAll();
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_RESTRICT_IWLAN_IN_CALL));
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                        RESTRICT_TYPE_RESTRICT_IWLAN_IN_CALL));
        mRestrictManager.releaseRestriction(
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN, RESTRICT_TYPE_RESTRICT_IWLAN_IN_CALL);
        assertFalse(mRestrictManager.isRestricted(AccessNetworkConstants.TRANSPORT_TYPE_WLAN));
    }

    @Test
    public void testGuardingWithHandoverComplete() {
        when(mConfigManager.isHysteresisTimerEnabled(QnsConstants.COVERAGE_HOME)).thenReturn(true);
        when(mConfigManager.getWwanHysteresisTimer(
                        ApnSetting.TYPE_IMS, QnsConstants.CALL_TYPE_VOICE))
                .thenReturn(90000);
        mRestrictManager.setCellularCoverage(QnsConstants.COVERAGE_HOME);
        mRestrictManager.setQnsCallType(QnsConstants.CALL_TYPE_VOICE);
        DataConnectionStatusTracker.DataConnectionChangedInfo dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_HANDOVER_SUCCESS,
                        DataConnectionStatusTracker.STATE_CONNECTED,
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_GUARDING));
        assertFalse(mRestrictManager.isRestricted(AccessNetworkConstants.TRANSPORT_TYPE_WLAN));
        mTestLooper.moveTimeForward(85000);
        mTestLooper.dispatchAll();
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_GUARDING));
        assertFalse(mRestrictManager.isRestricted(AccessNetworkConstants.TRANSPORT_TYPE_WLAN));
        mTestLooper.moveTimeForward(5000);
        mTestLooper.dispatchAll();
        assertFalse(mRestrictManager.isRestricted(AccessNetworkConstants.TRANSPORT_TYPE_WWAN));
        assertFalse(mRestrictManager.isRestricted(AccessNetworkConstants.TRANSPORT_TYPE_WLAN));
    }

    @Test
    public void testWwanToWlanGuardingOnHandoverStart() {
        DataConnectionStatusTracker.DataConnectionChangedInfo dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_HANDOVER_STARTED,
                        DataConnectionStatusTracker.STATE_HANDOVER,
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN, RESTRICT_TYPE_GUARDING));
        assertTrue(mRestrictManager.isRestricted(AccessNetworkConstants.TRANSPORT_TYPE_WWAN));
        mTestLooper.moveTimeForward(15000);
        mTestLooper.dispatchAll();
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_GUARDING));
        dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_HANDOVER_FAILED,
                        DataConnectionStatusTracker.STATE_CONNECTED,
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        assertFalse(mRestrictManager.isRestricted(AccessNetworkConstants.TRANSPORT_TYPE_WWAN));
    }

    @Test
    public void testWlanToWwanGuardingOnHandoverStart() {
        DataConnectionStatusTracker.DataConnectionChangedInfo dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_HANDOVER_STARTED,
                        DataConnectionStatusTracker.STATE_HANDOVER,
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_GUARDING));
        assertTrue(mRestrictManager.isRestricted(AccessNetworkConstants.TRANSPORT_TYPE_WLAN));
        mTestLooper.moveTimeForward(15000);
        mTestLooper.dispatchAll();
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN, RESTRICT_TYPE_GUARDING));
        dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_HANDOVER_FAILED,
                        DataConnectionStatusTracker.STATE_CONNECTED,
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        assertFalse(mRestrictManager.isRestricted(AccessNetworkConstants.TRANSPORT_TYPE_WLAN));
    }

    @Test
    public void testWlanOnLowRtpQualityEvent() {
        when(mConfigManager.isHysteresisTimerEnabled(QnsConstants.COVERAGE_HOME)).thenReturn(true);
        when(mConfigManager.getHoRestrictedTimeOnLowRTPQuality(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN))
                .thenReturn(30000);
        when(mConfigManager.getQnsMaxIwlanHoCountDuringCall()).thenReturn(1);
        when(mConfigManager.getQnsIwlanHoRestrictReason())
                .thenReturn(QnsConstants.FALLBACK_REASON_RTP_ONLY);
        QnsCarrierConfigManager.RtpMetricsConfig config =
                new QnsCarrierConfigManager.RtpMetricsConfig(0, 0, 0, 0);
        when(mConfigManager.getRTPMetricsData()).thenReturn(config);
        when(mConfigManager.getWwanHysteresisTimer(
                        ApnSetting.TYPE_IMS, QnsConstants.CALL_TYPE_VOICE))
                .thenReturn(0);
        mRestrictManager.setCellularCoverage(QnsConstants.COVERAGE_HOME);
        mRestrictManager.setQnsCallType(QnsConstants.CALL_TYPE_VOICE);
        DataConnectionStatusTracker.DataConnectionChangedInfo dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_HANDOVER_SUCCESS,
                        DataConnectionStatusTracker.STATE_CONNECTED,
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN, RESTRICT_TYPE_RTP_LOW_QUALITY));
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_RESTRICT_IWLAN_IN_CALL));
        mTestLooper.moveTimeForward(1000);
        mTestLooper.dispatchAll();
        mAltListener.notifyRtpLowQuality(QnsConstants.CALL_TYPE_VOICE, 1);
        mTestLooper.dispatchAll();
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN, RESTRICT_TYPE_RTP_LOW_QUALITY));
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_RESTRICT_IWLAN_IN_CALL));
        assertFalse(mRestrictManager.isRestricted(AccessNetworkConstants.TRANSPORT_TYPE_WWAN));
        mTestLooper.moveTimeForward(30000);
        mTestLooper.dispatchAll();
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN, RESTRICT_TYPE_RTP_LOW_QUALITY));
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_RESTRICT_IWLAN_IN_CALL));
        mRestrictManager.setQnsCallType(QnsConstants.CALL_TYPE_IDLE);
        assertFalse(mRestrictManager.isRestricted(AccessNetworkConstants.TRANSPORT_TYPE_WLAN));
        assertFalse(mRestrictManager.isRestricted(AccessNetworkConstants.TRANSPORT_TYPE_WWAN));
    }

    @Test
    public void testWwanOnLowRtpQualityEvent() {
        when(mConfigManager.isHysteresisTimerEnabled(QnsConstants.COVERAGE_HOME)).thenReturn(true);
        when(mConfigManager.getHoRestrictedTimeOnLowRTPQuality(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN))
                .thenReturn(30000);
        QnsCarrierConfigManager.RtpMetricsConfig config =
                new QnsCarrierConfigManager.RtpMetricsConfig(0, 0, 0, 0);
        when(mConfigManager.getRTPMetricsData()).thenReturn(config);
        when(mConfigManager.getWlanHysteresisTimer(
                        ApnSetting.TYPE_IMS, QnsConstants.CALL_TYPE_VOICE))
                .thenReturn(0);
        mRestrictManager.setCellularCoverage(QnsConstants.COVERAGE_HOME);
        mRestrictManager.setQnsCallType(QnsConstants.CALL_TYPE_VOICE);
        DataConnectionStatusTracker.DataConnectionChangedInfo dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_HANDOVER_SUCCESS,
                        DataConnectionStatusTracker.STATE_CONNECTED,
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_RTP_LOW_QUALITY));
        assertFalse(mRestrictManager.isRestricted(AccessNetworkConstants.TRANSPORT_TYPE_WLAN));
        mTestLooper.moveTimeForward(1000);
        mTestLooper.dispatchAll();
        mAltListener.notifyRtpLowQuality(QnsConstants.CALL_TYPE_VOICE, 1);
        mTestLooper.dispatchAll();
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_RTP_LOW_QUALITY));
        assertFalse(mRestrictManager.isRestricted(AccessNetworkConstants.TRANSPORT_TYPE_WLAN));
        mTestLooper.moveTimeForward(30000);
        mTestLooper.dispatchAll();
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_RTP_LOW_QUALITY));
        mRestrictManager.setQnsCallType(QnsConstants.CALL_TYPE_IDLE);
        assertFalse(mRestrictManager.isRestricted(AccessNetworkConstants.TRANSPORT_TYPE_WLAN));
        assertFalse(mRestrictManager.isRestricted(AccessNetworkConstants.TRANSPORT_TYPE_WWAN));
    }

    @Test
    public void testRestrictWithThrottling() {
        long throttleTime = SystemClock.elapsedRealtime() + 12000;
        when(mConfigManager.isHysteresisTimerEnabled(QnsConstants.COVERAGE_HOME)).thenReturn(true);
        when(mConfigManager.getWwanHysteresisTimer(
                        ApnSetting.TYPE_IMS, QnsConstants.CALL_TYPE_VOICE))
                .thenReturn(90000);
        mRestrictManager.setCellularCoverage(QnsConstants.COVERAGE_HOME);
        mRestrictManager.setQnsCallType(QnsConstants.CALL_TYPE_VOICE);
        DataConnectionStatusTracker.DataConnectionChangedInfo dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_HANDOVER_SUCCESS,
                        DataConnectionStatusTracker.STATE_CONNECTED,
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        mRestrictManager.notifyThrottling(
                true, throttleTime, AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_GUARDING));
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_THROTTLING));
        mRestrictManager.onDataConnectionChanged(dcInfo);
        dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_DISCONNECTED,
                        DataConnectionStatusTracker.STATE_INACTIVE,
                        AccessNetworkConstants.TRANSPORT_TYPE_INVALID);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_GUARDING));
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_THROTTLING));
        mTestLooper.moveTimeForward(13000);
        mTestLooper.dispatchAll();
        assertFalse(mRestrictManager.isRestricted(AccessNetworkConstants.TRANSPORT_TYPE_WWAN));
    }

    @Test
    public void testRestrictWithThrottlingForRetryTimerAsZero() {
        long throttleTime = SystemClock.elapsedRealtime();
        DataConnectionStatusTracker.DataConnectionChangedInfo dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_HANDOVER_SUCCESS,
                        DataConnectionStatusTracker.STATE_CONNECTED,
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        mRestrictManager.notifyThrottling(
                true, throttleTime, AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_THROTTLING));
    }

    @Test
    public void testDeferThrottlingDuringActiveState() {
        long throttleTime = SystemClock.elapsedRealtime() + 10000;
        DataConnectionStatusTracker.DataConnectionChangedInfo dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_CONNECTED,
                        DataConnectionStatusTracker.STATE_CONNECTED,
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        when(mMockDcst.isActiveState()).thenReturn(true);
        mRestrictManager.notifyThrottling(
                true, throttleTime, AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN, RESTRICT_TYPE_THROTTLING));
        mTestLooper.moveTimeForward(11000);
        mTestLooper.dispatchAll();
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN, RESTRICT_TYPE_THROTTLING));
        dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_DISCONNECTED,
                        DataConnectionStatusTracker.STATE_INACTIVE,
                        AccessNetworkConstants.TRANSPORT_TYPE_INVALID);
        when(mMockDcst.isActiveState()).thenReturn(false);
        long currentTime = SystemClock.elapsedRealtime() + 11000;
        lenient().when(QnsUtils.getSystemElapsedRealTime()).thenReturn(currentTime);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN, RESTRICT_TYPE_THROTTLING));
        dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_CONNECTED,
                        DataConnectionStatusTracker.STATE_CONNECTED,
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        when(mMockDcst.isActiveState()).thenReturn(true);
        throttleTime = SystemClock.elapsedRealtime() + 60000;
        mRestrictManager.notifyThrottling(
                true, throttleTime, AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN, RESTRICT_TYPE_THROTTLING));
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_THROTTLING));
        mTestLooper.moveTimeForward(11000);
        mTestLooper.dispatchAll();
        mRestrictManager.notifyThrottling(false, 0, AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        mTestLooper.moveTimeForward(5000);
        mTestLooper.dispatchAll();
        dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_DISCONNECTED,
                        DataConnectionStatusTracker.STATE_INACTIVE,
                        AccessNetworkConstants.TRANSPORT_TYPE_INVALID);
        when(mMockDcst.isActiveState()).thenReturn(false);
        currentTime = SystemClock.elapsedRealtime() + 16000;
        lenient().when(QnsUtils.getSystemElapsedRealTime()).thenReturn(currentTime);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN, RESTRICT_TYPE_THROTTLING));
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_THROTTLING));
    }

    @Test
    public void testProcessDeferredThrottlingAtDisconnected() {
        long throttleTime = SystemClock.elapsedRealtime() + 60000;
        DataConnectionStatusTracker.DataConnectionChangedInfo dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_CONNECTED,
                        DataConnectionStatusTracker.STATE_CONNECTED,
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        when(mMockDcst.isActiveState()).thenReturn(true);
        mRestrictManager.notifyThrottling(
                true, throttleTime, AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_THROTTLING));
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN, RESTRICT_TYPE_THROTTLING));
        mTestLooper.moveTimeForward(10000);
        mTestLooper.dispatchAll();
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_THROTTLING));
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN, RESTRICT_TYPE_THROTTLING));
        dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_DISCONNECTED,
                        DataConnectionStatusTracker.STATE_INACTIVE,
                        AccessNetworkConstants.TRANSPORT_TYPE_INVALID);
        when(mMockDcst.isActiveState()).thenReturn(false);
        long currentTime = SystemClock.elapsedRealtime() + 10000;
        lenient().when(QnsUtils.getSystemElapsedRealTime()).thenReturn(currentTime);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_THROTTLING));
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN, RESTRICT_TYPE_THROTTLING));
        mTestLooper.moveTimeForward(45000);
        mTestLooper.dispatchAll();
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_THROTTLING));
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN, RESTRICT_TYPE_THROTTLING));
        mTestLooper.moveTimeForward(10000);
        mTestLooper.dispatchAll();
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_THROTTLING));
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN, RESTRICT_TYPE_THROTTLING));
    }

    @Test
    public void testAllowRestrictionOnSingleNetwork() {
        mRestrictManager.addRestriction(
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                RESTRICT_TYPE_RESTRICT_IWLAN_IN_CALL,
                sReleaseEventMap.get(RESTRICT_TYPE_RESTRICT_IWLAN_IN_CALL),
                0);
        mRestrictManager.addRestriction(
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                RESTRICT_TYPE_RTP_LOW_QUALITY,
                sReleaseEventMap.get(RESTRICT_TYPE_RTP_LOW_QUALITY),
                DEFAULT_RESTRICT_WITH_LOW_RTP_QUALITY_TIME);
        mRestrictManager.addRestriction(
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                RESTRICT_TYPE_RTP_LOW_QUALITY,
                sReleaseEventMap.get(RESTRICT_TYPE_RTP_LOW_QUALITY),
                DEFAULT_RESTRICT_WITH_LOW_RTP_QUALITY_TIME);
        mRestrictManager.addRestriction(
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                RESTRICT_TYPE_GUARDING,
                sReleaseEventMap.get(RESTRICT_TYPE_GUARDING),
                DEFAULT_GUARDING_TIME);
        mRestrictManager.addRestriction(
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                RESTRICT_TYPE_THROTTLING,
                sReleaseEventMap.get(RESTRICT_TYPE_THROTTLING),
                600000);
        mRestrictManager.addRestriction(
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                RESTRICT_TYPE_FALLBACK_TO_WWAN_IMS_REGI_FAIL,
                sReleaseEventMap.get(RESTRICT_TYPE_FALLBACK_TO_WWAN_IMS_REGI_FAIL),
                600000);
        mRestrictManager.addRestriction(
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL,
                sReleaseEventMap.get(RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL),
                600000);
        assertTrue(mRestrictManager.isRestricted(AccessNetworkConstants.TRANSPORT_TYPE_WWAN));
        assertTrue(mRestrictManager.isRestricted(AccessNetworkConstants.TRANSPORT_TYPE_WLAN));
        assertTrue(
                mRestrictManager.isAllowedOnSingleTransport(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN));
        assertFalse(
                mRestrictManager.isAllowedOnSingleTransport(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN));
        mTestLooper.moveTimeForward(DEFAULT_RESTRICT_WITH_LOW_RTP_QUALITY_TIME);
        mTestLooper.dispatchAll();
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_RESTRICT_IWLAN_IN_CALL));
        mRestrictManager.setQnsCallType(QnsConstants.CALL_TYPE_IDLE);
        mRestrictManager.notifyThrottling(false, 0, AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        mRestrictManager.processReleaseEvent(
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN, RELEASE_EVENT_DISCONNECT);
        assertFalse(mRestrictManager.isRestricted(AccessNetworkConstants.TRANSPORT_TYPE_WLAN));
        assertFalse(mRestrictManager.isRestricted(AccessNetworkConstants.TRANSPORT_TYPE_WWAN));
    }

    @Test
    public void testRestrictNonPreferredTransport() {
        mRestrictManager.addRestriction(
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                RESTRICT_TYPE_NON_PREFERRED_TRANSPORT,
                sReleaseEventMap.get(RESTRICT_TYPE_NON_PREFERRED_TRANSPORT),
                45000);
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_NON_PREFERRED_TRANSPORT));
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                        RESTRICT_TYPE_NON_PREFERRED_TRANSPORT));
        assertFalse(
                mRestrictManager.isAllowedOnSingleTransport(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN));
        DataConnectionStatusTracker.DataConnectionChangedInfo dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_CONNECTED,
                        DataConnectionStatusTracker.STATE_CONNECTED,
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_NON_PREFERRED_TRANSPORT));
    }

    @Test
    public void testRestrictIwlanCsCall() {
        when(mConfigManager.allowImsOverIwlanCellularLimitedCase()).thenReturn(false);
        DataConnectionStatusTracker.DataConnectionChangedInfo dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_DISCONNECTED,
                        DataConnectionStatusTracker.STATE_INACTIVE,
                        AccessNetworkConstants.TRANSPORT_TYPE_INVALID);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        mRestrictManager.setCellularAccessNetwork(AccessNetworkConstants.AccessNetworkType.UTRAN);
        mTelephonyListener.onCallStateChanged(TelephonyManager.CALL_STATE_OFFHOOK);
        mTestLooper.dispatchAll();
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_RESTRICT_IWLAN_CS_CALL));
        mTelephonyListener.onCallStateChanged(TelephonyManager.CALL_STATE_IDLE);
        mTestLooper.dispatchAll();
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_RESTRICT_IWLAN_CS_CALL));
    }

    @Test
    public void testSrvccHandoverCompleted() {
        when(mConfigManager.allowImsOverIwlanCellularLimitedCase()).thenReturn(false);
        DataConnectionStatusTracker.DataConnectionChangedInfo dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_CONNECTED,
                        DataConnectionStatusTracker.STATE_CONNECTED,
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        mRestrictManager.setCellularAccessNetwork(AccessNetworkConstants.AccessNetworkType.EUTRAN);
        mRestrictManager.setQnsCallType(QnsConstants.CALL_TYPE_VOICE);
        mTelephonyListener.onCallStateChanged(TelephonyManager.CALL_STATE_OFFHOOK);
        mTestLooper.dispatchAll();
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_RESTRICT_IWLAN_CS_CALL));
        mTelephonyListener.onSrvccStateChanged(TelephonyManager.SRVCC_STATE_HANDOVER_STARTED);
        mTestLooper.dispatchAll();
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_RESTRICT_IWLAN_CS_CALL));
        mTelephonyListener.onSrvccStateChanged(TelephonyManager.SRVCC_STATE_HANDOVER_COMPLETED);
        mTestLooper.dispatchAll();
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_RESTRICT_IWLAN_CS_CALL));
        mTelephonyListener.onCallStateChanged(TelephonyManager.CALL_STATE_IDLE);
        mTestLooper.dispatchAll();
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_RESTRICT_IWLAN_CS_CALL));
    }

    @Test
    public void testSrvccHandoverFailed() {
        when(mConfigManager.allowImsOverIwlanCellularLimitedCase()).thenReturn(false);
        DataConnectionStatusTracker.DataConnectionChangedInfo dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_CONNECTED,
                        DataConnectionStatusTracker.STATE_CONNECTED,
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        mRestrictManager.setCellularAccessNetwork(AccessNetworkConstants.AccessNetworkType.EUTRAN);
        mRestrictManager.setQnsCallType(QnsConstants.CALL_TYPE_VOICE);
        mTelephonyListener.onCallStateChanged(TelephonyManager.CALL_STATE_OFFHOOK);
        mTestLooper.dispatchAll();
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_RESTRICT_IWLAN_CS_CALL));
        mTelephonyListener.onSrvccStateChanged(TelephonyManager.SRVCC_STATE_HANDOVER_STARTED);
        mTestLooper.dispatchAll();
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_RESTRICT_IWLAN_CS_CALL));
        mTelephonyListener.onSrvccStateChanged(TelephonyManager.SRVCC_STATE_HANDOVER_FAILED);
        mTestLooper.dispatchAll();
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_RESTRICT_IWLAN_CS_CALL));
        mTelephonyListener.onSrvccStateChanged(TelephonyManager.SRVCC_STATE_HANDOVER_STARTED);
        mTestLooper.dispatchAll();
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_RESTRICT_IWLAN_CS_CALL));
        mTelephonyListener.onCallStateChanged(TelephonyManager.CALL_STATE_IDLE);
        mTestLooper.dispatchAll();
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_RESTRICT_IWLAN_CS_CALL));
        mTelephonyListener.onSrvccStateChanged(TelephonyManager.SRVCC_STATE_HANDOVER_COMPLETED);
        mTestLooper.dispatchAll();
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_RESTRICT_IWLAN_CS_CALL));
    }

    @Test
    public void testImsRegistrationUnregistered() {
        when(mConfigManager.allowImsOverIwlanCellularLimitedCase()).thenReturn(false);
        when(mConfigManager.isAccessNetworkAllowed(
                        AccessNetworkConstants.AccessNetworkType.EUTRAN, ApnSetting.TYPE_IMS))
                .thenReturn(true);
        when(mConfigManager.isAccessNetworkAllowed(
                        AccessNetworkConstants.AccessNetworkType.UTRAN, ApnSetting.TYPE_IMS))
                .thenReturn(false);
        when(mConfigManager.getFallbackTimeImsUnreigstered(
                        eq(ImsReasonInfo.CODE_SIP_BUSY), anyInt()))
                .thenReturn(60000);
        mRestrictManager.setCellularAccessNetwork(AccessNetworkConstants.AccessNetworkType.EUTRAN);
        DataConnectionStatusTracker.DataConnectionChangedInfo dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_CONNECTED,
                        DataConnectionStatusTracker.STATE_CONNECTED,
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        mImsStatusListener.notifyImsRegistrationChangedEvent(
                QnsConstants.IMS_REGISTRATION_CHANGED_REGISTERED,
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                null);
        mTestLooper.dispatchAll();
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_FALLBACK_TO_WWAN_IMS_REGI_FAIL));
        ImsReasonInfo reason = new ImsReasonInfo();
        reason.mCode = ImsReasonInfo.CODE_SIP_BUSY;
        mImsStatusListener.notifyImsRegistrationChangedEvent(
                QnsConstants.IMS_REGISTRATION_CHANGED_UNREGISTERED,
                AccessNetworkConstants.TRANSPORT_TYPE_INVALID,
                reason);
        mTestLooper.dispatchAll();
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_FALLBACK_TO_WWAN_IMS_REGI_FAIL));
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                        RESTRICT_TYPE_FALLBACK_TO_WWAN_IMS_REGI_FAIL));
        mTestLooper.moveTimeForward(55000);
        mTestLooper.dispatchAll();
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_FALLBACK_TO_WWAN_IMS_REGI_FAIL));
        mRestrictManager.setCellularAccessNetwork(AccessNetworkConstants.AccessNetworkType.UTRAN);
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_FALLBACK_TO_WWAN_IMS_REGI_FAIL));
    }

    @Test
    public void testImsHoRegisterFailed() {
        when(mConfigManager.isAccessNetworkAllowed(
                        AccessNetworkConstants.AccessNetworkType.EUTRAN, ApnSetting.TYPE_IMS))
                .thenReturn(true);
        when(mConfigManager.isAccessNetworkAllowed(
                        AccessNetworkConstants.AccessNetworkType.UTRAN, ApnSetting.TYPE_IMS))
                .thenReturn(false);
        when(mConfigManager.getFallbackTimeImsHoRegisterFailed(
                        eq(ImsReasonInfo.CODE_SIP_TEMPRARILY_UNAVAILABLE), anyInt()))
                .thenReturn(30000);
        mRestrictManager.setCellularAccessNetwork(AccessNetworkConstants.AccessNetworkType.EUTRAN);
        DataConnectionStatusTracker.DataConnectionChangedInfo dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_CONNECTED,
                        DataConnectionStatusTracker.STATE_CONNECTED,
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_HANDOVER_SUCCESS,
                        DataConnectionStatusTracker.STATE_CONNECTED,
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_FALLBACK_TO_WWAN_IMS_REGI_FAIL));
        ImsReasonInfo reason = new ImsReasonInfo();
        reason.mCode = ImsReasonInfo.CODE_SIP_TEMPRARILY_UNAVAILABLE;
        mImsStatusListener.notifyImsRegistrationChangedEvent(
                QnsConstants.IMS_REGISTRATION_CHANGED_ACCESS_NETWORK_CHANGE_FAILED,
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                reason);
        mTestLooper.dispatchAll();
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_FALLBACK_TO_WWAN_IMS_REGI_FAIL));
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                        RESTRICT_TYPE_FALLBACK_TO_WWAN_IMS_REGI_FAIL));
        mTestLooper.moveTimeForward(25000);
        mTestLooper.dispatchAll();
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_FALLBACK_TO_WWAN_IMS_REGI_FAIL));
        dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_DISCONNECTED,
                        DataConnectionStatusTracker.STATE_INACTIVE,
                        AccessNetworkConstants.TRANSPORT_TYPE_INVALID);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_FALLBACK_TO_WWAN_IMS_REGI_FAIL));
    }

    @Test
    public void testReleasePdnfallbacktoWwanOnImsRegOnWlanRegistered() {
        long throttleTime = SystemClock.elapsedRealtime() + 12000;
        when(mConfigManager.allowImsOverIwlanCellularLimitedCase()).thenReturn(false);
        when(mConfigManager.isAccessNetworkAllowed(
                        AccessNetworkConstants.AccessNetworkType.EUTRAN, ApnSetting.TYPE_IMS))
                .thenReturn(true);
        when(mConfigManager.getFallbackTimeImsUnreigstered(
                        eq(ImsReasonInfo.CODE_SIP_BUSY), anyInt()))
                .thenReturn(60000);
        mRestrictManager.setCellularAccessNetwork(AccessNetworkConstants.AccessNetworkType.EUTRAN);
        mRestrictManager.notifyThrottling(
                true, throttleTime, AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_THROTTLING));
        DataConnectionStatusTracker.DataConnectionChangedInfo dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_CONNECTED,
                        DataConnectionStatusTracker.STATE_CONNECTED,
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        mImsStatusListener.notifyImsRegistrationChangedEvent(
                QnsConstants.IMS_REGISTRATION_CHANGED_REGISTERED,
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                null);
        mTestLooper.dispatchAll();
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_FALLBACK_TO_WWAN_IMS_REGI_FAIL));
        ImsReasonInfo reason = new ImsReasonInfo();
        reason.mCode = ImsReasonInfo.CODE_SIP_BUSY;
        mImsStatusListener.notifyImsRegistrationChangedEvent(
                QnsConstants.IMS_REGISTRATION_CHANGED_UNREGISTERED,
                AccessNetworkConstants.TRANSPORT_TYPE_INVALID,
                reason);
        mTestLooper.dispatchAll();
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_FALLBACK_TO_WWAN_IMS_REGI_FAIL));
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                        RESTRICT_TYPE_FALLBACK_TO_WWAN_IMS_REGI_FAIL));
        mImsStatusListener.notifyImsRegistrationChangedEvent(
                QnsConstants.IMS_REGISTRATION_CHANGED_REGISTERED,
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                reason);
        mTestLooper.dispatchAll();
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_FALLBACK_TO_WWAN_IMS_REGI_FAIL));
    }

    @Test
    public void testHandoverHystOfVoiceGreaterThanIdleState() {
        when(mConfigManager.isHysteresisTimerEnabled(QnsConstants.COVERAGE_HOME)).thenReturn(true);
        when(mConfigManager.getWwanHysteresisTimer(
                        ApnSetting.TYPE_IMS, QnsConstants.CALL_TYPE_IDLE))
                .thenReturn(30000);
        mRestrictManager.setCellularCoverage(QnsConstants.COVERAGE_HOME);
        DataConnectionStatusTracker.DataConnectionChangedInfo dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_HANDOVER_SUCCESS,
                        DataConnectionStatusTracker.STATE_CONNECTED,
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_GUARDING));
        when(mConfigManager.getWwanHysteresisTimer(
                        ApnSetting.TYPE_IMS, QnsConstants.CALL_TYPE_VOICE))
                .thenReturn(45000);
        mRestrictManager.setQnsCallType(QnsConstants.CALL_TYPE_VOICE);
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_GUARDING));
        assertTrue(
                (int)
                                mRestrictManager.getRemainingGuardTimer(
                                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN)
                        > 40000);
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_GUARDING));
    }

    @Test
    public void testHandoverHystGreaterBetweenStates() {
        when(mConfigManager.isHysteresisTimerEnabled(QnsConstants.COVERAGE_HOME)).thenReturn(true);
        when(mConfigManager.getWwanHysteresisTimer(
                        ApnSetting.TYPE_IMS, QnsConstants.CALL_TYPE_IDLE))
                .thenReturn(30000);
        mRestrictManager.setCellularCoverage(QnsConstants.COVERAGE_HOME);
        DataConnectionStatusTracker.DataConnectionChangedInfo dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_HANDOVER_SUCCESS,
                        DataConnectionStatusTracker.STATE_CONNECTED,
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_GUARDING));
        when(mConfigManager.getWwanHysteresisTimer(
                        ApnSetting.TYPE_IMS, QnsConstants.CALL_TYPE_VOICE))
                .thenReturn(45000);
        mRestrictManager.setQnsCallType(QnsConstants.CALL_TYPE_VOICE);
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_GUARDING));
        assertTrue(
                (int)
                                mRestrictManager.getRemainingGuardTimer(
                                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN)
                        > 40000);
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_GUARDING));
        when(mConfigManager.getWwanHysteresisTimer(
                        ApnSetting.TYPE_IMS, QnsConstants.CALL_TYPE_VIDEO))
                .thenReturn(80000);
        mRestrictManager.setQnsCallType(QnsConstants.CALL_TYPE_VIDEO);
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_GUARDING));
        assertTrue(
                (int)
                                mRestrictManager.getRemainingGuardTimer(
                                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN)
                        > 75000);
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_GUARDING));
    }

    @Test
    public void testHandoverHystWithZeroConfigBetweenCallStates() {
        when(mConfigManager.isHysteresisTimerEnabled(QnsConstants.COVERAGE_HOME)).thenReturn(true);
        when(mConfigManager.getWwanHysteresisTimer(
                        ApnSetting.TYPE_IMS, QnsConstants.CALL_TYPE_IDLE))
                .thenReturn(30000);
        mRestrictManager.setCellularCoverage(QnsConstants.COVERAGE_HOME);
        DataConnectionStatusTracker.DataConnectionChangedInfo dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_HANDOVER_SUCCESS,
                        DataConnectionStatusTracker.STATE_CONNECTED,
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_GUARDING));
        when(mConfigManager.getWwanHysteresisTimer(
                        ApnSetting.TYPE_IMS, QnsConstants.CALL_TYPE_VOICE))
                .thenReturn(0);
        mRestrictManager.setQnsCallType(QnsConstants.CALL_TYPE_VOICE);
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_GUARDING));
    }

    @Test
    public void testHandoverHystOfVoicelessThanIdleState() {
        when(mConfigManager.isHysteresisTimerEnabled(QnsConstants.COVERAGE_HOME)).thenReturn(true);
        when(mConfigManager.getWwanHysteresisTimer(
                        ApnSetting.TYPE_IMS, QnsConstants.CALL_TYPE_IDLE))
                .thenReturn(60000);
        mRestrictManager.setCellularCoverage(QnsConstants.COVERAGE_HOME);
        DataConnectionStatusTracker.DataConnectionChangedInfo dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_HANDOVER_SUCCESS,
                        DataConnectionStatusTracker.STATE_CONNECTED,
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_GUARDING));
        when(mConfigManager.getWwanHysteresisTimer(
                        ApnSetting.TYPE_IMS, QnsConstants.CALL_TYPE_VOICE))
                .thenReturn(30000);
        mRestrictManager.setQnsCallType(QnsConstants.CALL_TYPE_VOICE);
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_GUARDING));
        assertTrue(
                (int)
                                mRestrictManager.getRemainingGuardTimer(
                                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN)
                        <= 30000);
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_GUARDING));
    }

    @Test
    public void testHandoverHystEqualBetweenStates() {
        when(mConfigManager.isHysteresisTimerEnabled(QnsConstants.COVERAGE_HOME)).thenReturn(true);
        when(mConfigManager.getWwanHysteresisTimer(
                        ApnSetting.TYPE_IMS, QnsConstants.CALL_TYPE_IDLE))
                .thenReturn(120000);
        mRestrictManager.setCellularCoverage(QnsConstants.COVERAGE_HOME);
        DataConnectionStatusTracker.DataConnectionChangedInfo dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_HANDOVER_SUCCESS,
                        DataConnectionStatusTracker.STATE_CONNECTED,
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_GUARDING));
        when(mConfigManager.getWwanHysteresisTimer(
                        ApnSetting.TYPE_IMS, QnsConstants.CALL_TYPE_VOICE))
                .thenReturn(120000);
        mRestrictManager.setQnsCallType(QnsConstants.CALL_TYPE_VOICE);
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_GUARDING));
        assertTrue(
                (int)
                                mRestrictManager.getRemainingGuardTimer(
                                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN)
                        <= 120000);
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_GUARDING));
    }

    @Test
    public void testWlanHandoverGuardingTimerVideoWithZero() {
        when(mConfigManager.isHysteresisTimerEnabled(QnsConstants.COVERAGE_HOME)).thenReturn(true);
        when(mConfigManager.getWwanHysteresisTimer(
                        ApnSetting.TYPE_IMS, QnsConstants.CALL_TYPE_IDLE))
                .thenReturn(30000);
        mRestrictManager.setCellularCoverage(QnsConstants.COVERAGE_HOME);
        DataConnectionStatusTracker.DataConnectionChangedInfo dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_HANDOVER_SUCCESS,
                        DataConnectionStatusTracker.STATE_CONNECTED,
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_GUARDING));
        when(mConfigManager.getWwanHysteresisTimer(
                        ApnSetting.TYPE_IMS, QnsConstants.CALL_TYPE_VIDEO))
                .thenReturn(0);
        mRestrictManager.setQnsCallType(QnsConstants.CALL_TYPE_VIDEO);
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_GUARDING));
    }

    @Test
    public void testIsRestrictExceptGuarding() {
        when(mConfigManager.isHysteresisTimerEnabled(QnsConstants.COVERAGE_HOME)).thenReturn(true);
        when(mConfigManager.getWwanHysteresisTimer(
                        ApnSetting.TYPE_IMS, QnsConstants.CALL_TYPE_IDLE))
                .thenReturn(90000);
        mRestrictManager.setCellularCoverage(QnsConstants.COVERAGE_HOME);
        DataConnectionStatusTracker.DataConnectionChangedInfo dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_HANDOVER_SUCCESS,
                        DataConnectionStatusTracker.STATE_CONNECTED,
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_GUARDING));
        when(mConfigManager.getWwanHysteresisTimer(
                        ApnSetting.TYPE_IMS, QnsConstants.CALL_TYPE_VOICE))
                .thenReturn(60000);
        mRestrictManager.setQnsCallType(QnsConstants.CALL_TYPE_VOICE);
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_GUARDING));
        mRestrictManager.addRestriction(
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                RESTRICT_TYPE_RTP_LOW_QUALITY,
                sReleaseEventMap.get(RESTRICT_TYPE_RTP_LOW_QUALITY),
                DEFAULT_RESTRICT_WITH_LOW_RTP_QUALITY_TIME);
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_RTP_LOW_QUALITY));
        assertTrue(
                mRestrictManager.isRestrictedExceptGuarding(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN));
        mRestrictManager.setQnsCallType(QnsConstants.CALL_TYPE_IDLE);
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_GUARDING));
        assertFalse(
                mRestrictManager.isRestrictedExceptGuarding(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN));
    }

    @Test
    public void testRegisterRestrictInfoChanged() {
        Handler h2 = new Handler(Looper.getMainLooper());
        mRestrictManager.registerRestrictInfoChanged(h2, 10004);
        assertNotNull(mRestrictManager.mRestrictInfoRegistrant);
    }

    @Test
    public void testUnregisterRestrictInfoChanged() {
        Handler h2 = new Handler(Looper.getMainLooper());
        mRestrictManager.registerRestrictInfoChanged(h2, 10004);
        assertNotNull(mRestrictManager.mRestrictInfoRegistrant);
        mRestrictManager.unRegisterRestrictInfoChanged(h2);
        assertNull(mRestrictManager.mRestrictInfoRegistrant);
    }

    @Test
    public void testEnablePdnFallbackOnInitialDataConnectionFailWithRetryCounterOnWlan() {
        setupEnablePdnFallbackOnInitialDataConnectionFail();
        validateEnablePdnFallbackOnRetryCounterOverTransportType(
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL);
    }

    @Test
    public void testEnablePdnFallbackOnInitialDataConnectionFailWithRetryCounterOnWwan() {
        setupEnablePdnFallbackOnInitialDataConnectionFail();
        validateEnablePdnFallbackOnRetryCounterOverTransportType(
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL);
    }

    @Test
    public void testEnablePdnFallbackOnInitialDataConnectionFailWithRetryTimerOnWlan() {
        setupEnableInitialDataConnectionFailFallbackWithRetryTimer();
        validateEnablePdnFallbackOnRetryTimerOverTransportType(
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL);
    }

    @Test
    public void testEnablePdnFallbackOnInitialDataConnectionFailWithRetryTimerOnWwan() {
        setupEnableInitialDataConnectionFailFallbackWithRetryTimer();
        validateEnablePdnFallbackOnRetryTimerOverTransportType(
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL);
    }

    private void setupEnableInitialDataConnectionFailFallbackWithRetryTimer() {
        int[] fallbackConfigs = new int[] {1, 0, 30000, 2};
        when(mConfigManager.getInitialDataConnectionFallbackConfig(ApnSetting.TYPE_IMS))
                .thenReturn(fallbackConfigs);
        when(mConfigManager.getFallbackGuardTimerOnInitialConnectionFail(ApnSetting.TYPE_IMS))
                .thenReturn(30000);
        mRestrictManager.setCellularCoverage(QnsConstants.COVERAGE_HOME);
        mRestrictManager.setQnsCallType(QnsConstants.CALL_TYPE_IDLE);
    }

    private void validateEnablePdnFallbackOnRetryTimerOverTransportType(
            @AccessNetworkConstants.TransportType int transportType,
            @RestrictManager.RestrictType int restrictType) {
        assertFalse(mRestrictManager.hasRestrictionType(transportType, restrictType));
        setConnectionStartedToConnectionFailedState(transportType);
        assertFalse(mRestrictManager.hasRestrictionType(transportType, restrictType));
        assertTrue(mRestrictManager.mHandler.hasMessages(3010));
        setConnectionStartedToConnectionFailedState(transportType);
        assertFalse(mRestrictManager.hasRestrictionType(transportType, restrictType));
        setConnectionStartedToConnectionFailedState(transportType);
        assertFalse(mRestrictManager.hasRestrictionType(transportType, restrictType));
        mTestLooper.moveTimeForward(30000);
        mTestLooper.dispatchAll();
        assertTrue(mRestrictManager.hasRestrictionType(transportType, restrictType));
    }

    @Test
    public void testDisablePdnFallbackRunningOnDataConnectedStateWlanToWwan() {
        setupEnablePdnFallbackOnInitialDataConnectionFail();
        validateEnablePdnFallbackOnRetryCounterOverTransportType(
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL);
        cancelPdnFallbackRunningOnDataConnectedStateOverOtherTransportType(
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL);
    }

    @Test
    public void testDisablePdnFallbackRunningOnDataConnectedStateWwanToWlan() {
        setupEnablePdnFallbackOnInitialDataConnectionFail();
        validateEnablePdnFallbackOnRetryCounterOverTransportType(
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL);
        cancelPdnFallbackRunningOnDataConnectedStateOverOtherTransportType(
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL);
    }

    @Test
    public void testDisablePdnFallbackRunningOnDataConnectedStateWlantoWlan() {
        setupEnablePdnFallbackOnInitialDataConnectionFail();
        validateEnablePdnFallbackOnRetryCounterOverTransportType(
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL);
        cancelPdnFallbackRunningOnDataConnectedStateOverTransportType(
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL);
    }

    @Test
    public void testDisablePdnFallbackRunningOnDataConnectedStateWwantoWwan() {
        setupEnablePdnFallbackOnInitialDataConnectionFail();
        validateEnablePdnFallbackOnRetryCounterOverTransportType(
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL);
        cancelPdnFallbackRunningOnDataConnectedStateOverTransportType(
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL);
    }

    private void cancelPdnFallbackRunningOnDataConnectedStateOverOtherTransportType(
            @AccessNetworkConstants.TransportType int transportType,
            @RestrictManager.RestrictType int restrictType) {
        mTestLooper.moveTimeForward(20000);
        mTestLooper.dispatchAll();
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        mRestrictManager.getOtherTransport(transportType), restrictType));
        DataConnectionStatusTracker.DataConnectionChangedInfo dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_STARTED,
                        DataConnectionStatusTracker.STATE_CONNECTING,
                        transportType);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        mRestrictManager.getOtherTransport(transportType), restrictType));
        dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_CONNECTED,
                        DataConnectionStatusTracker.STATE_CONNECTED,
                        transportType);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        mRestrictManager.getOtherTransport(transportType), restrictType));
    }

    private void cancelPdnFallbackRunningOnDataConnectedStateOverTransportType(
            @AccessNetworkConstants.TransportType int transportType,
            @RestrictManager.RestrictType int restrictType) {
        mTestLooper.moveTimeForward(20000);
        mTestLooper.dispatchAll();
        assertTrue(mRestrictManager.hasRestrictionType(transportType, restrictType));
        DataConnectionStatusTracker.DataConnectionChangedInfo dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_STARTED,
                        DataConnectionStatusTracker.STATE_CONNECTING,
                        transportType);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        assertTrue(mRestrictManager.hasRestrictionType(transportType, restrictType));
        dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_CONNECTED,
                        DataConnectionStatusTracker.STATE_CONNECTED,
                        transportType);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        assertFalse(mRestrictManager.hasRestrictionType(transportType, restrictType));
    }

    private void setupEnablePdnFallbackOnInitialDataConnectionFail() {
        int[] fallbackConfigs = new int[] {1, 2, 0, 2};
        when(mConfigManager.getInitialDataConnectionFallbackConfig(ApnSetting.TYPE_IMS))
                .thenReturn(fallbackConfigs);
        when(mConfigManager.getFallbackGuardTimerOnInitialConnectionFail(ApnSetting.TYPE_IMS))
                .thenReturn(30000);
        mRestrictManager.setCellularCoverage(QnsConstants.COVERAGE_HOME);
        mRestrictManager.setQnsCallType(QnsConstants.CALL_TYPE_IDLE);
    }

    private void validateEnablePdnFallbackOnRetryCounterOverTransportType(
            @AccessNetworkConstants.TransportType int transportType,
            @RestrictManager.RestrictType int restrictType) {
        setConnectionStartedToConnectionFailedState(transportType);
        assertFalse(mRestrictManager.hasRestrictionType(transportType, restrictType));
        setConnectionStartedToConnectionFailedState(transportType);
        assertFalse(mRestrictManager.hasRestrictionType(transportType, restrictType));
        setConnectionStartedToConnectionFailedState(transportType);
        assertTrue(mRestrictManager.hasRestrictionType(transportType, restrictType));
    }

    private void setConnectionStartedToConnectionFailedState(int transportType) {
        DataConnectionStatusTracker.DataConnectionChangedInfo dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_STARTED,
                        DataConnectionStatusTracker.STATE_CONNECTING,
                        transportType);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_FAILED,
                        DataConnectionStatusTracker.STATE_INACTIVE,
                        transportType);
        mRestrictManager.onDataConnectionChanged(dcInfo);
    }

    @Test
    public void testDisablePdnFallbackRunningOnGuardTimerExpiry() {
        setupEnablePdnFallbackOnInitialDataConnectionFail();
        when(mConfigManager.getFallbackGuardTimerOnInitialConnectionFail(ApnSetting.TYPE_IMS))
                .thenReturn(30000);
        validateEnablePdnFallbackOnRetryCounterOverTransportType(
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL);
        mTestLooper.moveTimeForward(20000);
        mTestLooper.dispatchAll();
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                        RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL));
        mTestLooper.moveTimeForward(10000);
        mTestLooper.dispatchAll();
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                        RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL));
    }

    @Test
    public void testCancelPdnFallbackRestrictionOnImsNotSupportedRat() {
        setupEnablePdnFallbackOnInitialDataConnectionFail();
        validateEnablePdnFallbackOnRetryCounterOverTransportType(
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL);
        when(mConfigManager.isAccessNetworkAllowed(
                        AccessNetworkConstants.AccessNetworkType.UTRAN, ApnSetting.TYPE_IMS))
                .thenReturn(false);
        mRestrictManager.setCellularAccessNetwork(AccessNetworkConstants.AccessNetworkType.UTRAN);
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL));
    }

    @Test
    public void testSkipPdnFallbackCheckOverWlanWithAirplaneModeOnState() {
        setupEnablePdnFallbackOnInitialDataConnectionFail();
        when(mCellularNetworkStatusTracker.isAirplaneModeEnabled()).thenReturn(true);
        cancelPdnFallbackOnAirplaneModeOn(
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL);
    }

    private void cancelPdnFallbackOnAirplaneModeOn(
            @AccessNetworkConstants.TransportType int transportType,
            @RestrictManager.RestrictType int restrictType) {
        setConnectionStartedToConnectionFailedState(transportType);
        assertFalse(mRestrictManager.hasRestrictionType(transportType, restrictType));
        setConnectionStartedToConnectionFailedState(transportType);
        assertFalse(mRestrictManager.hasRestrictionType(transportType, restrictType));
        setConnectionStartedToConnectionFailedState(transportType);
        assertFalse(mRestrictManager.hasRestrictionType(transportType, restrictType));
    }

    @Test
    public void testDisableFallbackOnDataConnectionFailOnFallbackCountMet() {
        setupEnablePdnFallbackOnInitialDataConnectionFail();
        when(mConfigManager.getFallbackGuardTimerOnInitialConnectionFail(ApnSetting.TYPE_IMS))
                .thenReturn(30000);

        // First Fallback
        validateEnablePdnFallbackOnRetryCounterOverTransportType(
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL);
        mTestLooper.moveTimeForward(20000);
        mTestLooper.dispatchAll();
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                        RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL));
        setConnectionStartedToConnectionFailedState(AccessNetworkConstants.TRANSPORT_TYPE_WLAN);

        // WLAN Fallback timer Expiry
        mTestLooper.moveTimeForward(11000);
        mTestLooper.dispatchAll();
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                        RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL));
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL));
        setConnectionStartedToConnectionFailedState(AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
        setConnectionStartedToConnectionFailedState(AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL));

        // Second Fallback
        mTestLooper.moveTimeForward(20000);
        mTestLooper.dispatchAll();
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL));
        setConnectionStartedToConnectionFailedState(AccessNetworkConstants.TRANSPORT_TYPE_WWAN);

        // WWAN Fallback timer Expiry
        mTestLooper.moveTimeForward(11000);
        mTestLooper.dispatchAll();
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL));
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                        RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL));
        setConnectionStartedToConnectionFailedState(AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        setConnectionStartedToConnectionFailedState(AccessNetworkConstants.TRANSPORT_TYPE_WWAN);

        // Max Fallback reached , so Fallback restriction never expected to run
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                        RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL));
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL));
    }

    @Test
    public void testIgnoreFallbackCountOnSingleRat() {
        setupEnablePdnFallbackOnInitialDataConnectionFail();
        when(mConfigManager.getFallbackGuardTimerOnInitialConnectionFail(ApnSetting.TYPE_IMS))
                .thenReturn(30000);

        // First Fallback
        validateEnablePdnFallbackOnRetryCounterOverTransportType(
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL);
        mTestLooper.moveTimeForward(20000);
        mTestLooper.dispatchAll();
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                        RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL));
        DataConnectionStatusTracker.DataConnectionChangedInfo dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_STARTED,
                        DataConnectionStatusTracker.STATE_CONNECTING,
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        // WLAN Fallback timer Expiry
        mTestLooper.moveTimeForward(11000);
        mTestLooper.dispatchAll();
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                        RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL));
        dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_FAILED,
                        DataConnectionStatusTracker.STATE_INACTIVE,
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        setConnectionStartedToConnectionFailedState(AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        setConnectionStartedToConnectionFailedState(AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                        RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL));

        // Second Fallback
        mTestLooper.moveTimeForward(20000);
        mTestLooper.dispatchAll();
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                        RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL));
        dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_STARTED,
                        DataConnectionStatusTracker.STATE_CONNECTING,
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        // WLAN Fallback timer Expiry
        mTestLooper.moveTimeForward(11000);
        mTestLooper.dispatchAll();
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                        RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL));
        dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_FAILED,
                        DataConnectionStatusTracker.STATE_INACTIVE,
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        setConnectionStartedToConnectionFailedState(AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        setConnectionStartedToConnectionFailedState(AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        // Fallback Count not increased if single rat
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                        RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL));
    }

    @Test
    public void testClearFallbackCountOnDataConnectedOverTransportType() {
        setupEnablePdnFallbackOnInitialDataConnectionFail();
        when(mConfigManager.getFallbackGuardTimerOnInitialConnectionFail(ApnSetting.TYPE_IMS))
                .thenReturn(30000);

        // First Fallback
        validateEnablePdnFallbackOnRetryCounterOverTransportType(
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL);
        mTestLooper.moveTimeForward(20000);
        mTestLooper.dispatchAll();
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                        RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL));
        setConnectionStartedToConnectionFailedState(AccessNetworkConstants.TRANSPORT_TYPE_WLAN);

        // WLAN Fallback timer Expiry
        mTestLooper.moveTimeForward(11000);
        mTestLooper.dispatchAll();
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                        RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL));
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL));

        // Fallback count = 2 Reached as per config
        setConnectionStartedToConnectionFailedState(AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
        setConnectionStartedToConnectionFailedState(AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL));
        DataConnectionStatusTracker.DataConnectionChangedInfo dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_STARTED,
                        DataConnectionStatusTracker.STATE_CONNECTING,
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL));

        // On Data connected state : clear fallback count
        dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_CONNECTED,
                        DataConnectionStatusTracker.STATE_CONNECTED,
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL));
    }

    @Test
    public void testResetFallbackCounterOnAirplaneModeOn() {
        when(mCellularNetworkStatusTracker.isAirplaneModeEnabled()).thenReturn(false);
        testDisableFallbackOnDataConnectionFailOnFallbackCountMet();
        mRestrictManager.mHandler.handleMessage(Message.obtain(mRestrictManager.mHandler, 6, null));
        setupEnablePdnFallbackOnInitialDataConnectionFail();
        validateEnablePdnFallbackOnRetryCounterOverTransportType(
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL);
    }

    @Test
    public void testResetFallbackCounterOnWfcOff() {
        when(mCellularNetworkStatusTracker.isAirplaneModeEnabled()).thenReturn(false);
        testDisableFallbackOnDataConnectionFailOnFallbackCountMet();
        mRestrictManager.mHandler.handleMessage(Message.obtain(mRestrictManager.mHandler, 8, null));
        setupEnablePdnFallbackOnInitialDataConnectionFail();
        validateEnablePdnFallbackOnRetryCounterOverTransportType(
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL);
    }

    @Test
    public void testResetFallbackCounterOnWifiOff() {
        when(mCellularNetworkStatusTracker.isAirplaneModeEnabled()).thenReturn(false);
        testDisableFallbackOnDataConnectionFailOnFallbackCountMet();
        mRestrictManager.mHandler.handleMessage(Message.obtain(mRestrictManager.mHandler, 3, null));
        setupEnablePdnFallbackOnInitialDataConnectionFail();
        validateEnablePdnFallbackOnRetryCounterOverTransportType(
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                RESTRICT_TYPE_FALLBACK_ON_DATA_CONNECTION_FAIL);
    }

    @Test
    public void testHandlerEventOnDataConnectionChangedEvent() throws InterruptedException {
        // Handler message with WLAN Connected received
        QnsAsyncResult asyncResult =
                new QnsAsyncResult(
                        null,
                        new DataConnectionStatusTracker.DataConnectionChangedInfo(
                                DataConnectionStatusTracker.EVENT_DATA_CONNECTION_CONNECTED,
                                DataConnectionStatusTracker.STATE_CONNECTED,
                                AccessNetworkConstants.TRANSPORT_TYPE_WLAN),
                        null);
        mRestrictManager.mHandler.handleMessage(
                Message.obtain(mRestrictManager.mHandler, 3001, asyncResult));

        // Receive Handover started over Wwan
        asyncResult =
                new QnsAsyncResult(
                        null,
                        new DataConnectionStatusTracker.DataConnectionChangedInfo(
                                EVENT_DATA_CONNECTION_HANDOVER_STARTED,
                                DataConnectionStatusTracker.STATE_HANDOVER,
                                AccessNetworkConstants.TRANSPORT_TYPE_WLAN),
                        null);
        mRestrictManager.mHandler.handleMessage(
                Message.obtain(mRestrictManager.mHandler, 3001, asyncResult));

        // start Guarding operation of Source Rat during HO in progress
        mRestrictManager.hasRestrictionType(
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN, RESTRICT_TYPE_GUARDING);
    }

    @Test
    public void testRestrictNonPreferredTransportOnBootUpWithWfcModeChangedEvents() {
        mRestrictManager.setCellularCoverage(QnsConstants.COVERAGE_HOME);
        validateOnWfcModeChanged(9, AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
        validateOnWfcModeChanged(10, AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        validateOnWfcModeChanged(11, AccessNetworkConstants.TRANSPORT_TYPE_WLAN);

        mRestrictManager.setCellularCoverage(QnsConstants.COVERAGE_ROAM);
        validateOnWfcModeChanged(14, AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
        validateOnWfcModeChanged(15, AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        validateOnWfcModeChanged(16, AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
    }

    private void validateOnWfcModeChanged(
            int wfcModeEvent, @AccessNetworkConstants.TransportType int transportType) {
        when(mCellularNetworkStatusTracker.isAirplaneModeEnabled()).thenReturn(false);
        when(mConfigManager.getWaitingTimerForPreferredTransportOnPowerOn(transportType))
                .thenReturn(10000);
        mRestrictManager.setQnsCallType(QnsConstants.CALL_TYPE_IDLE);
        mRestrictManager.mHandler.handleMessage(
                Message.obtain(mRestrictManager.mHandler, wfcModeEvent, null));
        mRestrictManager.restrictNonPreferredTransport();
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        mRestrictManager.getOtherTransport(transportType),
                        RESTRICT_TYPE_NON_PREFERRED_TRANSPORT));
        mTestLooper.moveTimeForward(10000);
        mTestLooper.dispatchAll();
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        mRestrictManager.getOtherTransport(transportType),
                        RESTRICT_TYPE_NON_PREFERRED_TRANSPORT));
    }

    @Test
    public void testUpdateLastNotifiedTransportType() {
        when(mCellularNetworkStatusTracker.isAirplaneModeEnabled()).thenReturn(false);
        mRestrictManager.setCellularCoverage(QnsConstants.COVERAGE_HOME);
        mRestrictManager.setQnsCallType(QnsConstants.CALL_TYPE_IDLE);

        // Initial Data Connection State
        mRestrictManager.updateLastNotifiedTransportType(
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN, RESTRICT_TYPE_GUARDING));

        // WWAN connected to WLAN handover state
        DataConnectionStatusTracker.DataConnectionChangedInfo dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_STARTED,
                        DataConnectionStatusTracker.STATE_CONNECTING,
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_CONNECTED,
                        DataConnectionStatusTracker.STATE_CONNECTED,
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        when(mMockDcst.isActiveState()).thenReturn(true);
        mRestrictManager.updateLastNotifiedTransportType(
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
        assertTrue(
                mRestrictManager.hasRestrictionType(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RESTRICT_TYPE_GUARDING));
    }

    @Test
    public void testIsRestrictedForInvalidTransportType() {
        when(mCellularNetworkStatusTracker.isAirplaneModeEnabled()).thenReturn(false);
        mRestrictManager.setCellularCoverage(QnsConstants.COVERAGE_HOME);
        mRestrictManager.setQnsCallType(QnsConstants.CALL_TYPE_IDLE);

        assertFalse(mRestrictManager.isRestricted(-1));
        assertFalse(mRestrictManager.hasRestrictionType(-1, RESTRICT_TYPE_GUARDING));
        assertFalse(mRestrictManager.isRestrictedExceptGuarding(-1));
    }

    @Test
    public void testGuardTimerHysteresisOnPrefWithWifiPrefInHome() {
        setupGuardTimerHysteresisOnPrefSupportedWithCoverage(11, QnsConstants.COVERAGE_HOME);
        validateGuardTimerHysteresisOnPrefSupportedWithTransportType(
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
    }

    @Test
    public void testGuardTimerHysteresisOnPrefWithCellPrefInHome() {
        setupGuardTimerHysteresisOnPrefSupportedWithCoverage(10, QnsConstants.COVERAGE_HOME);
        validateGuardTimerHysteresisOnPrefSupportedWithTransportType(
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
    }

    @Test
    public void testGuardTimerHysteresisOnPrefWithWifiPrefInRoam() {
        setupGuardTimerHysteresisOnPrefSupportedWithCoverage(16, QnsConstants.COVERAGE_ROAM);
        validateGuardTimerHysteresisOnPrefSupportedWithTransportType(
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
    }

    @Test
    public void testGuardTimerHysteresisOnPrefWithCellPrefInRoam() {
        setupGuardTimerHysteresisOnPrefSupportedWithCoverage(15, QnsConstants.COVERAGE_ROAM);
        validateGuardTimerHysteresisOnPrefSupportedWithTransportType(
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
    }

    private void setupGuardTimerHysteresisOnPrefSupportedWithCoverage(
            int event, @QnsConstants.CellularCoverage int coverage) {
        when(mCellularNetworkStatusTracker.isAirplaneModeEnabled()).thenReturn(false);
        when(mConfigManager.isHysteresisTimerEnabled(coverage)).thenReturn(true);
        when(mConfigManager.getWwanHysteresisTimer(
                        ApnSetting.TYPE_IMS, QnsConstants.CALL_TYPE_IDLE))
                .thenReturn(30000);
        when(mConfigManager.getWlanHysteresisTimer(
                        ApnSetting.TYPE_IMS, QnsConstants.CALL_TYPE_IDLE))
                .thenReturn(30000);
        // Set Wfc preference with coverage with Hysteris based on Pref supported
        mRestrictManager.setCellularCoverage(coverage);
        mRestrictManager.setQnsCallType(QnsConstants.CALL_TYPE_IDLE);
        when(mConfigManager.isGuardTimerHystersisOnPrefSupported()).thenReturn(true);
        mRestrictManager.mHandler.handleMessage(
                Message.obtain(mRestrictManager.mHandler, event, null));
    }

    private void validateGuardTimerHysteresisOnPrefSupportedWithTransportType(
            @AccessNetworkConstants.TransportType int transportType) {

        DataConnectionStatusTracker.DataConnectionChangedInfo dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_HANDOVER_SUCCESS,
                        DataConnectionStatusTracker.STATE_CONNECTED,
                        transportType);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        assertFalse(
                mRestrictManager.hasRestrictionType(
                        mRestrictManager.getOtherTransport(transportType), RESTRICT_TYPE_GUARDING));

        dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_HANDOVER_STARTED,
                        DataConnectionStatusTracker.STATE_HANDOVER,
                        transportType);
        mRestrictManager.onDataConnectionChanged(dcInfo);
        dcInfo =
                new DataConnectionStatusTracker.DataConnectionChangedInfo(
                        EVENT_DATA_CONNECTION_HANDOVER_SUCCESS,
                        DataConnectionStatusTracker.STATE_CONNECTED,
                        mRestrictManager.getOtherTransport(transportType));
        mRestrictManager.onDataConnectionChanged(dcInfo);
        assertTrue(mRestrictManager.hasRestrictionType(transportType, RESTRICT_TYPE_GUARDING));
    }
}
