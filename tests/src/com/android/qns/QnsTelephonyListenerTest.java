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

import static android.telephony.NrVopsSupportInfo.NR_STATUS_VOPS_3GPP_SUPPORTED;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.qns.QualityMonitor.EVENT_SUBSCRIPTION_ID_CHANGED;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.net.LinkProperties;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.test.TestLooper;
import android.telephony.AccessNetworkConstants;
import android.telephony.BarringInfo;
import android.telephony.LteVopsSupportInfo;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.NrVopsSupportInfo;
import android.telephony.PreciseDataConnectionState;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.telephony.VopsSupportInfo;
import android.telephony.data.ApnSetting;
import android.util.SparseArray;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

@RunWith(JUnit4.class)
public final class QnsTelephonyListenerTest extends QnsTest {

    private static final int CONDITIONAL_BARRING_FACTOR = 100;
    private static final int CONDITIONAL_BARRING_TIME = 20;
    QnsTelephonyListener mQtListener;
    MockitoSession mStaticMockSession;

    Handler h;
    HandlerThread mHandlerThread =
            new HandlerThread("") {
                @Override
                protected void onLooperPrepared() {
                    mQtListener = QnsTelephonyListener.getInstance(sMockContext, 0);
                    setReady(true);
                }
            };
    TestLooper mTestLooper;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        super.setUp();
        mStaticMockSession = mockitoSession().mockStatic(QnsUtils.class).startMocking();
        doAnswer(invocation -> null)
                .when(mockSubscriptionManager)
                .addOnSubscriptionsChangedListener(
                        any(Executor.class),
                        any(SubscriptionManager.OnSubscriptionsChangedListener.class));
        mTestLooper = new TestLooper();
        h = new Handler(mTestLooper.getLooper());
        mHandlerThread.start();
        waitUntilReady();
    }

    @After
    public void tearDown() {
        mStaticMockSession.finishMocking();

        if (mQtListener != null) mQtListener.close();

        if (mHandlerThread != null) {
            mHandlerThread.quit();
        }
    }

    @Test
    public void testNotifyQnsTelephonyInfo() {
        QnsTelephonyListener.QnsTelephonyInfo qtInfo = mQtListener.new QnsTelephonyInfo();
        qtInfo.setRegisteredPlmn("00102");
        qtInfo.setDataNetworkType(TelephonyManager.NETWORK_TYPE_EDGE);
        mQtListener.registerQnsTelephonyInfoChanged(ApnSetting.TYPE_MMS, h, 1, null, false);

        mQtListener.notifyQnsTelephonyInfo(qtInfo);
        Message msg = mTestLooper.nextMessage();
        assertNotNull(msg);
        QnsTelephonyListener.QnsTelephonyInfo output =
                (QnsTelephonyListener.QnsTelephonyInfo) ((AsyncResult) msg.obj).result;
        assertEquals(qtInfo, output);

        mQtListener.registerQnsTelephonyInfoChanged(ApnSetting.TYPE_IMS, h, 1, null, false);
        mQtListener.notifyQnsTelephonyInfo(qtInfo);
        msg = mTestLooper.nextMessage();
        assertNotNull(msg);
        output = (QnsTelephonyListener.QnsTelephonyInfo) ((AsyncResult) msg.obj).result;
        assertTrue(output instanceof QnsTelephonyListener.QnsTelephonyInfoIms);
    }

    @Test
    public void testNotifyQnsTelephonyInfoIms() {
        QnsTelephonyListener.QnsTelephonyInfo qtInfo = mQtListener.new QnsTelephonyInfo();
        qtInfo.setRegisteredPlmn("00102");
        qtInfo.setDataNetworkType(TelephonyManager.NETWORK_TYPE_EDGE);
        QnsTelephonyListener.QnsTelephonyInfoIms qtInfoIms =
                mQtListener.new QnsTelephonyInfoIms(qtInfo, true, true, true, true);
        mQtListener.registerQnsTelephonyInfoChanged(ApnSetting.TYPE_MMS, h, 1, null, false);
        mQtListener.notifyQnsTelephonyInfoIms(qtInfoIms);

        Message msg = mTestLooper.nextMessage();
        assertNull(msg); // should not notify for non-IMS apn.

        mQtListener.registerQnsTelephonyInfoChanged(ApnSetting.TYPE_IMS, h, 1, null, false);
        mQtListener.notifyQnsTelephonyInfoIms(qtInfoIms);

        msg = mTestLooper.nextMessage();
        assertNotNull(msg);
        QnsTelephonyListener.QnsTelephonyInfoIms output =
                (QnsTelephonyListener.QnsTelephonyInfoIms) ((AsyncResult) msg.obj).result;
        assertEquals(qtInfoIms, output); // notify for IMS apn
    }

    @Test
    public void testGetLastQnsTelephonyInfo() {
        QnsTelephonyListener.QnsTelephonyInfo qtInfo = mQtListener.getLastQnsTelephonyInfo();
        assertEquals(ServiceState.STATE_OUT_OF_SERVICE, qtInfo.getDataRegState());
        assertEquals(TelephonyManager.NETWORK_TYPE_UNKNOWN, qtInfo.getDataNetworkType());
        assertEquals(TelephonyManager.NETWORK_TYPE_UNKNOWN, qtInfo.getVoiceNetworkType());
        assertEquals("", qtInfo.getRegisteredPlmn());
        assertFalse(qtInfo.isCoverage());
        assertFalse(qtInfo.isCellularAvailable());

        testOnCellularServiceStateChangedWithLteVopsOnHome();

        qtInfo = mQtListener.getLastQnsTelephonyInfo();
        assertEquals(ServiceState.STATE_IN_SERVICE, qtInfo.getDataRegState());
        assertEquals(TelephonyManager.NETWORK_TYPE_LTE, qtInfo.getDataNetworkType());
        assertEquals(TelephonyManager.NETWORK_TYPE_LTE, qtInfo.getVoiceNetworkType());
        assertEquals("00101", qtInfo.getRegisteredPlmn());
        assertFalse(qtInfo.isCoverage());
        assertTrue(qtInfo.isCellularAvailable());
        assertEquals(ServiceState.ROAMING_TYPE_NOT_ROAMING, qtInfo.getRoamingType());
    }

    @Test
    public void testGetLastPreciseDataConnectionState() {
        PreciseDataConnectionState output;
        List<Integer> typeIMS = new ArrayList<>();
        List<Integer> typeXcap = new ArrayList<>();
        typeIMS.add(ApnSetting.TYPE_IMS);
        typeXcap.add(ApnSetting.TYPE_XCAP);
        lenient().when(QnsUtils.getApnTypes(anyInt())).thenReturn(typeXcap).thenReturn(typeIMS);

        PreciseDataConnectionState connectionStateIms =
                new PreciseDataConnectionState.Builder()
                        .setTransportType(AccessNetworkConstants.TRANSPORT_TYPE_WWAN)
                        .setId(1)
                        .setState(TelephonyManager.DATA_CONNECTED)
                        .setNetworkType(TelephonyManager.NETWORK_TYPE_LTE)
                        .setApnSetting(
                                new ApnSetting.Builder()
                                        .setApnTypeBitmask(ApnSetting.TYPE_IMS)
                                        .setApnName("ims")
                                        .setEntryName("ims")
                                        .build())
                        .setLinkProperties(new LinkProperties())
                        .setFailCause(0)
                        .build();
        PreciseDataConnectionState connectionStateXcap =
                new PreciseDataConnectionState.Builder()
                        .setTransportType(AccessNetworkConstants.TRANSPORT_TYPE_WWAN)
                        .setId(1)
                        .setState(TelephonyManager.DATA_DISCONNECTED)
                        .setNetworkType(TelephonyManager.NETWORK_TYPE_LTE)
                        .setApnSetting(
                                new ApnSetting.Builder()
                                        .setApnTypeBitmask(ApnSetting.TYPE_XCAP)
                                        .setApnName("hos")
                                        .setEntryName("hos")
                                        .build())
                        .setLinkProperties(new LinkProperties())
                        .setFailCause(27)
                        .build();

        mQtListener.mTelephonyListener.onPreciseDataConnectionStateChanged(connectionStateXcap);
        mQtListener.mTelephonyListener.onPreciseDataConnectionStateChanged(connectionStateIms);

        output = mQtListener.getLastPreciseDataConnectionState(ApnSetting.TYPE_IMS);
        assertNotEquals(connectionStateXcap, output);
        assertEquals(connectionStateIms, output);

        output = mQtListener.getLastPreciseDataConnectionState(ApnSetting.TYPE_XCAP);
        assertEquals(connectionStateXcap, output);
    }

    @Test
    public void testValidatePreciseDataConnectionStateChanged() {
        PreciseDataConnectionState output;
        PreciseDataConnectionState connectedStateIms =
                new PreciseDataConnectionState.Builder()
                        .setTransportType(AccessNetworkConstants.TRANSPORT_TYPE_WWAN)
                        .setId(1)
                        .setState(TelephonyManager.DATA_CONNECTED)
                        .setNetworkType(TelephonyManager.NETWORK_TYPE_LTE)
                        .setApnSetting(
                                new ApnSetting.Builder()
                                        .setApnTypeBitmask(ApnSetting.TYPE_IMS)
                                        .setApnName("ims")
                                        .setEntryName("ims")
                                        .build())
                        .setLinkProperties(new LinkProperties())
                        .setFailCause(0)
                        .build();
        PreciseDataConnectionState handoverStateIms =
                new PreciseDataConnectionState.Builder()
                        .setTransportType(AccessNetworkConstants.TRANSPORT_TYPE_WLAN)
                        .setId(2)
                        .setState(TelephonyManager.DATA_HANDOVER_IN_PROGRESS)
                        .setNetworkType(TelephonyManager.NETWORK_TYPE_IWLAN)
                        .setApnSetting(
                                new ApnSetting.Builder()
                                        .setApnTypeBitmask(ApnSetting.TYPE_IMS)
                                        .setApnName("ims")
                                        .setEntryName("ims")
                                        .build())
                        .setLinkProperties(new LinkProperties())
                        .setFailCause(0)
                        .build();
        mQtListener.mTelephonyListener.onPreciseDataConnectionStateChanged(connectedStateIms);
        mQtListener.mTelephonyListener.onPreciseDataConnectionStateChanged(handoverStateIms);

        mQtListener.close();

        mQtListener = QnsTelephonyListener.getInstance(sMockContext, 0);

        mQtListener.mTelephonyListener.onPreciseDataConnectionStateChanged(handoverStateIms);
        output = mQtListener.getLastPreciseDataConnectionState(ApnSetting.TYPE_IMS);
        assertNull(output);

        mQtListener.mTelephonyListener.onPreciseDataConnectionStateChanged(connectedStateIms);

        output = mQtListener.getLastPreciseDataConnectionState(ApnSetting.TYPE_IMS);
        assertNull(output);
    }

    @Test
    public void testRegisterQnsTelephonyInfoChanged() {
        mQtListener.registerQnsTelephonyInfoChanged(ApnSetting.TYPE_IMS, h, 1, null, false);

        assertNotNull(mQtListener.mQnsTelephonyInfoRegistrantMap.get(ApnSetting.TYPE_IMS));
        assertEquals(1, mQtListener.mQnsTelephonyInfoRegistrantMap.get(ApnSetting.TYPE_IMS).size());
        assertEquals(0, mTestLooper.dispatchAll());

        mQtListener.registerQnsTelephonyInfoChanged(ApnSetting.TYPE_XCAP, h, 2, null, true);

        assertNotNull(mQtListener.mQnsTelephonyInfoRegistrantMap.get(ApnSetting.TYPE_XCAP));
        assertEquals(
                1, mQtListener.mQnsTelephonyInfoRegistrantMap.get(ApnSetting.TYPE_XCAP).size());
        assertEquals(1, mTestLooper.dispatchAll());
    }

    @Test
    public void testRegisterPreciseDataConnectionStateChanged() {
        mQtListener.registerPreciseDataConnectionStateChanged(
                ApnSetting.TYPE_IMS, h, 1, null, false);

        assertNotNull(mQtListener.mApnTypeRegistrantMap.get(ApnSetting.TYPE_IMS));
        assertEquals(1, mQtListener.mApnTypeRegistrantMap.get(ApnSetting.TYPE_IMS).size());
        assertEquals(0, mTestLooper.dispatchAll());

        mQtListener.registerPreciseDataConnectionStateChanged(
                ApnSetting.TYPE_XCAP, h, 2, null, true);

        assertNotNull(mQtListener.mApnTypeRegistrantMap.get(ApnSetting.TYPE_XCAP));
        assertEquals(1, mQtListener.mApnTypeRegistrantMap.get(ApnSetting.TYPE_XCAP).size());
        // not notified since precise data connection state is null for apn type.
        assertEquals(0, mTestLooper.dispatchAll());
    }

    @Test
    public void testRegisterCallStateListener() {
        mQtListener.registerCallStateListener(h, 1, null, false);

        assertEquals(1, mQtListener.mCallStateListener.size());
        assertEquals(0, mTestLooper.dispatchAll());

        mQtListener.registerCallStateListener(h, 2, null, true);

        assertEquals(2, mQtListener.mCallStateListener.size());
        assertEquals(1, mTestLooper.dispatchAll());
    }

    @Test
    public void testRegisterSrvccStateListener() {
        mQtListener.registerSrvccStateListener(h, 1, null);

        assertEquals(1, mQtListener.mSrvccStateListener.size());
        assertEquals(0, mTestLooper.dispatchAll());
    }

    @Test
    public void testUnregisterQnsTelephonyInfoChanged() {
        testRegisterQnsTelephonyInfoChanged();
        mQtListener.unregisterQnsTelephonyInfoChanged(ApnSetting.TYPE_IMS, h);

        assertNotNull(mQtListener.mQnsTelephonyInfoRegistrantMap.get(ApnSetting.TYPE_IMS));
        assertEquals(0, mQtListener.mQnsTelephonyInfoRegistrantMap.get(ApnSetting.TYPE_IMS).size());
        assertEquals(0, mTestLooper.dispatchAll());

        assertNotNull(mQtListener.mQnsTelephonyInfoRegistrantMap.get(ApnSetting.TYPE_XCAP));
        assertEquals(
                1, mQtListener.mQnsTelephonyInfoRegistrantMap.get(ApnSetting.TYPE_XCAP).size());
        assertEquals(0, mTestLooper.dispatchAll());

        mQtListener.unregisterQnsTelephonyInfoChanged(ApnSetting.TYPE_XCAP, h);
        assertEquals(
                0, mQtListener.mQnsTelephonyInfoRegistrantMap.get(ApnSetting.TYPE_XCAP).size());
        assertEquals(0, mTestLooper.dispatchAll());
    }

    @Test
    public void testUnregisterPreciseDataConnectionStateChanged() {
        testRegisterPreciseDataConnectionStateChanged();
        mQtListener.unregisterPreciseDataConnectionStateChanged(ApnSetting.TYPE_IMS, h);

        assertNotNull(mQtListener.mApnTypeRegistrantMap.get(ApnSetting.TYPE_IMS));
        assertEquals(0, mQtListener.mApnTypeRegistrantMap.get(ApnSetting.TYPE_IMS).size());
        assertEquals(0, mTestLooper.dispatchAll());

        assertNotNull(mQtListener.mApnTypeRegistrantMap.get(ApnSetting.TYPE_XCAP));
        assertEquals(1, mQtListener.mApnTypeRegistrantMap.get(ApnSetting.TYPE_XCAP).size());
        assertEquals(0, mTestLooper.dispatchAll());

        mQtListener.unregisterPreciseDataConnectionStateChanged(ApnSetting.TYPE_XCAP, h);
        assertEquals(0, mQtListener.mApnTypeRegistrantMap.get(ApnSetting.TYPE_XCAP).size());
        assertEquals(0, mTestLooper.dispatchAll());
    }

    @Test
    public void testUnregisterCallStateChanged() {
        testRegisterCallStateListener();
        mQtListener.unregisterCallStateChanged(h);

        assertNotNull(mQtListener.mCallStateListener);
        assertEquals(0, mQtListener.mCallStateListener.size());
        assertEquals(0, mTestLooper.dispatchAll());
    }

    @Test
    public void testUnregisterSrvccStateChanged() {
        testRegisterSrvccStateListener();
        mQtListener.unregisterSrvccStateChanged(h);

        assertNotNull(mQtListener.mSrvccStateListener);
        assertEquals(0, mQtListener.mSrvccStateListener.size());
        assertEquals(0, mTestLooper.dispatchAll());
    }

    @Test
    public void testStopTelephonyListener() {
        mQtListener.stopTelephonyListener(anyInt());
        verify(mockTelephonyManager).unregisterTelephonyCallback(any(TelephonyCallback.class));
    }

    @Test
    public void testStartTelephonyListener() {
        mQtListener.mSubscriptionsChangeListener.onSubscriptionsChanged();
        mQtListener.startTelephonyListener(1);
        verify(mockTelephonyManager, atLeastOnce())
                .registerTelephonyCallback(
                        anyInt(), any(Executor.class), any(TelephonyCallback.class));

        Mockito.clearInvocations(mockTelephonyManager);
        mQtListener.startTelephonyListener(-1);
        verify(mockTelephonyManager, never())
                .registerTelephonyCallback(
                        anyInt(), any(Executor.class), any(TelephonyCallback.class));
    }

    @Test
    public void testRegisterSubscriptionsIDChangedListener() {
        lenient()
                .when(QnsUtils.getSubId(isA(Context.class), anyInt()))
                .thenReturn(-1)
                .thenReturn(1);
        mQtListener.registerSubscriptionIdListener(h, EVENT_SUBSCRIPTION_ID_CHANGED, null);
        mQtListener.mSubscriptionsChangeListener.onSubscriptionsChanged();
        Message msg = mTestLooper.nextMessage();
        assertNull(msg);

        mQtListener.mSubscriptionsChangeListener.onSubscriptionsChanged();
        msg = mTestLooper.nextMessage();
        assertNotNull(msg);
        assertEquals(EVENT_SUBSCRIPTION_ID_CHANGED, msg.what);

        mQtListener.mSubscriptionsChangeListener.onSubscriptionsChanged();
        msg = mTestLooper.nextMessage();
        assertNull(msg);
    }

    @Test
    public void testUnregisterSubscriptionsIDChanged() {
        lenient().when(QnsUtils.getSubId(sMockContext, 0)).thenReturn(0);
        mQtListener.registerSubscriptionIdListener(h, EVENT_SUBSCRIPTION_ID_CHANGED, null);
        mQtListener.unregisterSubscriptionIdChanged(h);
        mQtListener.mSubscriptionsChangeListener.onSubscriptionsChanged();
        Message msg = mTestLooper.nextMessage();
        assertNull(msg);
    }

    @Test
    public void testOnCellularServiceStateChangedWithLteVopsOnHome() {
        VopsSupportInfo vopsSupportInfo =
                new LteVopsSupportInfo(
                        LteVopsSupportInfo.LTE_STATUS_SUPPORTED,
                        LteVopsSupportInfo.LTE_STATUS_SUPPORTED);
        setOnCellularServiceStateChangedWithLteVopsOn(
                vopsSupportInfo,
                NetworkRegistrationInfo.DOMAIN_PS,
                NetworkRegistrationInfo.REGISTRATION_STATE_HOME);

        assertEquals(
                ServiceState.STATE_IN_SERVICE,
                mQtListener.mLastQnsTelephonyInfoIms.getDataRegState());
        assertEquals(
                TelephonyManager.NETWORK_TYPE_LTE,
                mQtListener.mLastQnsTelephonyInfoIms.getDataNetworkType());
        assertEquals(
                TelephonyManager.NETWORK_TYPE_LTE,
                mQtListener.mLastQnsTelephonyInfoIms.getVoiceNetworkType());
        assertEquals("00101", mQtListener.mLastQnsTelephonyInfoIms.getRegisteredPlmn());
        assertEquals(
                ServiceState.ROAMING_TYPE_NOT_ROAMING,
                mQtListener.mLastQnsTelephonyInfoIms.getRoamingType());
        assertFalse(mQtListener.mLastQnsTelephonyInfoIms.isCoverage());
        assertTrue(mQtListener.mLastQnsTelephonyInfoIms.isCellularAvailable());
        assertFalse(mQtListener.mLastQnsTelephonyInfoIms.getEmergencyBarring());
        assertFalse(mQtListener.mLastQnsTelephonyInfoIms.getVoiceBarring());
        assertTrue(mQtListener.mLastQnsTelephonyInfoIms.getVopsEmergencySupport());
        assertTrue(mQtListener.mLastQnsTelephonyInfoIms.getVopsSupport());
    }

    @Test
    public void testOnCellularServiceStateChangedWithLteVopsOnRoam() {
        VopsSupportInfo vopsSupportInfo =
                new LteVopsSupportInfo(
                        LteVopsSupportInfo.LTE_STATUS_SUPPORTED,
                        LteVopsSupportInfo.LTE_STATUS_SUPPORTED);
        setOnCellularServiceStateChangedWithLteVopsOn(
                vopsSupportInfo,
                NetworkRegistrationInfo.DOMAIN_PS,
                NetworkRegistrationInfo.REGISTRATION_STATE_ROAMING);

        assertEquals(
                ServiceState.STATE_IN_SERVICE,
                mQtListener.mLastQnsTelephonyInfoIms.getDataRegState());
        assertEquals(
                TelephonyManager.NETWORK_TYPE_LTE,
                mQtListener.mLastQnsTelephonyInfoIms.getDataNetworkType());
        assertEquals(
                TelephonyManager.NETWORK_TYPE_LTE,
                mQtListener.mLastQnsTelephonyInfoIms.getVoiceNetworkType());
        assertEquals("00101", mQtListener.mLastQnsTelephonyInfoIms.getRegisteredPlmn());
        assertTrue(mQtListener.mLastQnsTelephonyInfoIms.isCoverage());
        assertTrue(mQtListener.mLastQnsTelephonyInfoIms.isCellularAvailable());
        assertFalse(mQtListener.mLastQnsTelephonyInfoIms.getEmergencyBarring());
        assertFalse(mQtListener.mLastQnsTelephonyInfoIms.getVoiceBarring());
        assertTrue(mQtListener.mLastQnsTelephonyInfoIms.getVopsEmergencySupport());
        assertTrue(mQtListener.mLastQnsTelephonyInfoIms.getVopsSupport());
    }

    @Test
    public void testOnCellularServiceStateChangedWithNrVopsOn() {
        VopsSupportInfo vopsSupportInfo =
                new NrVopsSupportInfo(
                        NR_STATUS_VOPS_3GPP_SUPPORTED,
                        NR_STATUS_VOPS_3GPP_SUPPORTED,
                        NR_STATUS_VOPS_3GPP_SUPPORTED);
        validateOnCellularServiceStateChangedWithNrVopsOn(vopsSupportInfo);
    }

    @Test
    public void testOnCellularServiceStateChangedWithLteVopsOff() {
        testOnCellularServiceStateChangedWithLteVopsOnHome();

        // Update Vops Information
        ServiceState ss = new ServiceState();
        VopsSupportInfo vopsSupportInfo =
                new LteVopsSupportInfo(
                        LteVopsSupportInfo.LTE_STATUS_NOT_SUPPORTED,
                        LteVopsSupportInfo.LTE_STATUS_NOT_SUPPORTED);
        NetworkRegistrationInfo nri =
                new NetworkRegistrationInfo(
                        NetworkRegistrationInfo.DOMAIN_PS,
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                        NetworkRegistrationInfo.REGISTRATION_STATE_HOME,
                        TelephonyManager.NETWORK_TYPE_LTE,
                        0,
                        false,
                        null,
                        null,
                        "00101",
                        10,
                        false,
                        true,
                        true,
                        vopsSupportInfo);
        ss.setRilVoiceRadioTechnology(ServiceState.RIL_RADIO_TECHNOLOGY_LTE);
        ss.addNetworkRegistrationInfo(nri);
        ss.setVoiceRegState(ServiceState.STATE_IN_SERVICE);
        assertTrue(mQtListener.mLastQnsTelephonyInfoIms.getVopsEmergencySupport());
        assertTrue(mQtListener.mLastQnsTelephonyInfoIms.getVopsSupport());
        mQtListener.mTelephonyListener.onServiceStateChanged(ss);

        // Validate Vops Updated information
        assertFalse(mQtListener.mLastQnsTelephonyInfoIms.getVopsEmergencySupport());
        assertFalse(mQtListener.mLastQnsTelephonyInfoIms.getVopsSupport());
    }

    private void setOnCellularServiceStateChangedWithLteVopsOn(
            VopsSupportInfo vopsSupportInfo, int domain, int coverage) {
        ServiceState ss = new ServiceState();
        NetworkRegistrationInfo nri =
                new NetworkRegistrationInfo(
                        domain,
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                        coverage,
                        TelephonyManager.NETWORK_TYPE_LTE,
                        0,
                        false,
                        null,
                        null,
                        "00101",
                        10,
                        false,
                        true,
                        true,
                        vopsSupportInfo);
        ss.setRilVoiceRadioTechnology(ServiceState.RIL_RADIO_TECHNOLOGY_LTE);
        ss.addNetworkRegistrationInfo(nri);
        ss.setVoiceRegState(ServiceState.STATE_IN_SERVICE);
        mQtListener.mTelephonyListener.onServiceStateChanged(ss);
        assertEquals(ss, mQtListener.mLastServiceState);
    }

    private void validateOnCellularServiceStateChangedWithNrVopsOn(
            VopsSupportInfo vopsSupportInfo) {
        ServiceState ss = new ServiceState();
        NetworkRegistrationInfo nri =
                new NetworkRegistrationInfo(
                        NetworkRegistrationInfo.DOMAIN_PS,
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                        NetworkRegistrationInfo.REGISTRATION_STATE_HOME,
                        TelephonyManager.NETWORK_TYPE_NR,
                        0,
                        false,
                        null,
                        null,
                        "00101",
                        10,
                        false,
                        true,
                        true,
                        vopsSupportInfo);
        ss.setRilVoiceRadioTechnology(ServiceState.RIL_RADIO_TECHNOLOGY_NR);
        ss.addNetworkRegistrationInfo(nri);
        ss.setVoiceRegState(ServiceState.STATE_IN_SERVICE);
        mQtListener.mTelephonyListener.onServiceStateChanged(ss);

        assertTrue(mQtListener.mLastQnsTelephonyInfoIms.getVopsEmergencySupport());
        assertTrue(mQtListener.mLastQnsTelephonyInfoIms.getVopsSupport());
    }

    @Test
    public void testIsAirplaneModeEnabled() {
        assertFalse(mQtListener.isAirplaneModeEnabled());

        ServiceState ss = new ServiceState();
        ss.setRilVoiceRadioTechnology(ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN);
        ss.setVoiceRegState(ServiceState.STATE_POWER_OFF);

        mQtListener.mTelephonyListener.onServiceStateChanged(ss);
        assertTrue(mQtListener.isAirplaneModeEnabled());
    }

    @Test
    public void testIsSupportVoPS() {
        assertFalse(mQtListener.isSupportVoPS());
        testOnCellularServiceStateChangedWithLteVopsOnHome();
        assertTrue(mQtListener.isSupportVoPS());
    }

    @Test
    public void testIsSupportVopsWithNullRegistrationInfo() {
        ServiceState ss = new ServiceState();
        ss.addNetworkRegistrationInfo(null);
        assertFalse(mQtListener.isSupportVoPS());
    }

    @Test
    public void testIsSupportEmergencyService() {
        assertFalse(mQtListener.isSupportEmergencyService());
        testOnCellularServiceStateChangedWithLteVopsOnHome();
        assertTrue(mQtListener.isSupportEmergencyService());
    }

    /**
     * This test covers test cases for notifyPreciseDataConnectionStateChanged() and
     * onPreciseDataConnectionStateChanged() methods.
     */
    @Test
    public void testOnPreciseDataConnectionStateChanged() {
        List<Integer> types = new ArrayList<>();
        types.add(ApnSetting.TYPE_IMS);
        lenient().when(QnsUtils.getApnTypes(anyInt())).thenReturn(types);

        PreciseDataConnectionState connectionState =
                new PreciseDataConnectionState.Builder()
                        .setTransportType(AccessNetworkConstants.TRANSPORT_TYPE_WWAN)
                        .setId(1)
                        .setState(TelephonyManager.DATA_CONNECTED)
                        .setNetworkType(TelephonyManager.NETWORK_TYPE_LTE)
                        .setApnSetting(
                                new ApnSetting.Builder()
                                        .setApnTypeBitmask(ApnSetting.TYPE_IMS)
                                        .setApnName("ims")
                                        .setEntryName("ims")
                                        .build())
                        .setLinkProperties(new LinkProperties())
                        .setFailCause(0)
                        .build();

        mQtListener.registerPreciseDataConnectionStateChanged(
                ApnSetting.TYPE_XCAP, h, 1, null, false);
        mQtListener.mTelephonyListener.onPreciseDataConnectionStateChanged(connectionState);

        Message msg = mTestLooper.nextMessage();
        assertNull(msg); // connection state is for IMS, but XCAP registered. So not notified.

        // update connection state for new precise data connection state change
        connectionState =
                new PreciseDataConnectionState.Builder()
                        .setTransportType(AccessNetworkConstants.TRANSPORT_TYPE_WLAN)
                        .setId(1)
                        .setState(TelephonyManager.DATA_CONNECTED)
                        .setNetworkType(TelephonyManager.NETWORK_TYPE_LTE)
                        .setApnSetting(
                                new ApnSetting.Builder()
                                        .setApnTypeBitmask(ApnSetting.TYPE_IMS)
                                        .setApnName("ims")
                                        .setEntryName("ims")
                                        .build())
                        .setLinkProperties(new LinkProperties())
                        .setFailCause(0)
                        .build();

        mQtListener.registerPreciseDataConnectionStateChanged(
                ApnSetting.TYPE_IMS, h, 1, null, false);
        mQtListener.mTelephonyListener.onPreciseDataConnectionStateChanged(connectionState);

        msg = mTestLooper.nextMessage();
        assertNotNull(msg);
        PreciseDataConnectionState output =
                (PreciseDataConnectionState) ((AsyncResult) msg.obj).result;
        assertEquals(connectionState, output);
    }

    @Test
    public void testIsVoiceBarring() {
        BarringInfo barringInfo = setupBarringInfo(true, false);
        mQtListener.mTelephonyListener.onBarringInfoChanged(barringInfo);
        assertTrue(mQtListener.isVoiceBarring());

        barringInfo = setupBarringInfo(false, false);
        mQtListener.mTelephonyListener.onBarringInfoChanged(barringInfo);
        assertFalse(mQtListener.isVoiceBarring());
    }

    @Test
    public void testIsEmergencyBarring() {
        BarringInfo barringInfo = setupBarringInfo(false, true);
        mQtListener.mTelephonyListener.onBarringInfoChanged(barringInfo);
        assertTrue(mQtListener.isEmergencyBarring());

        barringInfo = setupBarringInfo(false, false);
        mQtListener.mTelephonyListener.onBarringInfoChanged(barringInfo);
        assertFalse(mQtListener.isEmergencyBarring());
    }

    @Test
    public void testOnBarringInfoChanged_Supported() {
        mQtListener.registerQnsTelephonyInfoChanged(ApnSetting.TYPE_IMS, h, 1, null, false);

        BarringInfo barringInfo = setupBarringInfo(true, true);
        mQtListener.mTelephonyListener.onBarringInfoChanged(barringInfo);

        Message msg = mTestLooper.nextMessage();
        assertNotNull(msg);
        QnsTelephonyListener.QnsTelephonyInfoIms qtInfoIms =
                (QnsTelephonyListener.QnsTelephonyInfoIms) ((AsyncResult) msg.obj).result;
        assertTrue(qtInfoIms.getVoiceBarring());
        assertTrue(qtInfoIms.getEmergencyBarring());
    }

    @Test
    public void testOnBarringInfoChanged_SosNotSupported() {
        mQtListener.registerQnsTelephonyInfoChanged(ApnSetting.TYPE_IMS, h, 1, null, false);

        BarringInfo barringInfo = setupBarringInfo(true, false);
        mQtListener.mTelephonyListener.onBarringInfoChanged(barringInfo);

        Message msg = mTestLooper.nextMessage();
        assertNotNull(msg);
        QnsTelephonyListener.QnsTelephonyInfoIms qtInfoIms =
                (QnsTelephonyListener.QnsTelephonyInfoIms) ((AsyncResult) msg.obj).result;
        assertTrue(qtInfoIms.getVoiceBarring());
        assertFalse(qtInfoIms.getEmergencyBarring());
    }

    @Test
    public void testOnBarringInfoChanged_VoiceNotSupported() {
        mQtListener.registerQnsTelephonyInfoChanged(ApnSetting.TYPE_IMS, h, 1, null, false);

        BarringInfo barringInfo = setupBarringInfo(false, true);
        mQtListener.mTelephonyListener.onBarringInfoChanged(barringInfo);

        Message msg = mTestLooper.nextMessage();
        assertNotNull(msg);
        QnsTelephonyListener.QnsTelephonyInfoIms qtInfoIms =
                (QnsTelephonyListener.QnsTelephonyInfoIms) ((AsyncResult) msg.obj).result;
        assertFalse(qtInfoIms.getVoiceBarring());
        assertTrue(qtInfoIms.getEmergencyBarring());
    }

    private BarringInfo setupBarringInfo(boolean voiceBarring, boolean sosBarring) {
        SparseArray<BarringInfo.BarringServiceInfo> serviceInfos = new SparseArray<>();
        if (voiceBarring) {
            serviceInfos.put(
                    BarringInfo.BARRING_SERVICE_TYPE_MMTEL_VOICE,
                    new BarringInfo.BarringServiceInfo(
                            BarringInfo.BarringServiceInfo.BARRING_TYPE_UNCONDITIONAL,
                            true,
                            CONDITIONAL_BARRING_FACTOR,
                            CONDITIONAL_BARRING_TIME));
        } else {
            serviceInfos.put(
                    BarringInfo.BARRING_SERVICE_TYPE_MMTEL_VOICE,
                    new BarringInfo.BarringServiceInfo(
                            BarringInfo.BarringServiceInfo.BARRING_TYPE_UNCONDITIONAL,
                            false,
                            0,
                            0));
        }
        if (sosBarring) {
            serviceInfos.put(
                    BarringInfo.BARRING_SERVICE_TYPE_EMERGENCY,
                    new BarringInfo.BarringServiceInfo(
                            BarringInfo.BarringServiceInfo.BARRING_TYPE_UNCONDITIONAL,
                            true,
                            CONDITIONAL_BARRING_FACTOR,
                            CONDITIONAL_BARRING_TIME));

        } else {
            serviceInfos.put(
                    BarringInfo.BARRING_SERVICE_TYPE_EMERGENCY,
                    new BarringInfo.BarringServiceInfo(
                            BarringInfo.BarringServiceInfo.BARRING_TYPE_UNCONDITIONAL,
                            false,
                            0,
                            0));
        }

        return new BarringInfo(null, serviceInfos);
    }

    @Test
    public void testOnCallStateChanged() {
        mQtListener.registerCallStateListener(h, 1, null, false);
        int[] callStates =
                new int[] {
                    TelephonyManager.CALL_STATE_RINGING,
                    TelephonyManager.CALL_STATE_IDLE,
                    TelephonyManager.CALL_STATE_OFFHOOK
                };
        for (int state : callStates) {
            mQtListener.mTelephonyListener.onCallStateChanged(state);
            Message msg = mTestLooper.nextMessage();
            assertNotNull(msg);
            assertEquals(state, (int) ((AsyncResult) msg.obj).result);
        }
    }

    @Test
    public void testOnSrvccStateChanged() {
        mQtListener.registerSrvccStateListener(h, 1, null);
        int[] srvccStates =
                new int[] {
                    TelephonyManager.SRVCC_STATE_HANDOVER_NONE,
                    TelephonyManager.SRVCC_STATE_HANDOVER_STARTED,
                    TelephonyManager.SRVCC_STATE_HANDOVER_COMPLETED,
                    TelephonyManager.SRVCC_STATE_HANDOVER_FAILED,
                    TelephonyManager.SRVCC_STATE_HANDOVER_CANCELED
                };
        for (int state : srvccStates) {
            mQtListener.mTelephonyListener.onSrvccStateChanged(state);
            Message msg = mTestLooper.nextMessage();
            assertNotNull(msg);
            assertEquals(state, (int) ((AsyncResult) msg.obj).result);
        }
    }

    @Test
    public void testQnsTelephonyInfo() {
        QnsTelephonyListener.QnsTelephonyInfo qtInfo = mQtListener.new QnsTelephonyInfo();

        // test default values
        assertEquals(ServiceState.ROAMING_TYPE_NOT_ROAMING, qtInfo.getRoamingType());
        assertFalse(qtInfo.isCoverage());
        assertEquals(TelephonyManager.NETWORK_TYPE_UNKNOWN, qtInfo.getVoiceNetworkType());
        assertEquals(TelephonyManager.NETWORK_TYPE_UNKNOWN, qtInfo.getDataNetworkType());
        assertFalse(qtInfo.isCellularAvailable());
        assertEquals("", qtInfo.getRegisteredPlmn());
        assertEquals(ServiceState.STATE_OUT_OF_SERVICE, qtInfo.getDataRegState());

        // test setters
        qtInfo.setRoamingType(ServiceState.ROAMING_TYPE_DOMESTIC);
        assertEquals(ServiceState.ROAMING_TYPE_DOMESTIC, qtInfo.getRoamingType());

        qtInfo.setCoverage(true);
        assertTrue(qtInfo.isCoverage());

        qtInfo.setVoiceNetworkType(TelephonyManager.NETWORK_TYPE_LTE);
        assertEquals(TelephonyManager.NETWORK_TYPE_LTE, qtInfo.getVoiceNetworkType());

        qtInfo.setDataNetworkType(TelephonyManager.NETWORK_TYPE_EDGE);
        assertEquals(TelephonyManager.NETWORK_TYPE_EDGE, qtInfo.getDataNetworkType());

        qtInfo.setCellularAvailable(true);
        assertTrue(qtInfo.isCellularAvailable());

        qtInfo.setRegisteredPlmn("405861");
        assertEquals("405861", qtInfo.getRegisteredPlmn());

        qtInfo.setDataRegState(ServiceState.STATE_IN_SERVICE);
        assertEquals(ServiceState.STATE_IN_SERVICE, qtInfo.getDataRegState());
    }

    @Test
    public void testQnsTelephonyInfoIms() {
        QnsTelephonyListener.QnsTelephonyInfo qtInfo = mQtListener.new QnsTelephonyInfo();
        QnsTelephonyListener.QnsTelephonyInfoIms qtInfoIms = mQtListener.new QnsTelephonyInfoIms();

        // test defaults
        assertFalse(qtInfoIms.getEmergencyBarring());
        assertFalse(qtInfoIms.getVopsSupport());
        assertFalse(qtInfoIms.getVoiceBarring());
        assertFalse(qtInfoIms.getVopsEmergencySupport());

        // test setters
        qtInfoIms.setEmergencyBarring(true);
        assertTrue(qtInfoIms.getEmergencyBarring());

        qtInfoIms.setVoiceBarring(true);
        assertTrue(qtInfoIms.getVoiceBarring());

        qtInfoIms.setVopsSupport(true);
        assertTrue(qtInfoIms.getVopsSupport());

        qtInfoIms.setVopsEmergencySupport(true);
        assertTrue(qtInfoIms.getVopsEmergencySupport());

        // test constructor
        qtInfoIms = mQtListener.new QnsTelephonyInfoIms(qtInfo, false, true, false, true);
        assertTrue(qtInfoIms.getEmergencyBarring());
        assertFalse(qtInfoIms.getVopsSupport());
        assertFalse(qtInfoIms.getVoiceBarring());
        assertTrue(qtInfoIms.getVopsEmergencySupport());
    }

    @Test
    public void testNullTelephonyListener() {
        mQtListener.close();
        mHandlerThread.quit();
        setReady(false);
        Mockito.clearInvocations(mockTelephonyManager);
        when(mockSubscriptionInfo.getSubscriptionId()).thenReturn(-1);
        HandlerThread ht =
                new HandlerThread("") {
                    @Override
                    protected void onLooperPrepared() {
                        mQtListener = QnsTelephonyListener.getInstance(sMockContext, 0);
                        setReady(true);
                    }
                };
        ht.start();
        waitUntilReady();
        mQtListener.startTelephonyListener(-1);
        verify(mockTelephonyManager, never())
                .registerTelephonyCallback(isA(Executor.class), isA(TelephonyCallback.class));
        ht.quit();
    }
}
