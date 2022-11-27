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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import android.net.LinkProperties;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Message;
import android.os.test.TestLooper;
import android.telephony.AccessNetworkConstants;
import android.telephony.CallState;
import android.telephony.PreciseCallState;
import android.telephony.PreciseDataConnectionState;
import android.telephony.TelephonyManager;
import android.telephony.data.ApnSetting;
import android.telephony.ims.ImsCallProfile;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

@RunWith(JUnit4.class)
public class QnsCallStatusTrackerTest extends QnsTest {

    QnsCallStatusTracker mCallTracker;
    TestLooper mTestLooper;
    private Handler mImsHandler;
    private Handler mEmergencyHandler;
    CountDownLatch mLatch;
    int[] mThresholds;
    List<CallState> mTestCallStateList = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        super.setUp();
        mTestLooper = new TestLooper();
        Mockito.when(sMockContext.getMainLooper()).thenReturn(mTestLooper.getLooper());
        mImsHandler = new Handler(mTestLooper.getLooper());
        mEmergencyHandler = new Handler(mTestLooper.getLooper());
        mCallTracker = new QnsCallStatusTracker(mMockQnsTelephonyListener, 0);
        mCallTracker.registerCallTypeChangedListener(
                NetworkCapabilities.NET_CAPABILITY_IMS, mImsHandler, 1, null);
        mCallTracker.registerCallTypeChangedListener(
                NetworkCapabilities.NET_CAPABILITY_EIMS, mEmergencyHandler, 1, null);
    }

    @After
    public void tearDown() {
        mTestCallStateList.clear();
        mCallTracker.unregisterCallTypeChangedListener(
                NetworkCapabilities.NET_CAPABILITY_IMS, mImsHandler);
        mCallTracker.unregisterCallTypeChangedListener(
                NetworkCapabilities.NET_CAPABILITY_EIMS, mEmergencyHandler);
    }

    @Test
    public void testForVoiceCallTypeChangedScenarios() {

        mTestCallStateList.add(new CallState.Builder(PreciseCallState.PRECISE_CALL_STATE_DIALING)
                .setImsCallType(ImsCallProfile.CALL_TYPE_VOICE)
                .setImsCallServiceType(ImsCallProfile.SERVICE_TYPE_NORMAL).build());
        mCallTracker.updateCallState(mTestCallStateList);

        Message msg = mTestLooper.nextMessage();
        assertEquals(mImsHandler, msg.getTarget());
        assertNotEquals(mEmergencyHandler, msg.getTarget());
        assertNotNull(msg);
        QnsAsyncResult result = (QnsAsyncResult) msg.obj;
        assertNotNull(result.mResult);
        assertEquals(QnsConstants.CALL_TYPE_VOICE, (int) result.mResult);
        assertFalse(mCallTracker.isCallIdle());

        // Test2:
        mTestCallStateList.clear();
        mTestCallStateList.add(new CallState.Builder(PreciseCallState.PRECISE_CALL_STATE_ACTIVE)
                .setImsCallType(ImsCallProfile.CALL_TYPE_VOICE)
                .setImsCallServiceType(ImsCallProfile.SERVICE_TYPE_NORMAL).build());
        mCallTracker.updateCallState(mTestCallStateList);
        msg = mTestLooper.nextMessage();
        // Should not notify if call type is not changed
        assertNull(msg);

        // Test3:
        mTestCallStateList.clear();
        mCallTracker.updateCallState(mTestCallStateList);
        msg = mTestLooper.nextMessage();
        assertNotNull(msg);
        result = (QnsAsyncResult) msg.obj;
        assertNotNull(result.mResult);
        assertEquals(QnsConstants.CALL_TYPE_IDLE, (int) result.mResult);
        assertTrue(mCallTracker.isCallIdle()); // for IMS calls only
    }

    @Test
    public void testForEmergencyCallTypeChangedScenarios() {
        mTestCallStateList.add(new CallState.Builder(PreciseCallState.PRECISE_CALL_STATE_INCOMING)
                .setImsCallType(ImsCallProfile.CALL_TYPE_VOICE)
                .setImsCallServiceType(ImsCallProfile.SERVICE_TYPE_NORMAL).build());
        mCallTracker.updateCallState(mTestCallStateList);

        Message msg = mTestLooper.nextMessage();
        assertEquals(mImsHandler, msg.getTarget());
        assertNotNull(msg);
        QnsAsyncResult result = (QnsAsyncResult) msg.obj;
        assertNotNull(result.mResult);
        assertEquals(QnsConstants.CALL_TYPE_VOICE, (int) result.mResult);
        assertFalse(mCallTracker.isCallIdle());

        mTestCallStateList.clear();
        mTestCallStateList.add(new CallState.Builder(PreciseCallState.PRECISE_CALL_STATE_ACTIVE)
                .setImsCallType(ImsCallProfile.CALL_TYPE_VOICE)
                .setImsCallServiceType(ImsCallProfile.SERVICE_TYPE_NORMAL).build());
        mCallTracker.updateCallState(mTestCallStateList);
        msg = mTestLooper.nextMessage();
        // Should not notify if call type is not changed
        assertNull(msg);

        mTestCallStateList.clear();
        mTestCallStateList.add(
                new CallState.Builder(PreciseCallState.PRECISE_CALL_STATE_DISCONNECTING)
                        .setImsCallType(ImsCallProfile.CALL_TYPE_VOICE)
                        .setImsCallServiceType(ImsCallProfile.SERVICE_TYPE_NORMAL).build());
        mTestCallStateList.add(new CallState.Builder(PreciseCallState.PRECISE_CALL_STATE_DIALING)
                .setImsCallType(ImsCallProfile.CALL_TYPE_VOICE)
                .setImsCallServiceType(ImsCallProfile.SERVICE_TYPE_EMERGENCY).build());
        mCallTracker.updateCallState(mTestCallStateList);
        msg = mTestLooper.nextMessage();
        assertNotNull(msg);
        assertEquals(mEmergencyHandler, msg.getTarget());
        result = (QnsAsyncResult) msg.obj;
        assertNotNull(result.mResult);
        assertEquals(QnsConstants.CALL_TYPE_EMERGENCY, (int) result.mResult);
        assertFalse(mCallTracker.isCallIdle());

        mTestCallStateList.clear();
        mTestCallStateList.add(new CallState.Builder(PreciseCallState.PRECISE_CALL_STATE_ACTIVE)
                .setImsCallType(ImsCallProfile.CALL_TYPE_VOICE)
                .setImsCallServiceType(ImsCallProfile.SERVICE_TYPE_EMERGENCY).build());
        mCallTracker.updateCallState(mTestCallStateList);
        msg = mTestLooper.nextMessage();
        assertNotNull(msg);
        assertEquals(mImsHandler, msg.getTarget());
        result = (QnsAsyncResult) msg.obj;
        assertNotNull(result.mResult);
        assertEquals(QnsConstants.CALL_TYPE_IDLE, (int) result.mResult);
        assertFalse(mCallTracker.isCallIdle());

        mTestCallStateList.clear();
        mTestCallStateList.add(new CallState.Builder(PreciseCallState.PRECISE_CALL_STATE_ALERTING)
                .setImsCallType(ImsCallProfile.CALL_TYPE_VOICE)
                .setImsCallServiceType(ImsCallProfile.SERVICE_TYPE_NORMAL).build());
        mCallTracker.updateCallState(mTestCallStateList);
        msg = mTestLooper.nextMessage();
        assertNotNull(msg);
        assertEquals(mEmergencyHandler, msg.getTarget());
        result = (QnsAsyncResult) msg.obj;
        assertNotNull(result.mResult);
        assertEquals(QnsConstants.CALL_TYPE_IDLE, (int) result.mResult);
        msg = mTestLooper.nextMessage();
        assertNotNull(msg);
        assertEquals(mImsHandler, msg.getTarget());
        result = (QnsAsyncResult) msg.obj;
        assertNotNull(result.mResult);
        assertEquals(QnsConstants.CALL_TYPE_VOICE, (int) result.mResult);
        assertFalse(mCallTracker.isCallIdle());
    }

    @Test
    public void testForVideoCallTypeChangedScenarios() {
        mTestCallStateList.add(new CallState.Builder(PreciseCallState.PRECISE_CALL_STATE_ACTIVE)
                .setImsCallType(ImsCallProfile.CALL_TYPE_VOICE)
                .setImsCallServiceType(ImsCallProfile.SERVICE_TYPE_NORMAL).build());
        mTestCallStateList.add(new CallState.Builder(PreciseCallState.PRECISE_CALL_STATE_INCOMING)
                .setImsCallType(ImsCallProfile.CALL_TYPE_VT)
                .setImsCallServiceType(ImsCallProfile.SERVICE_TYPE_NORMAL).build());
        mCallTracker.updateCallState(mTestCallStateList);
        Message msg = mTestLooper.nextMessage();
        assertNotNull(msg);
        QnsAsyncResult result = (QnsAsyncResult) msg.obj;
        assertNotNull(result.mResult);
        assertEquals(QnsConstants.CALL_TYPE_VOICE, (int) result.mResult);
        assertFalse(mCallTracker.isCallIdle()); // for IMS calls only

        mTestCallStateList.clear();
        mTestCallStateList.add(new CallState.Builder(PreciseCallState.PRECISE_CALL_STATE_ACTIVE)
                .setImsCallType(ImsCallProfile.CALL_TYPE_VOICE)
                .setImsCallServiceType(ImsCallProfile.SERVICE_TYPE_NORMAL).build());
        mTestCallStateList.add(new CallState.Builder(PreciseCallState.PRECISE_CALL_STATE_HOLDING)
                .setImsCallType(ImsCallProfile.CALL_TYPE_VT)
                .setImsCallServiceType(ImsCallProfile.SERVICE_TYPE_NORMAL).build());
        mCallTracker.updateCallState(mTestCallStateList);
        msg = mTestLooper.nextMessage();
        assertNotNull(msg);
        result = (QnsAsyncResult) msg.obj;
        assertNotNull(result.mResult);
        assertEquals(QnsConstants.CALL_TYPE_VIDEO, (int) result.mResult);
        assertFalse(mCallTracker.isCallIdle()); // for IMS calls only

        mTestCallStateList.clear();
        mTestCallStateList.add(new CallState.Builder(PreciseCallState.PRECISE_CALL_STATE_ACTIVE)
                .setImsCallType(ImsCallProfile.CALL_TYPE_VT)
                .setImsCallServiceType(ImsCallProfile.SERVICE_TYPE_NORMAL).build());
        mTestCallStateList.add(new CallState.Builder(PreciseCallState.PRECISE_CALL_STATE_HOLDING)
                .setImsCallType(ImsCallProfile.CALL_TYPE_VOICE)
                .setImsCallServiceType(ImsCallProfile.SERVICE_TYPE_NORMAL).build());
        mCallTracker.updateCallState(mTestCallStateList);
        msg = mTestLooper.nextMessage();
        assertNull(msg);
        assertFalse(mCallTracker.isCallIdle()); // for IMS calls only

        mTestCallStateList.clear();
        mTestCallStateList.add(new CallState.Builder(PreciseCallState.PRECISE_CALL_STATE_HOLDING)
                .setImsCallType(ImsCallProfile.CALL_TYPE_VOICE)
                .setImsCallServiceType(ImsCallProfile.SERVICE_TYPE_NORMAL).build());
        mCallTracker.updateCallState(mTestCallStateList);
        msg = mTestLooper.nextMessage();
        assertNotNull(msg);
        assertEquals(mImsHandler, msg.getTarget());
        result = (QnsAsyncResult) msg.obj;
        assertNotNull(result.mResult);
        assertEquals(QnsConstants.CALL_TYPE_VOICE, (int) result.mResult);
        assertFalse(mCallTracker.isCallIdle());

        mTestCallStateList.clear();
        mTestCallStateList.add(new CallState.Builder(PreciseCallState.PRECISE_CALL_STATE_ACTIVE)
                .setImsCallType(ImsCallProfile.CALL_TYPE_VOICE)
                .setImsCallServiceType(ImsCallProfile.SERVICE_TYPE_NORMAL).build());
        mTestCallStateList.add(new CallState.Builder(PreciseCallState.PRECISE_CALL_STATE_INCOMING)
                .setImsCallType(ImsCallProfile.CALL_TYPE_VT)
                .setImsCallServiceType(ImsCallProfile.SERVICE_TYPE_NORMAL).build());
        mCallTracker.updateCallState(mTestCallStateList);
        msg = mTestLooper.nextMessage();
        assertNull(msg);
        assertFalse(mCallTracker.isCallIdle());

        mTestCallStateList.clear();
        mTestCallStateList.add(new CallState.Builder(PreciseCallState.PRECISE_CALL_STATE_ACTIVE)
                .setImsCallType(ImsCallProfile.CALL_TYPE_VT)
                .setImsCallServiceType(ImsCallProfile.SERVICE_TYPE_NORMAL).build());
        mCallTracker.updateCallState(mTestCallStateList);
        msg = mTestLooper.nextMessage();
        assertNotNull(msg);
        assertEquals(mImsHandler, msg.getTarget());
        result = (QnsAsyncResult) msg.obj;
        assertNotNull(result.mResult);
        assertEquals(QnsConstants.CALL_TYPE_VIDEO, (int) result.mResult);
        assertFalse(mCallTracker.isCallIdle());

        mTestCallStateList.clear();
        mTestCallStateList.add(new CallState.Builder(PreciseCallState.PRECISE_CALL_STATE_ALERTING)
                .setImsCallType(ImsCallProfile.CALL_TYPE_VT)
                .setImsCallServiceType(ImsCallProfile.SERVICE_TYPE_NORMAL).build());
        mCallTracker.updateCallState(mTestCallStateList);
        msg = mTestLooper.nextMessage();
        assertNotNull(msg);
        assertEquals(mImsHandler, msg.getTarget());
        result = (QnsAsyncResult) msg.obj;
        assertNotNull(result.mResult);
        assertEquals(QnsConstants.CALL_TYPE_IDLE, (int) result.mResult);
        assertFalse(mCallTracker.isCallIdle());

        mTestCallStateList.clear();
        mCallTracker.updateCallState(mTestCallStateList);
        msg = mTestLooper.nextMessage();
        assertNull(msg);
        assertTrue(mCallTracker.isCallIdle());
    }

    @Test
    public void testUnregisterCallTypeChangedListener() {
        mTestCallStateList.add(new CallState.Builder(PreciseCallState.PRECISE_CALL_STATE_ACTIVE)
                .setImsCallType(ImsCallProfile.CALL_TYPE_VOICE)
                .setImsCallServiceType(ImsCallProfile.SERVICE_TYPE_NORMAL).build());
        mTestCallStateList.add(new CallState.Builder(PreciseCallState.PRECISE_CALL_STATE_INCOMING)
                .setImsCallType(ImsCallProfile.CALL_TYPE_VT)
                .setImsCallServiceType(ImsCallProfile.SERVICE_TYPE_NORMAL).build());
        mCallTracker.updateCallState(mTestCallStateList);
        Message msg = mTestLooper.nextMessage();
        assertNotNull(msg);
        QnsAsyncResult result = (QnsAsyncResult) msg.obj;
        assertNotNull(result.mResult);
        assertEquals(QnsConstants.CALL_TYPE_VOICE, (int) result.mResult);
        assertFalse(mCallTracker.isCallIdle()); // for IMS calls only

        mCallTracker.unregisterCallTypeChangedListener(
                NetworkCapabilities.NET_CAPABILITY_IMS, mImsHandler);
        mCallTracker.unregisterCallTypeChangedListener(
                NetworkCapabilities.NET_CAPABILITY_EIMS, mEmergencyHandler);

        mTestCallStateList.clear();
        mCallTracker.updateCallState(mTestCallStateList);
        msg = mTestLooper.nextMessage();
        assertNull(msg);

        mTestCallStateList.add(new CallState.Builder(PreciseCallState.PRECISE_CALL_STATE_ACTIVE)
                .setImsCallType(ImsCallProfile.CALL_TYPE_VOICE)
                .setImsCallServiceType(ImsCallProfile.SERVICE_TYPE_EMERGENCY).build());
        mCallTracker.updateCallState(mTestCallStateList);
        msg = mTestLooper.nextMessage();
        assertNull(msg);
    }

    @Test
    public void testEmergencyOverImsCallTypeChangedScenarios() {
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
        mTestCallStateList.add(new CallState.Builder(PreciseCallState.PRECISE_CALL_STATE_DIALING)
                .setImsCallType(ImsCallProfile.CALL_TYPE_VOICE)
                .setImsCallServiceType(ImsCallProfile.SERVICE_TYPE_EMERGENCY).build());
        mCallTracker.updateCallState(mTestCallStateList);
        Message msg = mTestLooper.nextMessage();
        assertNotNull(msg);
        assertEquals(mImsHandler, msg.getTarget());
        QnsAsyncResult result = (QnsAsyncResult) msg.obj;
        assertNotNull(result.mResult);
        assertEquals(QnsConstants.CALL_TYPE_EMERGENCY, (int) result.mResult);

        // Test2:
        mTestCallStateList.clear();
        mTestCallStateList.add(new CallState.Builder(PreciseCallState.PRECISE_CALL_STATE_ACTIVE)
                .setImsCallType(ImsCallProfile.CALL_TYPE_VOICE)
                .setImsCallServiceType(ImsCallProfile.SERVICE_TYPE_EMERGENCY).build());
        mCallTracker.updateCallState(mTestCallStateList);
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

        mTestCallStateList.clear();
        mCallTracker.updateCallState(mTestCallStateList);
        msg = mTestLooper.nextMessage();
        assertNotNull(msg);
        assertEquals(mImsHandler, msg.getTarget());
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
        mTestCallStateList.clear();
        mTestCallStateList.add(new CallState.Builder(PreciseCallState.PRECISE_CALL_STATE_ACTIVE)
                .setImsCallType(ImsCallProfile.CALL_TYPE_VOICE)
                .setImsCallServiceType(ImsCallProfile.SERVICE_TYPE_EMERGENCY).build());
        mCallTracker.updateCallState(mTestCallStateList);
        msg = mTestLooper.nextMessage();
        assertNotNull(msg);
        assertEquals(mEmergencyHandler, msg.getTarget());
        result = (QnsAsyncResult) msg.obj;
        assertNotNull(result.mResult);
        assertEquals(QnsConstants.CALL_TYPE_EMERGENCY, (int) result.mResult);

        mTestCallStateList.clear();
        mCallTracker.updateCallState(mTestCallStateList);
        msg = mTestLooper.nextMessage();
        assertNotNull(msg);
        assertEquals(mEmergencyHandler, msg.getTarget());
        result = (QnsAsyncResult) msg.obj;
        assertNotNull(result.mResult);
        assertEquals(QnsConstants.CALL_TYPE_IDLE, (int) result.mResult);
    }

    @Test
    public void testOnSrvccStateChanged() {
        mTestCallStateList.add(new CallState.Builder(PreciseCallState.PRECISE_CALL_STATE_ACTIVE)
                .setImsCallType(ImsCallProfile.CALL_TYPE_VOICE)
                .setImsCallServiceType(ImsCallProfile.SERVICE_TYPE_EMERGENCY).build());
        mCallTracker.updateCallState(mTestCallStateList);
        Message msg = mTestLooper.nextMessage();
        assertNotNull(msg);
        assertEquals(mEmergencyHandler, msg.getTarget());
        QnsAsyncResult result = (QnsAsyncResult) msg.obj;
        assertNotNull(result.mResult);
        assertEquals(QnsConstants.CALL_TYPE_EMERGENCY, (int) result.mResult);
        mCallTracker.onSrvccStateChangedInternal(TelephonyManager.SRVCC_STATE_HANDOVER_COMPLETED);
        msg = mTestLooper.nextMessage();
        assertNotNull(msg);
        result = (QnsAsyncResult) msg.obj;
        assertNotNull(result.mResult);
        assertEquals(QnsConstants.CALL_TYPE_IDLE, (int) result.mResult);

        mCallTracker.onSrvccStateChangedInternal(TelephonyManager.SRVCC_STATE_HANDOVER_STARTED);
        msg = mTestLooper.nextMessage();
        assertNull(msg);
    }

    @Test
    public void isIdleState() {
        mTestCallStateList.clear();
        mCallTracker.updateCallState(mTestCallStateList);
        assertTrue(mCallTracker.isCallIdle());
    }
}
