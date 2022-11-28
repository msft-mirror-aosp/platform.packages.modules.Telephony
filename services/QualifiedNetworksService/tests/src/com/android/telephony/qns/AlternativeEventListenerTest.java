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

package com.android.telephony.qns;

import static org.junit.Assert.*;
import static org.mockito.Mockito.when;

import android.net.LinkProperties;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Message;
import android.os.test.TestLooper;
import android.telephony.AccessNetworkConstants;
import android.telephony.PreciseCallState;
import android.telephony.PreciseDataConnectionState;
import android.telephony.TelephonyManager;
import android.telephony.data.ApnSetting;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(JUnit4.class)
public class AlternativeEventListenerTest extends QnsTest {

    AlternativeEventListener mListener;
    AltEventProvider mAltEventProvider;
    TestLooper mTestLooper;
    private static final int SLOT_INDEX = 0;
    private Handler mHandler;
    CountDownLatch mLatch;
    int[] mThresholds;
    // QnsTelephonyListener mQtListener;

    class AltEventProvider extends AlternativeEventProvider {

        AltEventProvider(AlternativeEventListener altEventListener, int slotId) {
            super(altEventListener, slotId);
        }

        @Override
        public void requestRtpThreshold(
                int callId,
                int jitter,
                int packetLossRate,
                int packetLossTimeInMilliSec,
                int noRtpTimeInMilliSec) {}

        @Override
        public void setEcnoSignalThreshold(int[] threshold) {
            if (mLatch != null) {
                mThresholds = threshold;
                mLatch.countDown();
            }
        }
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        super.setUp();
        mTestLooper = new TestLooper();
        Mockito.when(sMockContext.getMainLooper()).thenReturn(mTestLooper.getLooper());
        mHandler = new Handler(mTestLooper.getLooper());
        mListener = new AlternativeEventListener(sMockContext, mMockQnsTelephonyListener, 0);
        mAltEventProvider = new AltEventProvider(mMockAltEventListener, SLOT_INDEX);
        mListener.setEventProvider(mAltEventProvider);
    }

    @After
    public void tearDown() {
        mListener.close();
    }

    @Test
    public void testRegisterEmergencyPreferredTransportTypeChanged() {
        int[] expectedTransports =
                new int[] {
                    AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                    AccessNetworkConstants.TRANSPORT_TYPE_WWAN
                };

        mListener.registerEmergencyPreferredTransportTypeChanged(mHandler, 1, null);

        for (int expectedTransport : expectedTransports) {
            mAltEventProvider.notifyEmergencyPreferredTransportType(expectedTransport);
            Message msg = mTestLooper.nextMessage();
            assertNotNull(msg);
            QnsAsyncResult result = (QnsAsyncResult) msg.obj;
            assertNotNull(result.mResult);
            int actualTransport = (int) result.mResult;
            assertEquals(expectedTransport, actualTransport);
        }
    }

