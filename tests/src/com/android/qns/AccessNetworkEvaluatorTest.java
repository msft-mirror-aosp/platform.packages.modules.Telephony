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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNotNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.AsyncResult;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.telephony.AccessNetworkConstants;
import android.telephony.ServiceState;
import android.telephony.SignalThresholdInfo;
import android.telephony.TelephonyManager;
import android.telephony.data.ApnSetting;
import android.telephony.ims.ProvisioningManager;

import com.android.qns.AccessNetworkSelectionPolicy.PreCondition;
import com.android.qns.QnsProvisioningListener.QnsProvisioningInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(JUnit4.class)
public class AccessNetworkEvaluatorTest extends QnsTest {
    private static final String TEST_PLMN = "707333";
    private static final int QUALIFIED_NETWORKS_CHANGED = 2;

    private static final int EVENT_BASE = 10000;
    private static final int EVENT_IWLAN_NETWORK_STATUS_CHANGED = EVENT_BASE;
    private static final int EVENT_QNS_TELEPHONY_INFO_CHANGED = EVENT_BASE + 1;
    private static final int EVENT_RESTRICT_INFO_CHANGED = EVENT_BASE + 4;
    private static final int EVENT_SET_CALL_TYPE = EVENT_BASE + 5;
    private static final int EVENT_DATA_CONNECTION_STATE_CHANGED = EVENT_BASE + 6;
    private static final int EVENT_EMERGENCY_PREFERRED_TRANSPORT_TYPE_CHANGED = EVENT_BASE + 7;
    private static final int EVENT_PROVISIONING_INFO_CHANGED = EVENT_BASE + 8;
    private static final int EVENT_WFC_ACTIVATION_WITH_IWLAN_CONNECTION_REQUIRED = EVENT_BASE + 9;
    private static final int EVENT_IMS_REGISTRATION_STATE_CHANGED = EVENT_BASE + 10;

    @Mock private RestrictManager restrictManager;
    @Mock private QnsCarrierConfigManager configManager;
    @Mock private QualityMonitor wifiQualityMonitor;
    @Mock private QualityMonitor cellularQualityMonitor;
    @Mock private CellularNetworkStatusTracker cellularNetworkStatusTracker;
    @Mock private IwlanNetworkStatusTracker iwlanNetworkStatusTracker;
    @Mock private DataConnectionStatusTracker dataConnectionStatusTracker;
    @Mock private QnsEventDispatcher qnsEventDispatcher;
    @Mock private AlternativeEventListener altEventListener;
    @Mock private QnsProvisioningListener mQnsProvisioningListener;
    @Mock private QnsTelephonyListener qnsTelephonyListener;
    @Mock private ImsStatusListener qnsImsStatusListener;
    private AccessNetworkEvaluator ane;
    private Handler mHandler;
    private Handler mEvaluatorHandler;
    private QualifiedNetworksServiceImpl.QualifiedNetworksInfo mQualifiedNetworksInfo;
    private Map<PreCondition, List<AccessNetworkSelectionPolicy>> mTestAnspPolicyMap = null;
    private CountDownLatch latch;
    private int mSlotIndex = 0;
    private int mApnType = ApnSetting.TYPE_IMS;
    private HandlerThread mHandlerThread;
    private MockitoSession mMockitoSession;
    private int mRatPreference = QnsConstants.RAT_PREFERENCE_DEFAULT;

    private class AneHandler extends Handler {
        AneHandler() {
            super(mHandlerThread.getLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            switch (msg.what) {
                case QUALIFIED_NETWORKS_CHANGED:
                    AsyncResult ar = (AsyncResult) msg.obj;
                    mQualifiedNetworksInfo =
                            (QualifiedNetworksServiceImpl.QualifiedNetworksInfo) ar.result;
                    latch.countDown();
                    break;
                default:
                    break;
            }
        }
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        super.setUp();
        mHandlerThread = new HandlerThread("");
        latch = new CountDownLatch(1);
        mMockitoSession = null;
        ane =
                new AccessNetworkEvaluator(
                        mSlotIndex,
                        mApnType,
                        sMockContext,
                        restrictManager,
                        configManager,
                        wifiQualityMonitor,
                        cellularQualityMonitor,
                        cellularNetworkStatusTracker,
                        iwlanNetworkStatusTracker,
                        dataConnectionStatusTracker,
                        qnsEventDispatcher,
                        altEventListener,
                        mQnsProvisioningListener,
                        qnsImsStatusListener);
        mHandlerThread.start();
        mHandler = new AneHandler();
        ArgumentCaptor<Handler> capture = ArgumentCaptor.forClass(Handler.class);
        verify(iwlanNetworkStatusTracker)
                .registerIwlanNetworksChanged(anyInt(), capture.capture(), anyInt());
        mEvaluatorHandler = capture.getValue();
    }

    private void stubQnsStatics(
            int settingWfcMode,
            int settingWfcRoamingMode,
            boolean wfcPlatformEnabled,
            boolean settingWfcEnabled,
            boolean settingWfcRoamingEnabled) {
        when(QnsUtils.isWfcEnabledByPlatform(sMockContext, mSlotIndex))
                .thenReturn(wfcPlatformEnabled);
        when(QnsUtils.isWfcEnabled(sMockContext, mSlotIndex, false)).thenReturn(settingWfcEnabled);
        when(QnsUtils.getWfcMode(sMockContext, mSlotIndex, false)).thenReturn(settingWfcMode);
        when(QnsUtils.isWfcEnabled(sMockContext, mSlotIndex, true))
                .thenReturn(settingWfcRoamingEnabled);
        when(QnsUtils.getWfcMode(sMockContext, mSlotIndex, true)).thenReturn(settingWfcRoamingMode);
    }

    @After
    public void tearDown() throws Exception {
        if (mMockitoSession != null) {
            mMockitoSession.finishMocking();
        }
        if (ane != null) {
            ane.close();
        }
        if (mHandlerThread != null) {
            mHandlerThread.quit();
        }
        QnsCarrierConfigManager.getInstance(sMockContext, 0).close();
        QnsCarrierConfigManager.getInstance(sMockContext, 1).close();
        IwlanNetworkStatusTracker.getInstance(sMockContext).close();
        WifiQualityMonitor.getInstance(sMockContext).dispose();
    }

    @Test
    public void testRegisterForQualifiedNetworksChanged() {
        Handler h2 = new Handler(Looper.getMainLooper());
        ane.registerForQualifiedNetworksChanged(mHandler, QUALIFIED_NETWORKS_CHANGED);
        ane.registerForQualifiedNetworksChanged(mHandler, 2);
        assertEquals(1, ane.mQualifiedNetworksChangedRegistrants.size());
        ane.registerForQualifiedNetworksChanged(h2, 3);
        assertEquals(2, ane.mQualifiedNetworksChangedRegistrants.size());
    }

    @Test
    public void testUnregisterForQualifiedNetworksChanged() {
        Handler h2 = new Handler(Looper.getMainLooper());
        ane.registerForQualifiedNetworksChanged(mHandler, QUALIFIED_NETWORKS_CHANGED);
        ane.registerForQualifiedNetworksChanged(h2, 3);
        assertEquals(2, ane.mQualifiedNetworksChangedRegistrants.size());
        ane.unregisterForQualifiedNetworksChanged(h2);
        assertEquals(1, ane.mQualifiedNetworksChangedRegistrants.size());
    }

    /**
     * This test covers test cases for below methods of ANE: updateLastNotifiedQualifiedNetwork(),
     * getLastQualifiedTransportType(), initLastNotifiedQualifiedNetwork(),
     * equalsLastNotifiedQualifiedNetwork()
     */
    @Test
    public void testLastNotifiedQualifiedNetwork() {
        List<Integer> accessNetworks = new ArrayList<>();
        accessNetworks.add(AccessNetworkConstants.AccessNetworkType.EUTRAN);
        ane.updateLastNotifiedQualifiedNetwork(accessNetworks);
        assertFalse(
                ane.equalsLastNotifiedQualifiedNetwork(
                        List.of(AccessNetworkConstants.AccessNetworkType.IWLAN)));
        assertTrue(
                ane.equalsLastNotifiedQualifiedNetwork(
                        List.of(AccessNetworkConstants.AccessNetworkType.EUTRAN)));
        assertEquals(
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN, ane.getLastQualifiedTransportType());
        ane.initLastNotifiedQualifiedNetwork();
        assertEquals(
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN, ane.getLastQualifiedTransportType());

        accessNetworks.clear();
        accessNetworks.add(AccessNetworkConstants.AccessNetworkType.NGRAN);
        ane.updateLastNotifiedQualifiedNetwork(accessNetworks);
        assertFalse(
                ane.equalsLastNotifiedQualifiedNetwork(
                        List.of(AccessNetworkConstants.AccessNetworkType.IWLAN)));
        assertFalse(
                ane.equalsLastNotifiedQualifiedNetwork(
                        List.of(AccessNetworkConstants.AccessNetworkType.EUTRAN)));
        assertTrue(
                ane.equalsLastNotifiedQualifiedNetwork(
                        List.of(AccessNetworkConstants.AccessNetworkType.NGRAN)));
        assertEquals(
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN, ane.getLastQualifiedTransportType());
        ane.initLastNotifiedQualifiedNetwork();
        assertEquals(
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN, ane.getLastQualifiedTransportType());

        accessNetworks.clear();
        accessNetworks.add(AccessNetworkConstants.AccessNetworkType.IWLAN);
        ane.updateLastNotifiedQualifiedNetwork(accessNetworks);
        assertTrue(
                ane.equalsLastNotifiedQualifiedNetwork(
                        List.of(AccessNetworkConstants.AccessNetworkType.IWLAN)));
        assertFalse(
                ane.equalsLastNotifiedQualifiedNetwork(
                        List.of(AccessNetworkConstants.AccessNetworkType.EUTRAN)));
        assertEquals(
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN, ane.getLastQualifiedTransportType());
        ane.initLastNotifiedQualifiedNetwork();
        assertEquals(
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN, ane.getLastQualifiedTransportType());
    }