    @Test
    public void testUnregisterEmergencyPreferredTransportTypeChanged() {
        mListener.registerEmergencyPreferredTransportTypeChanged(mHandler, 1, null);
        mAltEventProvider.notifyEmergencyPreferredTransportType(
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        Message msg = mTestLooper.nextMessage();
        assertNotNull(msg);

        mListener.unregisterEmergencyPreferredTransportTypeChanged();
        mAltEventProvider.notifyEmergencyPreferredTransportType(
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
        msg = mTestLooper.nextMessage();
        assertNull(msg);

        mAltEventProvider.notifyEmergencyPreferredTransportType(
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        msg = mTestLooper.nextMessage();
        assertNull(msg);
    }

    @Test
    public void testRegisterTryWfcConnectionStateListener() {
        mListener.registerTryWfcConnectionStateListener(mHandler, 1, null);
        boolean[] expectedStates = new boolean[] {false, true, false};

        for (boolean expectedState : expectedStates) {
            mAltEventProvider.notifyTryWfcConnectionState(expectedState);
            Message msg = mTestLooper.nextMessage();
            assertNotNull(msg);
            QnsAsyncResult result = (QnsAsyncResult) msg.obj;
            assertNotNull(result.mResult);
            boolean actualState = (boolean) result.mResult;
            assertEquals(expectedState, actualState);
        }
    }

    @Test
    public void testForRtpQualityScenarios() {
        QnsCarrierConfigManager.RtpMetricsConfig rtpConfig =
                new QnsCarrierConfigManager.RtpMetricsConfig(0, 0, 0, 0);
        int expectedReason = QnsConstants.RTP_LOW_QUALITY_REASON_JITTER;

        // register to IMS, Emergency:
        mListener.registerLowRtpQualityEvent(
                NetworkCapabilities.NET_CAPABILITY_IMS, mHandler, 1, null, rtpConfig);
        mListener.registerLowRtpQualityEvent(
                NetworkCapabilities.NET_CAPABILITY_EIMS, mHandler, 2, null, rtpConfig);

        mAltEventProvider.notifyCallInfo(
                1, QnsConstants.CALL_TYPE_VOICE, PreciseCallState.PRECISE_CALL_STATE_ACTIVE);
        mAltEventProvider.notifyRtpLowQuality(expectedReason);
        Message msg = mTestLooper.nextMessage();
        assertNotNull(msg);
        QnsAsyncResult result = (QnsAsyncResult) msg.obj;
        assertNotNull(result.mResult);
        int actualReason = (int) result.mResult;
        assertEquals(expectedReason, actualReason);

        // Do not notify when call is disconnected:
        mAltEventProvider.notifyCallInfo(
                1, QnsConstants.CALL_TYPE_VOICE, PreciseCallState.PRECISE_CALL_STATE_DISCONNECTED);
        mAltEventProvider.notifyRtpLowQuality(expectedReason);
        msg = mTestLooper.nextMessage();
        assertNull(msg);

        mAltEventProvider.notifyCallInfo(
                2, QnsConstants.CALL_TYPE_EMERGENCY, PreciseCallState.PRECISE_CALL_STATE_ACTIVE);
        mAltEventProvider.notifyRtpLowQuality(expectedReason);
        msg = mTestLooper.nextMessage();
        assertNotNull(msg);
        result = (QnsAsyncResult) msg.obj;
        assertNotNull(result.mResult);
        actualReason = (int) result.mResult;
        assertEquals(expectedReason, actualReason);

        // Do not notify when call is disconnected:
        mAltEventProvider.notifyCallInfo(
                1,
                QnsConstants.CALL_TYPE_EMERGENCY,
                PreciseCallState.PRECISE_CALL_STATE_DISCONNECTED);
        mAltEventProvider.notifyRtpLowQuality(expectedReason);
        msg = mTestLooper.nextMessage();
        assertNull(msg);
    }

    @Test
    public void testUnregisterLowRtpQualityEvent() {
        QnsCarrierConfigManager.RtpMetricsConfig rtpConfig =
                new QnsCarrierConfigManager.RtpMetricsConfig(0, 0, 0, 0);
        int expectedReason = QnsConstants.RTP_LOW_QUALITY_REASON_JITTER;
        mAltEventProvider.notifyCallInfo(
                1, QnsConstants.CALL_TYPE_VOICE, PreciseCallState.PRECISE_CALL_STATE_ACTIVE);

        // register to IMS, Emergency:
        mListener.registerLowRtpQualityEvent(
                NetworkCapabilities.NET_CAPABILITY_IMS, mHandler, 1, null, rtpConfig);
        mListener.registerLowRtpQualityEvent(
                NetworkCapabilities.NET_CAPABILITY_EIMS, mHandler, 2, null, rtpConfig);
        mAltEventProvider.notifyRtpLowQuality(expectedReason);
        Message msg = mTestLooper.nextMessage();
        assertNotNull(msg);

        // unregister for IMS
        mListener.unregisterLowRtpQualityEvent(NetworkCapabilities.NET_CAPABILITY_IMS, mHandler);
        mAltEventProvider.notifyRtpLowQuality(expectedReason);
        msg = mTestLooper.nextMessage();
        assertNull(msg);

        // Event still registered for emergency, hence it should be notified:
        mAltEventProvider.notifyCallInfo(
                1, QnsConstants.CALL_TYPE_EMERGENCY, PreciseCallState.PRECISE_CALL_STATE_ACTIVE);
        mAltEventProvider.notifyRtpLowQuality(expectedReason);
        msg = mTestLooper.nextMessage();
        assertNotNull(msg);

        // unregister for Emergency:
        mListener.unregisterLowRtpQualityEvent(NetworkCapabilities.NET_CAPABILITY_EIMS, mHandler);
        mAltEventProvider.notifyRtpLowQuality(expectedReason);
        msg = mTestLooper.nextMessage();
        assertNull(msg);
    }

    @Test
    public void testForCallTypeChangedScenarios() {
        mListener.registerCallTypeChangedListener(
                NetworkCapabilities.NET_CAPABILITY_IMS, null, 1, null);
        mListener.registerCallTypeChangedListener(
                NetworkCapabilities.NET_CAPABILITY_IMS, mHandler, 1, null);
        mListener.registerCallTypeChangedListener(
                NetworkCapabilities.NET_CAPABILITY_EIMS, mHandler, 1, null);
        mListener.registerCallTypeChangedListener(
                NetworkCapabilities.NET_CAPABILITY_MMS, mHandler, 1, null);

        // Test1:
        mAltEventProvider.notifyCallInfo(
                1, QnsConstants.CALL_TYPE_VOICE, PreciseCallState.PRECISE_CALL_STATE_ACTIVE);

        Message msg = mTestLooper.nextMessage();
        assertNotNull(msg);
        QnsAsyncResult result = (QnsAsyncResult) msg.obj;
        assertNotNull(result.mResult);
        assertEquals(QnsConstants.CALL_TYPE_VOICE, (int) result.mResult);
        assertFalse(mListener.isIdleState()); // for IMS calls only

        // Test2:
        mAltEventProvider.notifyCallInfo(
                1, QnsConstants.CALL_TYPE_VOICE, PreciseCallState.PRECISE_CALL_STATE_ACTIVE);
        msg = mTestLooper.nextMessage();

        // Should not notify if call type is not changed
        assertNull(msg);

        // Test3:
        mAltEventProvider.notifyCallInfo(
                1, QnsConstants.CALL_TYPE_VOICE, PreciseCallState.PRECISE_CALL_STATE_DISCONNECTED);
        msg = mTestLooper.nextMessage();
        assertNotNull(msg);
        result = (QnsAsyncResult) msg.obj;
        assertNotNull(result.mResult);
        assertEquals(QnsConstants.CALL_TYPE_IDLE, (int) result.mResult);
        assertTrue(mListener.isIdleState()); // for IMS calls only

        // Test4:
        mAltEventProvider.notifyCallInfo(
                2, QnsConstants.CALL_TYPE_EMERGENCY, PreciseCallState.PRECISE_CALL_STATE_ACTIVE);
        msg = mTestLooper.nextMessage();
        assertNotNull(msg);
        result = (QnsAsyncResult) msg.obj;
        assertNotNull(result.mResult);
        assertEquals(QnsConstants.CALL_TYPE_EMERGENCY, (int) result.mResult);

        // Test5:
        mAltEventProvider.notifyCallInfo(
                2,
                QnsConstants.CALL_TYPE_EMERGENCY,
                PreciseCallState.PRECISE_CALL_STATE_DISCONNECTED);
        msg = mTestLooper.nextMessage();
        assertNotNull(msg);
        result = (QnsAsyncResult) msg.obj;
        assertNotNull(result.mResult);
        assertEquals(QnsConstants.CALL_TYPE_IDLE, (int) result.mResult);
    }

    @Test
    public void testUnregisterCallTypeChangedListener() {
        mListener.registerCallTypeChangedListener(
                NetworkCapabilities.NET_CAPABILITY_IMS, null, 1, null);
        mListener.registerCallTypeChangedListener(
                NetworkCapabilities.NET_CAPABILITY_IMS, mHandler, 1, null);
        mListener.registerCallTypeChangedListener(
                NetworkCapabilities.NET_CAPABILITY_EIMS, mHandler, 1, null);
        mListener.registerCallTypeChangedListener(
                NetworkCapabilities.NET_CAPABILITY_MMS, mHandler, 1, null);

        mAltEventProvider.notifyCallInfo(
                1, QnsConstants.CALL_TYPE_VOICE, PreciseCallState.PRECISE_CALL_STATE_ACTIVE);
        Message msg = mTestLooper.nextMessage();
        assertNotNull(msg);

        mListener.unregisterCallTypeChangedListener(
                NetworkCapabilities.NET_CAPABILITY_IMS, mHandler);
        mAltEventProvider.notifyCallInfo(
                1, QnsConstants.CALL_TYPE_VOICE, PreciseCallState.PRECISE_CALL_STATE_DISCONNECTED);
        msg = mTestLooper.nextMessage();
        assertNull(msg);

        mAltEventProvider.notifyCallInfo(
                1, QnsConstants.CALL_TYPE_EMERGENCY, PreciseCallState.PRECISE_CALL_STATE_ACTIVE);
        msg = mTestLooper.nextMessage();
        assertNotNull(msg);

        mListener.unregisterCallTypeChangedListener(
                NetworkCapabilities.NET_CAPABILITY_EIMS, mHandler);
        mAltEventProvider.notifyCallInfo(
                1,
                QnsConstants.CALL_TYPE_EMERGENCY,
                PreciseCallState.PRECISE_CALL_STATE_DISCONNECTED);
        msg = mTestLooper.nextMessage();
        assertNull(msg);
    }

    @Test
    public void testEmergencyOverImsCallTypeChangedScenarios() {
        mListener.registerCallTypeChangedListener(
                NetworkCapabilities.NET_CAPABILITY_IMS, mHandler, 1, null);
        PreciseDataConnectionState emergencyDataStatus =
                new PreciseDataConnectionState.Builder()
                        .setTransportType(AccessNetworkConstants.TRANSPORT_TYPE_INVALID)
                        .setState(TelephonyManager.DATA_DISCONNECTED)
                        .setNetworkType(AccessNetworkConstants.AccessNetworkType.EUTRAN)
                        .setApnSetting(
                                new ApnSetting.Builder()
                                        .setApnTypeBitmask(ApnSetting.TYPE_EMERGENCY)
                                        .setApnName("sos")
                                        .setEntryName("sos")
                                        .build())
                        .setLinkProperties(new LinkProperties())
                        .build();
        PreciseDataConnectionState imsDataStatus =
                new PreciseDataConnectionState.Builder()
                        .setTransportType(AccessNetworkConstants.TRANSPORT_TYPE_WLAN)
                        .setState(TelephonyManager.DATA_CONNECTED)
                        .setNetworkType(AccessNetworkConstants.AccessNetworkType.IWLAN)
                        .setApnSetting(
                                new ApnSetting.Builder()
                                        .setApnTypeBitmask(ApnSetting.TYPE_IMS)
                                        .setApnName("ims")
                                        .setEntryName("ims")
                                        .build())
                        .build();

        when(mMockQnsTelephonyListener.getLastPreciseDataConnectionState(
                        NetworkCapabilities.NET_CAPABILITY_EIMS))
                .thenReturn(emergencyDataStatus);
        when(mMockQnsTelephonyListener.getLastPreciseDataConnectionState(
                        NetworkCapabilities.NET_CAPABILITY_IMS))
                .thenReturn(imsDataStatus);
        // Test1:
        mAltEventProvider.notifyCallInfo(
                1, QnsConstants.CALL_TYPE_EMERGENCY, PreciseCallState.PRECISE_CALL_STATE_DIALING);

        Message msg = mTestLooper.nextMessage();
        assertNotNull(msg);
        QnsAsyncResult result = (QnsAsyncResult) msg.obj;
        assertNotNull(result.mResult);
        assertEquals(QnsConstants.CALL_TYPE_EMERGENCY, (int) result.mResult);

        // Test2:
        mAltEventProvider.notifyCallInfo(
                1, QnsConstants.CALL_TYPE_EMERGENCY, PreciseCallState.PRECISE_CALL_STATE_ACTIVE);
        msg = mTestLooper.nextMessage();

        // Should not notify if call type is not changed
        assertNull(msg);

        // Test3:
        imsDataStatus =
                new PreciseDataConnectionState.Builder()
                        .setTransportType(AccessNetworkConstants.TRANSPORT_TYPE_INVALID)
                        .setState(TelephonyManager.DATA_DISCONNECTED)
                        .setNetworkType(AccessNetworkConstants.AccessNetworkType.EUTRAN)
                        .setApnSetting(
                                new ApnSetting.Builder()
                                        .setApnTypeBitmask(ApnSetting.TYPE_IMS)
                                        .setApnName("ims")
                                        .setEntryName("ims")
                                        .build())
                        .build();
        Mockito.clearInvocations(mMockQnsTelephonyListener);
        when(mMockQnsTelephonyListener.getLastPreciseDataConnectionState(
                        NetworkCapabilities.NET_CAPABILITY_IMS))
                .thenReturn(imsDataStatus);

        mAltEventProvider.notifyCallInfo(
                1,
                QnsConstants.CALL_TYPE_EMERGENCY,
                PreciseCallState.PRECISE_CALL_STATE_DISCONNECTED);
        msg = mTestLooper.nextMessage();
        assertNotNull(msg);
        result = (QnsAsyncResult) msg.obj;
        assertNotNull(result.mResult);
        assertEquals(QnsConstants.CALL_TYPE_IDLE, (int) result.mResult);

        // Test4:
        emergencyDataStatus =
                new PreciseDataConnectionState.Builder()
                        .setTransportType(AccessNetworkConstants.TRANSPORT_TYPE_WLAN)
                        .setState(TelephonyManager.DATA_CONNECTED)
                        .setNetworkType(AccessNetworkConstants.AccessNetworkType.IWLAN)
                        .setApnSetting(
                                new ApnSetting.Builder()
                                        .setApnTypeBitmask(ApnSetting.TYPE_EMERGENCY)
                                        .setApnName("sos")
                                        .setEntryName("sos")
                                        .build())
                        .build();
        Mockito.clearInvocations(mMockQnsTelephonyListener);
        when(mMockQnsTelephonyListener.getLastPreciseDataConnectionState(
                        NetworkCapabilities.NET_CAPABILITY_EIMS))
                .thenReturn(emergencyDataStatus);
        imsDataStatus =
                new PreciseDataConnectionState.Builder()
                        .setTransportType(AccessNetworkConstants.TRANSPORT_TYPE_WLAN)
                        .setState(TelephonyManager.DATA_CONNECTED)
                        .setNetworkType(AccessNetworkConstants.AccessNetworkType.IWLAN)
                        .setApnSetting(
                                new ApnSetting.Builder()
                                        .setApnTypeBitmask(ApnSetting.TYPE_IMS)
                                        .setApnName("ims")
                                        .setEntryName("ims")
                                        .build())
                        .build();
        Mockito.clearInvocations(mMockQnsTelephonyListener);
        when(mMockQnsTelephonyListener.getLastPreciseDataConnectionState(
                        NetworkCapabilities.NET_CAPABILITY_IMS))
                .thenReturn(imsDataStatus);
        mAltEventProvider.notifyCallInfo(
                2, QnsConstants.CALL_TYPE_EMERGENCY, PreciseCallState.PRECISE_CALL_STATE_ACTIVE);
        msg = mTestLooper.nextMessage();
        assertNull(msg);
    }

    @Test
    public void testOnSrvccStateChanged() {
        mListener.registerCallTypeChangedListener(
                NetworkCapabilities.NET_CAPABILITY_IMS, mHandler, 1, null);

        mListener.onSrvccStateChanged(TelephonyManager.SRVCC_STATE_HANDOVER_COMPLETED);
        Message msg = mTestLooper.nextMessage();
        assertNotNull(msg);
        QnsAsyncResult result = (QnsAsyncResult) msg.obj;
        assertNotNull(result.mResult);
        assertEquals(QnsConstants.CALL_TYPE_IDLE, (int) result.mResult);

        mListener.onSrvccStateChanged(TelephonyManager.SRVCC_STATE_HANDOVER_STARTED);
        msg = mTestLooper.nextMessage();
        assertNull(msg);
    }

    @Test
    public void isIdleState() {
        mListener.isIdleState();
    }

    @Test
    public void setEcnoSignalThreshold() throws InterruptedException {
        mLatch = new CountDownLatch(1);
        int[] ecnoThresholds = new int[] {-85, -90, -95};
        mListener.setEcnoSignalThreshold(ecnoThresholds);
        assertTrue(mLatch.await(100, TimeUnit.MILLISECONDS));
        assertNotNull(mThresholds);
        assertArrayEquals(ecnoThresholds, mThresholds);
    }
}