    @Test
    public void testNotifyForQualifiedNetworksChanged() throws Exception {
        latch = new CountDownLatch(1);
        List<Integer> accessNetworks = new ArrayList<>();
        accessNetworks.add(AccessNetworkConstants.AccessNetworkType.EUTRAN);
        ane.registerForQualifiedNetworksChanged(mHandler, QUALIFIED_NETWORKS_CHANGED);
        ane.notifyForQualifiedNetworksChanged(accessNetworks);
        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertEquals(accessNetworks, mQualifiedNetworksInfo.getAccessNetworkTypes());
    }

    @Test
    public void testMoveTransportTypeAllowed() {
        when(configManager.isHandoverAllowedByPolicy(
                        ApnSetting.TYPE_IMS,
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        QnsConstants.COVERAGE_HOME))
                .thenReturn(true);
        when(configManager.isHandoverAllowedByPolicy(
                        ApnSetting.TYPE_IMS,
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        AccessNetworkConstants.AccessNetworkType.NGRAN,
                        QnsConstants.COVERAGE_ROAM))
                .thenReturn(false);
        when(configManager.isVolteRoamingSupported(anyInt())).thenReturn(true);
        when(dataConnectionStatusTracker.isActiveState()).thenReturn(true);
        when(dataConnectionStatusTracker.getLastTransportType())
                .thenReturn(AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
        List<Integer> accessNetworks = new ArrayList<>();
        accessNetworks.add(AccessNetworkConstants.AccessNetworkType.IWLAN);
        QnsTelephonyListener.QnsTelephonyInfo info = qnsTelephonyListener.new QnsTelephonyInfo();
        info.setCellularAvailable(false);
        info.setCoverage(false);
        info.setDataNetworkType(TelephonyManager.NETWORK_TYPE_UNKNOWN);
        info.setVoiceNetworkType(TelephonyManager.NETWORK_TYPE_UNKNOWN);
        info.setDataRegState(ServiceState.STATE_OUT_OF_SERVICE);
        QnsTelephonyListener.QnsTelephonyInfoIms infoIms =
                qnsTelephonyListener.new QnsTelephonyInfoIms(info, true, true, false, false);
        ane.onQnsTelephonyInfoChanged(infoIms);
        ane.updateLastNotifiedQualifiedNetwork(accessNetworks);
        assertFalse(ane.needHandoverPolicyCheck());
        assertTrue(ane.moveTransportTypeAllowed());

        info = qnsTelephonyListener.new QnsTelephonyInfo();
        info.setCellularAvailable(true);
        info.setCoverage(true);
        info.setDataNetworkType(TelephonyManager.NETWORK_TYPE_LTE);
        info.setVoiceNetworkType(TelephonyManager.NETWORK_TYPE_LTE);
        info.setDataRegState(ServiceState.STATE_IN_SERVICE);
        infoIms = qnsTelephonyListener.new QnsTelephonyInfoIms(info, true, true, true, true);
        ane.onQnsTelephonyInfoChanged(infoIms);
        ane.updateLastNotifiedQualifiedNetwork(accessNetworks);
        assertTrue(ane.needHandoverPolicyCheck());
        assertTrue(ane.moveTransportTypeAllowed());

        info = qnsTelephonyListener.new QnsTelephonyInfo();
        info.setCellularAvailable(true);
        info.setCoverage(false);
        info.setDataNetworkType(TelephonyManager.NETWORK_TYPE_NR);
        info.setVoiceNetworkType(TelephonyManager.NETWORK_TYPE_NR);
        info.setDataRegState(ServiceState.STATE_IN_SERVICE);
        infoIms = qnsTelephonyListener.new QnsTelephonyInfoIms(info, true, true, true, true);
        ane.onQnsTelephonyInfoChanged(infoIms);
        ane.updateLastNotifiedQualifiedNetwork(accessNetworks);
        assertTrue(ane.needHandoverPolicyCheck());
        assertTrue(ane.moveTransportTypeAllowed());
    }

    @Test
    public void testMoveTransportTypeAllowedEmergencyOverIms() {
        when(configManager.isHandoverAllowedByPolicy(
                        ApnSetting.TYPE_IMS,
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        QnsConstants.COVERAGE_HOME))
                .thenReturn(true);
        when(configManager.isHandoverAllowedByPolicy(
                        ApnSetting.TYPE_IMS,
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        QnsConstants.COVERAGE_HOME))
                .thenReturn(true);
        when(configManager.isVolteRoamingSupported(anyInt())).thenReturn(true);
        when(dataConnectionStatusTracker.isActiveState()).thenReturn(true);
        when(dataConnectionStatusTracker.getLastTransportType())
                .thenReturn(AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
        List<Integer> accessNetworks = new ArrayList<>();
        accessNetworks.add(AccessNetworkConstants.AccessNetworkType.IWLAN);
        QnsTelephonyListener.QnsTelephonyInfo info = qnsTelephonyListener.new QnsTelephonyInfo();
        info.setCellularAvailable(true);
        info.setCoverage(false);
        info.setDataNetworkType(TelephonyManager.NETWORK_TYPE_LTE);
        info.setVoiceNetworkType(TelephonyManager.NETWORK_TYPE_LTE);
        info.setDataRegState(ServiceState.STATE_IN_SERVICE);
        QnsTelephonyListener.QnsTelephonyInfoIms infoIms =
                qnsTelephonyListener.new QnsTelephonyInfoIms(info, true, true, false, false);
        ane.onQnsTelephonyInfoChanged(infoIms);
        ane.updateLastNotifiedQualifiedNetwork(accessNetworks);
        ane.onSetCallType(QnsConstants.CALL_TYPE_EMERGENCY);
        assertTrue(ane.needHandoverPolicyCheck());
        assertFalse(ane.moveTransportTypeAllowed());

        when(configManager.isHandoverAllowedByPolicy(
                        ApnSetting.TYPE_IMS,
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        AccessNetworkConstants.AccessNetworkType.UTRAN,
                        QnsConstants.COVERAGE_HOME))
                .thenReturn(false);
        info = qnsTelephonyListener.new QnsTelephonyInfo();
        info.setCellularAvailable(true);
        info.setCoverage(false);
        info.setDataNetworkType(TelephonyManager.NETWORK_TYPE_UMTS);
        info.setVoiceNetworkType(TelephonyManager.NETWORK_TYPE_UMTS);
        info.setDataRegState(ServiceState.STATE_IN_SERVICE);
        infoIms = qnsTelephonyListener.new QnsTelephonyInfoIms(info, true, true, false, false);
        ane.onQnsTelephonyInfoChanged(infoIms);
        ane.updateLastNotifiedQualifiedNetwork(accessNetworks);
        ane.onSetCallType(QnsConstants.CALL_TYPE_VOICE);
        assertTrue(ane.needHandoverPolicyCheck());
        assertFalse(ane.moveTransportTypeAllowed());
        ane.updateLastNotifiedQualifiedNetwork(accessNetworks);
        ane.onSetCallType(QnsConstants.CALL_TYPE_IDLE);
        assertTrue(ane.needHandoverPolicyCheck());
        assertTrue(ane.moveTransportTypeAllowed());
        ane.updateLastNotifiedQualifiedNetwork(accessNetworks);
        ane.onSetCallType(QnsConstants.CALL_TYPE_EMERGENCY);
        assertTrue(ane.needHandoverPolicyCheck());
        assertFalse(ane.moveTransportTypeAllowed());
    }

    @Test
    public void testVopsCheckRequired() {
        when(dataConnectionStatusTracker.getLastTransportType())
                .thenReturn(AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
        when(configManager.isHandoverAllowedByPolicy(anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(true);
        when(configManager.isMmtelCapabilityRequired(QnsConstants.COVERAGE_HOME)).thenReturn(true);
        when(configManager.isMmtelCapabilityRequired(QnsConstants.COVERAGE_ROAM)).thenReturn(false);
        when(configManager.isInCallHoDecisionWlanToWwanWithoutVopsCondition()).thenReturn(false);
        assertTrue(
                ane.vopsCheckRequired(
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        QnsConstants.COVERAGE_HOME,
                        QnsConstants.CALL_TYPE_IDLE));
        assertFalse(
                ane.vopsCheckRequired(
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        QnsConstants.COVERAGE_ROAM,
                        QnsConstants.CALL_TYPE_IDLE));
        when(configManager.isInCallHoDecisionWlanToWwanWithoutVopsCondition()).thenReturn(true);
        assertFalse(
                ane.vopsCheckRequired(
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        QnsConstants.COVERAGE_HOME,
                        QnsConstants.CALL_TYPE_VOICE));
        when(dataConnectionStatusTracker.getLastTransportType())
                .thenReturn(AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        assertTrue(
                ane.vopsCheckRequired(
                        AccessNetworkConstants.AccessNetworkType.NGRAN,
                        QnsConstants.COVERAGE_HOME,
                        QnsConstants.CALL_TYPE_IDLE));
        assertFalse(
                ane.vopsCheckRequired(
                        AccessNetworkConstants.AccessNetworkType.NGRAN,
                        QnsConstants.COVERAGE_HOME,
                        QnsConstants.CALL_TYPE_VOICE));
        assertFalse(
                ane.vopsCheckRequired(
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        QnsConstants.COVERAGE_ROAM,
                        QnsConstants.CALL_TYPE_IDLE));
    }

    /**
     * This method covers test for onCellularQualityChanged(), on WifiQualityChanged(),
     * onQnsTelephonyInfoChanged() and evaluate() methods of ANE. Since all these methods call for
     * the evaluation of access networks, testing them separately will repeat the test cases.
     */
    @Test
    public void testEvaluationOfQualifiedNetwork() throws Exception {
        latch = new CountDownLatch(1);
        ane.registerForQualifiedNetworksChanged(mHandler, QUALIFIED_NETWORKS_CHANGED);
        waitFor(200);
        List<Integer> accessNetworks = new ArrayList<>();
        accessNetworks.add(AccessNetworkConstants.AccessNetworkType.IWLAN);
        ane.updateLastNotifiedQualifiedNetwork(accessNetworks);

        when(configManager.isMmtelCapabilityRequired(anyInt())).thenReturn(true);
        when(configManager.isServiceBarringCheckSupported()).thenReturn(true);
        when(configManager.isInCallHoDecisionWlanToWwanWithoutVopsCondition()).thenReturn(false);
        when(configManager.isHandoverAllowedByPolicy(anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(true);
        when(configManager.allowWFCOnAirplaneModeOn()).thenReturn(true);
        when(configManager.isAccessNetworkAllowed(anyInt(), anyInt())).thenReturn(true);
        when(configManager.isVolteRoamingSupported(anyInt())).thenReturn(true);
        when(configManager.blockIwlanInInternationalRoamWithoutWwan()).thenReturn(false);
        when(configManager.getRatPreference(ApnSetting.TYPE_IMS))
                .thenReturn(QnsConstants.RAT_PREFERENCE_DEFAULT);

        when(dataConnectionStatusTracker.isActiveState()).thenReturn(true);
        when(dataConnectionStatusTracker.isInactiveState()).thenReturn(true);
        when(dataConnectionStatusTracker.isHandoverState()).thenReturn(true);

        when(restrictManager.isRestricted(anyInt())).thenReturn(true);
        when(restrictManager.isAllowedOnSingleTransport(anyInt())).thenReturn(true);

        QnsTelephonyListener.QnsTelephonyInfo info = qnsTelephonyListener.new QnsTelephonyInfo();
        info.setCellularAvailable(true);
        info.setCoverage(true);
        info.setDataNetworkType(TelephonyManager.NETWORK_TYPE_LTE);
        info.setVoiceNetworkType(TelephonyManager.NETWORK_TYPE_LTE);
        info.setDataRegState(ServiceState.STATE_IN_SERVICE);
        QnsTelephonyListener.QnsTelephonyInfoIms infoIms =
                qnsTelephonyListener.new QnsTelephonyInfoIms(info, true, true, false, false);
        Message.obtain(
                        mEvaluatorHandler,
                        EVENT_QNS_TELEPHONY_INFO_CHANGED,
                        new AsyncResult(null, info, null))
                .sendToTarget();
        waitFor(100);
        // ane.onQnsTelephonyInfoChanged(infoIms);

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        ArrayList<Integer> expected = new ArrayList<>();
        expected.add(AccessNetworkConstants.AccessNetworkType.EUTRAN);
        assertEquals(expected, mQualifiedNetworksInfo.getAccessNetworkTypes());
    }

    @Test
    public void testOnIwlanNetworkStatusChanged() {
        when(restrictManager.isRestricted(anyInt())).thenReturn(false);
        when(restrictManager.isAllowedOnSingleTransport(anyInt())).thenReturn(true);
        Message.obtain(
                        mEvaluatorHandler,
                        EVENT_IWLAN_NETWORK_STATUS_CHANGED,
                        new AsyncResult(
                                null,
                                iwlanNetworkStatusTracker.new IwlanAvailabilityInfo(true, false),
                                null))
                .sendToTarget();
        waitFor(50);
        assertTrue(ane.mIwlanAvailable);
        Message.obtain(
                        mEvaluatorHandler,
                        EVENT_IWLAN_NETWORK_STATUS_CHANGED,
                        new AsyncResult(
                                null,
                                iwlanNetworkStatusTracker.new IwlanAvailabilityInfo(false, false),
                                null))
                .sendToTarget();
        waitFor(50);
        assertFalse(ane.mIwlanAvailable);
    }

    @Test
    public void testOnTryWfcConnectionStateChanged() {
        mMockitoSession =
                mockitoSession()
                        .strictness(Strictness.LENIENT)
                        .mockStatic(QnsUtils.class)
                        .startMocking();
        stubQnsStatics(QnsConstants.CELL_PREF, QnsConstants.CELL_PREF, true, true, true);
        ane.onTryWfcConnectionStateChanged(true);
        Message.obtain(
                        mEvaluatorHandler,
                        EVENT_WFC_ACTIVATION_WITH_IWLAN_CONNECTION_REQUIRED,
                        new AsyncResult(null, true, null))
                .sendToTarget();
        waitFor(100);
        assertTrue(ane.isWfcEnabled());
        assertEquals(QnsConstants.WIFI_PREF, ane.getPreferredMode());
        Message.obtain(
                        mEvaluatorHandler,
                        EVENT_WFC_ACTIVATION_WITH_IWLAN_CONNECTION_REQUIRED,
                        new AsyncResult(null, false, null))
                .sendToTarget();
        waitFor(100);
        assertFalse(ane.isWfcEnabled());
        assertEquals(QnsConstants.CELL_PREF, ane.getPreferredMode());
    }

    @Test
    public void testGetTargetTransportType() {
        List<Integer> accessNetworks = new ArrayList<>();
        accessNetworks.add(AccessNetworkConstants.AccessNetworkType.EUTRAN);
        assertEquals(
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                ane.getTargetTransportType(accessNetworks));
        accessNetworks.clear();

        accessNetworks.add(AccessNetworkConstants.AccessNetworkType.IWLAN);
        assertEquals(
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                ane.getTargetTransportType(accessNetworks));
        accessNetworks.clear();

        accessNetworks.add(AccessNetworkConstants.AccessNetworkType.UNKNOWN);
        assertEquals(
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                ane.getTargetTransportType(accessNetworks));
        accessNetworks.clear();

        accessNetworks.add(AccessNetworkConstants.AccessNetworkType.NGRAN);
        assertEquals(
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                ane.getTargetTransportType(accessNetworks));
        accessNetworks.clear();

        accessNetworks = null;
        assertEquals(
                AccessNetworkConstants.TRANSPORT_TYPE_INVALID,
                ane.getTargetTransportType(accessNetworks));
    }

    @Test
    public void testIsHandoverNeeded() {
        List<Integer> accessNetworks = new ArrayList<>();
        accessNetworks.add(AccessNetworkConstants.AccessNetworkType.EUTRAN);
        when(dataConnectionStatusTracker.isInactiveState()).thenReturn(true, false);
        when(dataConnectionStatusTracker.isHandoverState()).thenReturn(true, false);
        when(dataConnectionStatusTracker.getLastTransportType())
                .thenReturn(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
        assertFalse(ane.isHandoverNeeded(accessNetworks)); // isInactiveState
        assertFalse(ane.isHandoverNeeded(accessNetworks)); // isHandoverState
        assertFalse(ane.isHandoverNeeded(accessNetworks)); // same last TransportType
        assertTrue(ane.isHandoverNeeded(accessNetworks)); // all OK
    }

    @Test
    public void testIsFallbackCase() {
        List<Integer> accessNetworks = new ArrayList<>();
        accessNetworks.add(AccessNetworkConstants.AccessNetworkType.EUTRAN);
        ane.updateLastNotifiedQualifiedNetwork(accessNetworks);
        when(dataConnectionStatusTracker.isInactiveState()).thenReturn(true, false);
        when(dataConnectionStatusTracker.getLastTransportType())
                .thenReturn(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        assertFalse(ane.isFallbackCase(accessNetworks)); // isInactiveState
        assertFalse(ane.isFallbackCase(accessNetworks)); // target TT != last TT
        assertFalse(ane.isFallbackCase(accessNetworks)); // LastQualifiedTransportType == TT
        List<Integer> accessNetworks2 = new ArrayList<>();
        accessNetworks2.add(AccessNetworkConstants.AccessNetworkType.IWLAN);
        ane.updateLastNotifiedQualifiedNetwork(accessNetworks2);
        assertTrue(ane.isFallbackCase(accessNetworks)); // all OK
    }

    @Test
    public void testReportQualifiedNetwork_WithoutListAndCellularLimtedCaseSet() {
        when(configManager.isAccessNetworkAllowed(anyInt(), anyInt())).thenReturn(false, true);
        when(configManager.allowImsOverIwlanCellularLimitedCase()).thenReturn(true);
        List<Integer> accessNetworks2 = new ArrayList<>();
        accessNetworks2.add(AccessNetworkConstants.AccessNetworkType.IWLAN);
        ane.updateLastNotifiedQualifiedNetwork(accessNetworks2);
        ane.reportQualifiedNetwork(List.of(AccessNetworkConstants.AccessNetworkType.EUTRAN));
        assertEquals(
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN, ane.getLastQualifiedTransportType());
        ane.reportQualifiedNetwork(List.of(AccessNetworkConstants.AccessNetworkType.EUTRAN));
        assertEquals(
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN, ane.getLastQualifiedTransportType());
    }

    @Test
    public void testReportQualifiedNetwork_WithListAndCellularLimtedCaseSet() {
        when(configManager.isAccessNetworkAllowed(anyInt(), anyInt())).thenReturn(false, true);
        when(configManager.allowImsOverIwlanCellularLimitedCase()).thenReturn(true);
        List<Integer> accessNetworks1 = new ArrayList<>();
        List<Integer> accessNetworks2 = new ArrayList<>();
        accessNetworks1.add(AccessNetworkConstants.AccessNetworkType.EUTRAN);
        accessNetworks2.add(AccessNetworkConstants.AccessNetworkType.IWLAN);
        ane.updateLastNotifiedQualifiedNetwork(accessNetworks2);
        ane.reportQualifiedNetwork(accessNetworks1);
        assertEquals(
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN, ane.getLastQualifiedTransportType());
        accessNetworks1.clear();
        accessNetworks1.add(AccessNetworkConstants.AccessNetworkType.EUTRAN);
        ane.reportQualifiedNetwork(accessNetworks1);
        assertEquals(
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN, ane.getLastQualifiedTransportType());
    }

    @Test
    public void testReportQualifiedNetwork_WithListAndCellularLimtedCaseSet_NonIms() {
        ane =
                new AccessNetworkEvaluator(
                        mSlotIndex,
                        ApnSetting.TYPE_MMS,
                        sMockContext,
                        restrictManager,
                        configManager,
                        wifiQualityMonitor,
                        cellularQualityMonitor,
                        cellularNetworkStatusTracker,
                        iwlanNetworkStatusTracker,
                        dataConnectionStatusTracker,
                        qnsEventDispatcher,
                        altEventListener,
                        mQnsProvisioningListener,
                        qnsImsStatusListener);

        when(configManager.isAccessNetworkAllowed(anyInt(), anyInt())).thenReturn(false);
        when(configManager.allowImsOverIwlanCellularLimitedCase()).thenReturn(true);
        List<Integer> accessNetworks1 = new ArrayList<>();
        List<Integer> accessNetworks2 = new ArrayList<>();
        accessNetworks1.add(AccessNetworkConstants.AccessNetworkType.EUTRAN);
        accessNetworks2.add(AccessNetworkConstants.AccessNetworkType.IWLAN);
        ane.updateLastNotifiedQualifiedNetwork(accessNetworks2);
        ane.reportQualifiedNetwork(accessNetworks1);
        assertEquals(
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN, ane.getLastQualifiedTransportType());
    }

    @Test
    public void testReportQualifiedNetwork_WithoutList() {
        when(configManager.isAccessNetworkAllowed(anyInt(), anyInt())).thenReturn(false, true);
        when(configManager.allowImsOverIwlanCellularLimitedCase()).thenReturn(false);
        List<Integer> accessNetworks2 = new ArrayList<>();
        accessNetworks2.add(AccessNetworkConstants.AccessNetworkType.IWLAN);
        ane.updateLastNotifiedQualifiedNetwork(accessNetworks2);
        ane.reportQualifiedNetwork(List.of(AccessNetworkConstants.AccessNetworkType.EUTRAN));
        assertEquals(
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN, ane.getLastQualifiedTransportType());
    }

    @Test
    public void testReportQualifiedNetwork_WithList() {
        when(configManager.isAccessNetworkAllowed(anyInt(), anyInt())).thenReturn(false, true);
        when(configManager.allowImsOverIwlanCellularLimitedCase()).thenReturn(false);
        List<Integer> accessNetworks1 = new ArrayList<>();
        List<Integer> accessNetworks2 = new ArrayList<>();
        accessNetworks1.add(AccessNetworkConstants.AccessNetworkType.EUTRAN);
        accessNetworks2.add(AccessNetworkConstants.AccessNetworkType.IWLAN);
        ane.updateLastNotifiedQualifiedNetwork(accessNetworks2);
        ane.reportQualifiedNetwork(accessNetworks1);
        assertEquals(
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN, ane.getLastQualifiedTransportType());
    }

    @Test
    public void testCellularUnavailableWhenVolteRoamingNotSupported() {
        when(configManager.isMmtelCapabilityRequired(anyInt())).thenReturn(true);
        when(configManager.isServiceBarringCheckSupported()).thenReturn(false);
        when(configManager.isInCallHoDecisionWlanToWwanWithoutVopsCondition()).thenReturn(false);
        when(configManager.isHandoverAllowedByPolicy(anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(true);
        when(configManager.isVolteRoamingSupported(anyInt())).thenReturn(false);
        when(dataConnectionStatusTracker.getLastTransportType())
                .thenReturn(AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        QnsTelephonyListener.QnsTelephonyInfo info = qnsTelephonyListener.new QnsTelephonyInfo();
        info.setCellularAvailable(true);
        info.setCoverage(true);
        info.setDataNetworkType(TelephonyManager.NETWORK_TYPE_LTE);
        info.setVoiceNetworkType(TelephonyManager.NETWORK_TYPE_LTE);
        info.setDataRegState(ServiceState.STATE_IN_SERVICE);
        QnsTelephonyListener.QnsTelephonyInfoIms infoIms =
                qnsTelephonyListener.new QnsTelephonyInfoIms(info, true, true, false, false);
        ane.onQnsTelephonyInfoChanged(infoIms);
        assertFalse(ane.mCellularAvailable);
    }

    @Test
    public void testUpdateThrottleStatus() {
        ane.updateThrottleStatus(false, 30000, AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        verify(restrictManager)
                .notifyThrottling(false, 30000, AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
    }

    @Test
    public void testGetCoverage() {
        QnsTelephonyListener.QnsTelephonyInfo info = qnsTelephonyListener.new QnsTelephonyInfo();
        QnsTelephonyListener.QnsTelephonyInfoIms infoIms =
                qnsTelephonyListener.new QnsTelephonyInfoIms(info, true, true, false, false);
        infoIms.setCoverage(true);
        infoIms.setRoamingType(ServiceState.ROAMING_TYPE_INTERNATIONAL);
        infoIms.setRegisteredPlmn(TEST_PLMN);
        when(configManager.needToCheckInternationalRoaming(ApnSetting.TYPE_IMS)).thenReturn(true);
        when(configManager.isDefinedDomesticRoamingPlmn(TEST_PLMN)).thenReturn(false);
        assertEquals(QnsConstants.COVERAGE_ROAM, ane.getCoverage(infoIms, mApnType));
        when(configManager.isDefinedDomesticRoamingPlmn(TEST_PLMN)).thenReturn(true);
        assertEquals(QnsConstants.COVERAGE_HOME, ane.getCoverage(infoIms, mApnType));
        infoIms.setCoverage(false);
        when(configManager.isDefinedDomesticRoamingPlmn(TEST_PLMN)).thenReturn(false);
        assertEquals(QnsConstants.COVERAGE_HOME, ane.getCoverage(infoIms, mApnType));
        when(configManager.needToCheckInternationalRoaming(ApnSetting.TYPE_IMS)).thenReturn(false);
        assertEquals(QnsConstants.COVERAGE_HOME, ane.getCoverage(infoIms, mApnType));
        infoIms.setCoverage(true);
        infoIms.setRoamingType(ServiceState.ROAMING_TYPE_DOMESTIC);
        when(configManager.needToCheckInternationalRoaming(ApnSetting.TYPE_IMS)).thenReturn(true);
        assertEquals(QnsConstants.COVERAGE_HOME, ane.getCoverage(infoIms, mApnType));
        when(configManager.isDefinedInternationalRoamingPlmn(TEST_PLMN)).thenReturn(true);
        assertEquals(QnsConstants.COVERAGE_ROAM, ane.getCoverage(infoIms, mApnType));
    }

    @Test
    public void testUseDifferentApnOverIwlan() {
        ane =
                new AccessNetworkEvaluator(
                        mSlotIndex,
                        ApnSetting.TYPE_MMS,
                        sMockContext,
                        restrictManager,
                        configManager,
                        wifiQualityMonitor,
                        cellularQualityMonitor,
                        cellularNetworkStatusTracker,
                        iwlanNetworkStatusTracker,
                        dataConnectionStatusTracker,
                        qnsEventDispatcher,
                        altEventListener,
                        mQnsProvisioningListener,
                        qnsImsStatusListener);

        ApnSetting apnSettingForCellular =
                new ApnSetting.Builder()
                        .setApnName("internetmms")
                        .setEntryName("internetmms")
                        .setApnTypeBitmask(
                                ApnSetting.TYPE_DEFAULT
                                        | ApnSetting.TYPE_MMS
                                        | ApnSetting.TYPE_XCAP
                                        | ApnSetting.TYPE_CBS)
                        .setNetworkTypeBitmask(
                                (int) TelephonyManager.NETWORK_STANDARDS_FAMILY_BITMASK_3GPP)
                        .setCarrierEnabled(true)
                        .build();
        when(configManager.isHandoverAllowedByPolicy(
                        ApnSetting.TYPE_MMS,
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        QnsConstants.COVERAGE_HOME))
                .thenReturn(false);
        when(dataConnectionStatusTracker.getLastTransportType())
                .thenReturn(AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
        when(dataConnectionStatusTracker.getLastApnSetting(anyInt()))
                .thenReturn(apnSettingForCellular);

        assertTrue(ane.moveTransportTypeAllowed());
    }

    @Test
    public void testRatPreferenceWifiWhenNoCellularHandoverDisallowedButMoveToCellular() {
        ane =
                new AccessNetworkEvaluator(
                        mSlotIndex,
                        ApnSetting.TYPE_XCAP,
                        sMockContext,
                        restrictManager,
                        configManager,
                        wifiQualityMonitor,
                        cellularQualityMonitor,
                        cellularNetworkStatusTracker,
                        iwlanNetworkStatusTracker,
                        dataConnectionStatusTracker,
                        qnsEventDispatcher,
                        altEventListener,
                        mQnsProvisioningListener,
                        qnsImsStatusListener);
        QnsTelephonyListener.QnsTelephonyInfo info = qnsTelephonyListener.new QnsTelephonyInfo();
        info.setCellularAvailable(true);
        info.setCoverage(false);
        info.setDataNetworkType(TelephonyManager.NETWORK_TYPE_LTE);
        info.setVoiceNetworkType(TelephonyManager.NETWORK_TYPE_LTE);
        info.setDataRegState(ServiceState.STATE_IN_SERVICE);

        when(configManager.isHandoverAllowedByPolicy(
                        ApnSetting.TYPE_XCAP,
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        QnsConstants.COVERAGE_HOME))
                .thenReturn(false);
        when(configManager.getRatPreference(ApnSetting.TYPE_XCAP))
                .thenReturn(QnsConstants.RAT_PREFERENCE_WIFI_WHEN_NO_CELLULAR);
        when(dataConnectionStatusTracker.getLastTransportType())
                .thenReturn(AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
        ane.onQnsTelephonyInfoChanged(info);

        assertTrue(ane.moveTransportTypeAllowed());

        info.setCellularAvailable(false);
        ane.onQnsTelephonyInfoChanged(info);

        assertFalse(ane.moveTransportTypeAllowed());
    }

    @Test
    public void testOnWfcEnabledChanged_Home() throws InterruptedException {
        mMockitoSession =
                mockitoSession()
                        .strictness(Strictness.LENIENT)
                        .mockStatic(QnsUtils.class)
                        .startMocking();
        stubQnsStatics(QnsConstants.WIFI_PREF, QnsConstants.WIFI_PREF, true, false, false);
        ane.rebuild();
        ane.onIwlanNetworkStatusChanged(
                iwlanNetworkStatusTracker.new IwlanAvailabilityInfo(true, false));
        ane.registerForQualifiedNetworksChanged(mHandler, QUALIFIED_NETWORKS_CHANGED);
        waitFor(200);

        // Enabled
        latch = new CountDownLatch(1);
        mEvaluatorHandler.sendEmptyMessage(QnsEventDispatcher.QNS_EVENT_WFC_ENABLED);
        assertTrue(latch.await(200, TimeUnit.MILLISECONDS));
        assertTrue(
                mQualifiedNetworksInfo
                        .getAccessNetworkTypes()
                        .contains(AccessNetworkConstants.AccessNetworkType.IWLAN));

        // Disabled
        latch = new CountDownLatch(1);
        mEvaluatorHandler.sendEmptyMessage(QnsEventDispatcher.QNS_EVENT_WFC_DISABLED);
        assertTrue(latch.await(200, TimeUnit.MILLISECONDS));
        assertFalse(
                mQualifiedNetworksInfo
                        .getAccessNetworkTypes()
                        .contains(AccessNetworkConstants.AccessNetworkType.IWLAN));
    }

    @Test
    public void testOnWfcEnabledChanged_Roaming() throws InterruptedException {
        mMockitoSession =
                mockitoSession()
                        .strictness(Strictness.LENIENT)
                        .mockStatic(QnsUtils.class)
                        .startMocking();
        stubQnsStatics(QnsConstants.CELL_PREF, QnsConstants.WIFI_PREF, true, true, false);
        QnsTelephonyListener.QnsTelephonyInfo info = qnsTelephonyListener.new QnsTelephonyInfo();
        QnsTelephonyListener.QnsTelephonyInfoIms infoIms =
                qnsTelephonyListener.new QnsTelephonyInfoIms(info, true, true, false, false);
        infoIms.setCoverage(true);
        infoIms.setRoamingType(ServiceState.ROAMING_TYPE_INTERNATIONAL);
        infoIms.setRegisteredPlmn(TEST_PLMN);
        ane.onQnsTelephonyInfoChanged(infoIms);

        ane.rebuild();
        ane.onIwlanNetworkStatusChanged(
                iwlanNetworkStatusTracker.new IwlanAvailabilityInfo(true, false));
        ane.registerForQualifiedNetworksChanged(mHandler, QUALIFIED_NETWORKS_CHANGED);
        waitFor(200);

        // Enabled
        latch = new CountDownLatch(1);
        mEvaluatorHandler.sendEmptyMessage(QnsEventDispatcher.QNS_EVENT_WFC_ROAMING_ENABLED);
        assertTrue(latch.await(200, TimeUnit.MILLISECONDS));
        assertTrue(
                mQualifiedNetworksInfo
                        .getAccessNetworkTypes()
                        .contains(AccessNetworkConstants.AccessNetworkType.IWLAN));

        // Disabled
        latch = new CountDownLatch(1);
        mEvaluatorHandler.sendEmptyMessage(QnsEventDispatcher.QNS_EVENT_WFC_ROAMING_DISABLED);
        assertTrue(latch.await(200, TimeUnit.MILLISECONDS));
        assertFalse(
                mQualifiedNetworksInfo
                        .getAccessNetworkTypes()
                        .contains(AccessNetworkConstants.AccessNetworkType.IWLAN));
    }

    @Test
    public void testOnWfcPlatformChanged() throws InterruptedException {
        mMockitoSession =
                mockitoSession()
                        .strictness(Strictness.LENIENT)
                        .mockStatic(QnsUtils.class)
                        .startMocking();
        stubQnsStatics(QnsConstants.CELL_PREF, QnsConstants.WIFI_PREF, false, true, false);
        ane.rebuild();
        ane.onIwlanNetworkStatusChanged(
                iwlanNetworkStatusTracker.new IwlanAvailabilityInfo(true, false));
        ane.registerForQualifiedNetworksChanged(mHandler, QUALIFIED_NETWORKS_CHANGED);
        waitFor(200);

        // Enabled
        latch = new CountDownLatch(1);
        mEvaluatorHandler.sendEmptyMessage(QnsEventDispatcher.QNS_EVENT_WFC_PLATFORM_ENABLED);
        assertTrue(latch.await(200, TimeUnit.MILLISECONDS));
        assertTrue(
                mQualifiedNetworksInfo
                        .getAccessNetworkTypes()
                        .contains(AccessNetworkConstants.AccessNetworkType.IWLAN));

        // No report if no change in settings
        latch = new CountDownLatch(1);
        mEvaluatorHandler.sendEmptyMessage(QnsEventDispatcher.QNS_EVENT_WFC_PLATFORM_ENABLED);
        assertFalse(latch.await(200, TimeUnit.MILLISECONDS));

        // Disabled
        latch = new CountDownLatch(1);
        mEvaluatorHandler.sendEmptyMessage(QnsEventDispatcher.QNS_EVENT_WFC_PLATFORM_DISABLED);
        assertTrue(latch.await(100, TimeUnit.MILLISECONDS));
        assertTrue(mQualifiedNetworksInfo.getAccessNetworkTypes().isEmpty());
    }

    @Test
    public void testOnWfcModeChanged_Home() throws Exception {
        mMockitoSession =
                mockitoSession()
                        .strictness(Strictness.LENIENT)
                        .mockStatic(QnsUtils.class)
                        .startMocking();
        stubQnsStatics(QnsConstants.CELL_PREF, QnsConstants.WIFI_PREF, true, true, false);
        ane.rebuild();
        waitFor(300);
        generateAnspPolicyMap();
        mockCurrentQuality(-60, -90);
        ane.onIwlanNetworkStatusChanged(
                iwlanNetworkStatusTracker.new IwlanAvailabilityInfo(true, false));

        when(configManager.isVolteRoamingSupported(anyInt())).thenReturn(true);
        QnsTelephonyListener.QnsTelephonyInfo info = qnsTelephonyListener.new QnsTelephonyInfo();
        info.setCellularAvailable(true);
        info.setCoverage(false);
        info.setDataNetworkType(TelephonyManager.NETWORK_TYPE_LTE);
        info.setVoiceNetworkType(TelephonyManager.NETWORK_TYPE_LTE);
        info.setDataRegState(ServiceState.STATE_IN_SERVICE);
        QnsTelephonyListener.QnsTelephonyInfoIms infoIms =
                qnsTelephonyListener.new QnsTelephonyInfoIms(info, true, true, false, false);
        ane.onQnsTelephonyInfoChanged(infoIms);
        ane.registerForQualifiedNetworksChanged(mHandler, QUALIFIED_NETWORKS_CHANGED);
        waitFor(200);

        // Cellular Preferred
        latch = new CountDownLatch(1);
        mEvaluatorHandler.sendEmptyMessage(QnsEventDispatcher.QNS_EVENT_WFC_MODE_TO_WIFI_PREFERRED);
        assertTrue(latch.await(200, TimeUnit.MILLISECONDS));
        assertTrue(
                mQualifiedNetworksInfo
                        .getAccessNetworkTypes()
                        .contains(AccessNetworkConstants.AccessNetworkType.IWLAN));
    }

    @Test
    public void testOnEmergencyPreferredTransportTypeChanged() throws InterruptedException {
        latch = new CountDownLatch(1);
        ane.registerForQualifiedNetworksChanged(mHandler, QUALIFIED_NETWORKS_CHANGED);
        Message.obtain(
                        mEvaluatorHandler,
                        EVENT_EMERGENCY_PREFERRED_TRANSPORT_TYPE_CHANGED,
                        new AsyncResult(null, AccessNetworkConstants.TRANSPORT_TYPE_WLAN, null))
                .sendToTarget();
        assertFalse(latch.await(100, TimeUnit.MILLISECONDS)); // no report since ANE is for IMS apn.
        when(dataConnectionStatusTracker.isInactiveState()).thenReturn(true);
        ane =
                new AccessNetworkEvaluator(
                        mSlotIndex,
                        ApnSetting.TYPE_EMERGENCY,
                        sMockContext,
                        restrictManager,
                        configManager,
                        wifiQualityMonitor,
                        cellularQualityMonitor,
                        cellularNetworkStatusTracker,
                        iwlanNetworkStatusTracker,
                        dataConnectionStatusTracker,
                        qnsEventDispatcher,
                        altEventListener,
                        mQnsProvisioningListener,
                        qnsImsStatusListener);

        waitFor(100);
        ArgumentCaptor<Handler> capture = ArgumentCaptor.forClass(Handler.class);
        verify(iwlanNetworkStatusTracker, atLeastOnce())
                .registerIwlanNetworksChanged(anyInt(), capture.capture(), anyInt());
        mEvaluatorHandler = capture.getValue();

        // WLAN
        latch = new CountDownLatch(1);
        ane.registerForQualifiedNetworksChanged(mHandler, QUALIFIED_NETWORKS_CHANGED);
        Message.obtain(
                        mEvaluatorHandler,
                        EVENT_EMERGENCY_PREFERRED_TRANSPORT_TYPE_CHANGED,
                        new AsyncResult(null, AccessNetworkConstants.TRANSPORT_TYPE_WLAN, null))
                .sendToTarget();
        assertTrue(latch.await(100, TimeUnit.MILLISECONDS));
        assertTrue(
                mQualifiedNetworksInfo
                        .getAccessNetworkTypes()
                        .contains(AccessNetworkConstants.AccessNetworkType.IWLAN));

        // UNKNOWN
        latch = new CountDownLatch(1);
        Message.obtain(
                        mEvaluatorHandler,
                        EVENT_EMERGENCY_PREFERRED_TRANSPORT_TYPE_CHANGED,
                        new AsyncResult(null, AccessNetworkConstants.TRANSPORT_TYPE_WWAN, null))
                .sendToTarget();
        assertTrue(latch.await(100, TimeUnit.MILLISECONDS));
        assertTrue(mQualifiedNetworksInfo.getAccessNetworkTypes().isEmpty());

        // EUTRAN
        QnsTelephonyListener.QnsTelephonyInfo info = qnsTelephonyListener.new QnsTelephonyInfo();
        info.setCellularAvailable(true);
        info.setCoverage(false);
        info.setDataNetworkType(TelephonyManager.NETWORK_TYPE_LTE);
        info.setVoiceNetworkType(TelephonyManager.NETWORK_TYPE_LTE);
        info.setDataRegState(ServiceState.STATE_IN_SERVICE);
        QnsTelephonyListener.QnsTelephonyInfoIms infoIms =
                qnsTelephonyListener.new QnsTelephonyInfoIms(info, true, true, false, false);
        ane.onQnsTelephonyInfoChanged(infoIms);
        waitFor(100);

        latch = new CountDownLatch(1);
        Message.obtain(
                        mEvaluatorHandler,
                        EVENT_EMERGENCY_PREFERRED_TRANSPORT_TYPE_CHANGED,
                        new AsyncResult(null, AccessNetworkConstants.TRANSPORT_TYPE_WWAN, null))
                .sendToTarget();
        assertTrue(latch.await(100, TimeUnit.MILLISECONDS));
        assertTrue(
                mQualifiedNetworksInfo
                        .getAccessNetworkTypes()
                        .contains(AccessNetworkConstants.AccessNetworkType.EUTRAN));
    }

    @Test
    public void testReportSatisfiedAccessNetworkTypesByState()
            throws InterruptedException, NoSuchFieldException, IllegalAccessException {
        doNothing().when(restrictManager).setQnsCallType(anyInt());
        Message.obtain(
                        mEvaluatorHandler,
                        EVENT_SET_CALL_TYPE,
                        new AsyncResult(null, QnsConstants.CALL_TYPE_IDLE, null))
                .sendToTarget();
        Message.obtain(
                        mEvaluatorHandler,
                        EVENT_IWLAN_NETWORK_STATUS_CHANGED,
                        new AsyncResult(
                                null,
                                iwlanNetworkStatusTracker.new IwlanAvailabilityInfo(true, false),
                                null))
                .sendToTarget();

        generateAnspPolicyMap();
        mockCurrentQuality(-70, -90);
        mEvaluatorHandler.sendEmptyMessage(QnsEventDispatcher.QNS_EVENT_WFC_MODE_TO_WIFI_PREFERRED);
        when(dataConnectionStatusTracker.isInactiveState()).thenReturn(true);
        when(restrictManager.isRestricted(anyInt())).thenReturn(false);
        latch = new CountDownLatch(1);
        List<Integer> accessNetworks = new ArrayList<>();
        accessNetworks.add(AccessNetworkConstants.AccessNetworkType.EUTRAN);
        ane.registerForQualifiedNetworksChanged(mHandler, QUALIFIED_NETWORKS_CHANGED);
        waitFor(200);
        ane.reportSatisfiedAccessNetworkTypesByState(accessNetworks, true);
        assertTrue(latch.await(100, TimeUnit.MILLISECONDS));
        assertTrue(
                mQualifiedNetworksInfo
                        .getAccessNetworkTypes()
                        .contains(AccessNetworkConstants.AccessNetworkType.EUTRAN));
        verify(wifiQualityMonitor).updateThresholdsForApn(anyInt(), anyInt(), isNotNull());
    }

    private void generateAnspPolicyMap() throws NoSuchFieldException, IllegalAccessException {
        mTestAnspPolicyMap = new HashMap<>();

        PreCondition p1 =
                new PreCondition(
                        QnsConstants.CALL_TYPE_IDLE,
                        QnsConstants.WIFI_PREF,
                        QnsConstants.COVERAGE_HOME);
        PreCondition p2 =
                new PreCondition(
                        QnsConstants.CALL_TYPE_IDLE,
                        QnsConstants.CELL_PREF,
                        QnsConstants.COVERAGE_HOME);

        List<ThresholdGroup> tg1 =
                generateTestThresholdGroups(
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSI,
                        QnsConstants.THRESHOLD_EQUAL_OR_LARGER,
                        -65);
        List<ThresholdGroup> tg2 =
                generateTestThresholdGroups(
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRP,
                        QnsConstants.THRESHOLD_EQUAL_OR_LARGER,
                        -91);

        AccessNetworkSelectionPolicy ansp1 =
                new AccessNetworkSelectionPolicy(
                        ApnSetting.TYPE_IMS, AccessNetworkConstants.TRANSPORT_TYPE_WLAN, p1, tg1);
        List<AccessNetworkSelectionPolicy> anspList = new ArrayList<>();
        anspList.add(ansp1);

        AccessNetworkSelectionPolicy ansp2 =
                new AccessNetworkSelectionPolicy(
                        ApnSetting.TYPE_IMS, AccessNetworkConstants.TRANSPORT_TYPE_WWAN, p2, tg2);
        List<AccessNetworkSelectionPolicy> anspList2 = new ArrayList<>();
        anspList2.add(ansp2);

        mTestAnspPolicyMap.put(p1, anspList);
        mTestAnspPolicyMap.put(p2, anspList2);
        Field f = AccessNetworkEvaluator.class.getDeclaredField("mAnspPolicyMap");
        f.setAccessible(true);
        f.set(ane, mTestAnspPolicyMap);
    }

    private void mockCurrentQuality(int wifi, int cellular) {
        when(wifiQualityMonitor.getCurrentQuality(anyInt(), anyInt()))
                .thenAnswer(
                        (Answer<Integer>)
                    invocation -> {
                        int quality = -255;
                        switch ((int) invocation.getArgument(0)) {
                            case AccessNetworkConstants.AccessNetworkType.EUTRAN:
                                quality = cellular;
                                break;
                            case AccessNetworkConstants.AccessNetworkType.IWLAN:
                                quality = wifi;
                                break;
                        }
                        return quality;
                    });
    }

    private List<ThresholdGroup> generateTestThresholdGroups(
            int accessNetwork, int measType, int matchType, int threshold) {
        List<ThresholdGroup> thgroups = new ArrayList<>();
        List<Threshold> ths = new ArrayList<>();
        ths.add(
                new Threshold(
                        accessNetwork,
                        measType,
                        threshold,
                        matchType,
                        QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER));
        thgroups.add(new ThresholdGroup(ths));

        return thgroups;
    }

    @Test
    public void testOnProvisioningInfoChanged_LTE() throws Exception {
        QnsProvisioningInfo lastInfo = getQnsProvisioningInfo();
        setObject(AccessNetworkEvaluator.class, "mLastProvisioningInfo", ane, lastInfo);

        QnsProvisioningInfo info = getQnsProvisioningInfo();
        ConcurrentHashMap<Integer, Integer> integerItems = getProvisioningItems();

        integerItems.put(ProvisioningManager.KEY_LTE_THRESHOLD_1, -100); // bad
        integerItems.put(ProvisioningManager.KEY_LTE_THRESHOLD_2, -120); // worst
        integerItems.put(ProvisioningManager.KEY_LTE_THRESHOLD_3, -85); // good

        // update new provisioning info
        setObject(QnsProvisioningInfo.class, "mIntegerItems", info, integerItems);

        Message.obtain(
                        mEvaluatorHandler,
                        EVENT_PROVISIONING_INFO_CHANGED,
                        new AsyncResult(null, info, null))
                .sendToTarget();
        waitFor(100);
        verify(configManager, times(3)).setQnsProvisioningInfo(info);
    }

    @Test
    public void testOnProvisioningInfoChanged_Wifi() throws Exception {
        QnsProvisioningInfo lastInfo = getQnsProvisioningInfo();
        setObject(AccessNetworkEvaluator.class, "mLastProvisioningInfo", ane, lastInfo);

        QnsProvisioningInfo info = getQnsProvisioningInfo();
        ConcurrentHashMap<Integer, Integer> integerItems = getProvisioningItems();

        integerItems.put(ProvisioningManager.KEY_WIFI_THRESHOLD_A, -75); // good
        integerItems.put(ProvisioningManager.KEY_WIFI_THRESHOLD_B, -90); // bad

        // update new provisioning info
        setObject(QnsProvisioningInfo.class, "mIntegerItems", info, integerItems);
        Message.obtain(
                        mEvaluatorHandler,
                        EVENT_PROVISIONING_INFO_CHANGED,
                        new AsyncResult(null, info, null))
                .sendToTarget();
        waitFor(100);
        verify(configManager, times(2)).setQnsProvisioningInfo(info);
    }

    @Test
    public void testOnProvisioningInfoChanged_ePDG() throws Exception {
        QnsProvisioningInfo lastInfo = getQnsProvisioningInfo();
        setObject(AccessNetworkEvaluator.class, "mLastProvisioningInfo", ane, lastInfo);

        QnsProvisioningInfo info = getQnsProvisioningInfo();
        ConcurrentHashMap<Integer, Integer> integerItems = getProvisioningItems();
        integerItems.put(ProvisioningManager.KEY_LTE_EPDG_TIMER_SEC, 10000);
        integerItems.put(ProvisioningManager.KEY_WIFI_EPDG_TIMER_SEC, 20000);

        // update new provisioning info
        setObject(QnsProvisioningInfo.class, "mIntegerItems", info, integerItems);
        Message.obtain(
                        mEvaluatorHandler,
                        EVENT_PROVISIONING_INFO_CHANGED,
                        new AsyncResult(null, info, null))
                .sendToTarget();
        waitFor(100);
        verify(configManager, times(2)).setQnsProvisioningInfo(info);
    }

    private QnsProvisioningInfo getQnsProvisioningInfo() throws Exception {
        QnsProvisioningInfo info = new QnsProvisioningInfo();
        ConcurrentHashMap<Integer, Integer> integerItems = getProvisioningItems();
        setObject(QnsProvisioningInfo.class, "mIntegerItems", info, integerItems);
        return info;
    }

    private ConcurrentHashMap<Integer, Integer> getProvisioningItems() {
        ConcurrentHashMap<Integer, Integer> integerItems = new ConcurrentHashMap<>();
        integerItems.put(ProvisioningManager.KEY_LTE_THRESHOLD_1, -95); // bad
        integerItems.put(ProvisioningManager.KEY_LTE_THRESHOLD_2, -110); // worst
        integerItems.put(ProvisioningManager.KEY_LTE_THRESHOLD_3, -80); // good
        integerItems.put(ProvisioningManager.KEY_WIFI_THRESHOLD_A, -70); // good
        integerItems.put(ProvisioningManager.KEY_WIFI_THRESHOLD_B, -85); // bad
        integerItems.put(ProvisioningManager.KEY_LTE_EPDG_TIMER_SEC, -80);
        integerItems.put(ProvisioningManager.KEY_WIFI_EPDG_TIMER_SEC, -80);
        return integerItems;
    }

    @Test
    public void testIsAllowed_WLAN() throws Exception {
        int transport = AccessNetworkConstants.TRANSPORT_TYPE_WLAN;
        ane.onTryWfcConnectionStateChanged(true);
        when(qnsEventDispatcher.isAirplaneModeToggleOn()).thenReturn(true);
        when(configManager.allowWFCOnAirplaneModeOn()).thenReturn(false, true);
        Method method = AccessNetworkEvaluator.class.getDeclaredMethod("isAllowed", int.class);
        method.setAccessible(true);
        assertFalse((Boolean) method.invoke(ane, transport));

        when(iwlanNetworkStatusTracker.isInternationalRoaming(sMockContext, mSlotIndex))
                .thenReturn(true);
        when(configManager.blockIwlanInInternationalRoamWithoutWwan()).thenReturn(true, false);
        assertFalse((Boolean) method.invoke(ane, transport));

        when(configManager.getRatPreference(mApnType)).thenAnswer(pref -> mRatPreference);

        // RAT_PREFERENCE_DEFAULT
        mRatPreference = QnsConstants.RAT_PREFERENCE_DEFAULT;
        assertTrue((Boolean) method.invoke(ane, transport));

        // RAT_PREFERENCE_WIFI_ONLY
        mRatPreference = QnsConstants.RAT_PREFERENCE_WIFI_ONLY;
        assertTrue((Boolean) method.invoke(ane, transport));

        mRatPreference = QnsConstants.RAT_PREFERENCE_WIFI_WHEN_WFC_AVAILABLE;
        when(qnsImsStatusListener.isImsRegistered(transport)).thenReturn(false, true);

        // RAT_PREFERENCE_WIFI_WHEN_WFC_AVAILABLE - ims not registered over WLAN
        assertFalse((Boolean) method.invoke(ane, transport));

        // RAT_PREFERENCE_WIFI_WHEN_WFC_AVAILABLE - ims registered over WLAN
        assertTrue((Boolean) method.invoke(ane, transport));

        // RAT_PREFERENCE_WIFI_WHEN_NO_CELLULAR - cellular available
        mRatPreference = QnsConstants.RAT_PREFERENCE_WIFI_WHEN_NO_CELLULAR;
        setObject(AccessNetworkEvaluator.class, "mCellularAvailable", ane, true);
        assertFalse((Boolean) method.invoke(ane, transport));

        // RAT_PREFERENCE_WIFI_WHEN_NO_CELLULAR - cellular not available
        mRatPreference = QnsConstants.RAT_PREFERENCE_WIFI_WHEN_NO_CELLULAR;
        setObject(AccessNetworkEvaluator.class, "mCellularAvailable", ane, false);
        assertTrue((Boolean) method.invoke(ane, transport));
    }

    @Test
    public void testIsAllowed_WWAN() throws Exception {
        int transport = AccessNetworkConstants.TRANSPORT_TYPE_WWAN;
        when(qnsEventDispatcher.isAirplaneModeToggleOn()).thenReturn(true, false);
        Method method = AccessNetworkEvaluator.class.getDeclaredMethod("isAllowed", int.class);
        method.setAccessible(true);

        assertFalse((Boolean) method.invoke(ane, transport));

        when(configManager.getRatPreference(mApnType)).thenAnswer(pref -> mRatPreference);

        // RAT_PREFERENCE_DEFAULT
        mRatPreference = QnsConstants.RAT_PREFERENCE_DEFAULT;
        assertTrue((Boolean) method.invoke(ane, transport));

        // RAT_PREFERENCE_WIFI_ONLY
        mRatPreference = QnsConstants.RAT_PREFERENCE_WIFI_ONLY;
        assertFalse((Boolean) method.invoke(ane, transport));

        // RAT_PREFERENCE_WIFI_WHEN_WFC_AVAILABLE
        mRatPreference = QnsConstants.RAT_PREFERENCE_WIFI_WHEN_WFC_AVAILABLE;
        when(qnsImsStatusListener.isImsRegistered(AccessNetworkConstants.TRANSPORT_TYPE_WLAN))
                .thenReturn(true, false);
        assertFalse((Boolean) method.invoke(ane, transport));
        assertTrue((Boolean) method.invoke(ane, transport));

        // RAT_PREFERENCE_WIFI_WHEN_NO_CELLULAR
        mRatPreference = QnsConstants.RAT_PREFERENCE_WIFI_WHEN_NO_CELLULAR;
        assertTrue((Boolean) method.invoke(ane, transport));

        mRatPreference = QnsConstants.RAT_PREFERENCE_WIFI_WHEN_HOME_IS_NOT_AVAILABLE;

        // RAT_PREFERENCE_WIFI_WHEN_HOME_IS_NOT_AVAILABLE - no cellular
        setObject(AccessNetworkEvaluator.class, "mCellularAvailable", ane, false);
        assertFalse((Boolean) method.invoke(ane, transport));

        // RAT_PREFERENCE_WIFI_WHEN_HOME_IS_NOT_AVAILABLE - home
        setObject(AccessNetworkEvaluator.class, "mCellularAvailable", ane, true);
        setObject(AccessNetworkEvaluator.class, "mCoverage", ane, QnsConstants.COVERAGE_HOME);
        assertTrue((Boolean) method.invoke(ane, transport));

        // RAT_PREFERENCE_WIFI_WHEN_HOME_IS_NOT_AVAILABLE - roaming
        setObject(AccessNetworkEvaluator.class, "mCoverage", ane, QnsConstants.COVERAGE_ROAM);
        assertFalse((Boolean) method.invoke(ane, transport));
    }

    @Test
    public void testGetMatchingPreconditionForEmergency() {
        AccessNetworkEvaluator aneSos =
                new AccessNetworkEvaluator(
                        mSlotIndex,
                        ApnSetting.TYPE_EMERGENCY,
                        sMockContext,
                        restrictManager,
                        configManager,
                        wifiQualityMonitor,
                        cellularQualityMonitor,
                        cellularNetworkStatusTracker,
                        iwlanNetworkStatusTracker,
                        dataConnectionStatusTracker,
                        qnsEventDispatcher,
                        altEventListener,
                        mQnsProvisioningListener,
                        qnsImsStatusListener);
        AccessNetworkEvaluator aneIms =
                new AccessNetworkEvaluator(
                        mSlotIndex,
                        ApnSetting.TYPE_IMS,
                        sMockContext,
                        restrictManager,
                        configManager,
                        wifiQualityMonitor,
                        cellularQualityMonitor,
                        cellularNetworkStatusTracker,
                        iwlanNetworkStatusTracker,
                        dataConnectionStatusTracker,
                        qnsEventDispatcher,
                        altEventListener,
                        mQnsProvisioningListener,
                        qnsImsStatusListener);

        List<Integer> satisfiedAccessNetworkTypes = new ArrayList<>();
        satisfiedAccessNetworkTypes.add(AccessNetworkConstants.AccessNetworkType.EUTRAN);
        when(dataConnectionStatusTracker.isInactiveState()).thenReturn(true);
        try {
            Field field = AccessNetworkEvaluator.class.getDeclaredField("mCallType");
            field.setAccessible(true);
            field.set(aneSos, QnsConstants.CALL_TYPE_VOICE);
            field.set(aneIms, QnsConstants.CALL_TYPE_VOICE);
        } catch (Exception e) {
        }
        aneSos.reportSatisfiedAccessNetworkTypesByState(satisfiedAccessNetworkTypes, true);
        aneIms.reportSatisfiedAccessNetworkTypesByState(satisfiedAccessNetworkTypes, true);
        ArrayList<AccessNetworkSelectionPolicy> matchedAnspSos = null;
        ArrayList<AccessNetworkSelectionPolicy> matchedAnspIms = null;
        try {
            Field field =
                    AccessNetworkEvaluator.class.getDeclaredField(
                            "mAccessNetworkSelectionPolicies");
            field.setAccessible(true);
            matchedAnspSos = (ArrayList<AccessNetworkSelectionPolicy>) field.get(aneSos);
            matchedAnspIms = (ArrayList<AccessNetworkSelectionPolicy>) field.get(aneIms);
        } catch (Exception e) {
            assertTrue(matchedAnspSos != null);
            assertTrue(matchedAnspIms != null);
        }

        assertTrue(matchedAnspSos != null);
        assertTrue(matchedAnspIms != null);
        assertTrue(matchedAnspSos.size() > 0);
        assertTrue(matchedAnspIms.size() > 0);
        assertTrue(
                matchedAnspSos.get(0).getPreCondition().getCallType()
                        == QnsConstants.CALL_TYPE_VOICE);
        assertTrue(
                matchedAnspIms.get(0).getPreCondition().getCallType()
                        == QnsConstants.CALL_TYPE_VOICE);

        try {
            Field field = AccessNetworkEvaluator.class.getDeclaredField("mCallType");
            field.setAccessible(true);
            field.set(aneSos, QnsConstants.CALL_TYPE_EMERGENCY);
            field.set(aneIms, QnsConstants.CALL_TYPE_EMERGENCY);
        } catch (Exception e) {
        }
        aneSos.reportSatisfiedAccessNetworkTypesByState(satisfiedAccessNetworkTypes, true);
        aneIms.reportSatisfiedAccessNetworkTypesByState(satisfiedAccessNetworkTypes, true);
        try {
            Field field =
                    AccessNetworkEvaluator.class.getDeclaredField(
                            "mAccessNetworkSelectionPolicies");
            field.setAccessible(true);
            matchedAnspSos = (ArrayList<AccessNetworkSelectionPolicy>) field.get(aneSos);
            matchedAnspIms = (ArrayList<AccessNetworkSelectionPolicy>) field.get(aneIms);
        } catch (Exception e) {
            assertTrue(matchedAnspSos != null);
            assertTrue(matchedAnspIms != null);
        }

        assertTrue(matchedAnspSos != null);
        assertTrue(matchedAnspIms != null);
        assertTrue(matchedAnspSos.size() > 0);
        assertTrue(matchedAnspIms.size() == 0);
        assertTrue(
                matchedAnspSos.get(0).getPreCondition().getCallType()
                        == QnsConstants.CALL_TYPE_VOICE);
    }
}
