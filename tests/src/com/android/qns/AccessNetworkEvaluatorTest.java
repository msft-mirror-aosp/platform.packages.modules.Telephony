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

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNotNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
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

import com.android.qns.AccessNetworkSelectionPolicy.PreCondition;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.stubbing.Answer;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private boolean mWfcPlatformEnabled = false;
    private boolean mSettingWfcEnabled = false;
    private boolean mSettingWfcRoamingEnabled = false;
    private int mSettingWfcMode = QnsConstants.WIFI_PREF;
    private int mSettingWfcRoamingMode = QnsConstants.WIFI_PREF;

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
        mMockitoSession = mockitoSession().spyStatic(QnsUtils.class).startMocking();
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
        stubStatics();
        ArgumentCaptor<Handler> capture = ArgumentCaptor.forClass(Handler.class);
        verify(iwlanNetworkStatusTracker)
                .registerIwlanNetworksChanged(anyInt(), capture.capture(), anyInt());
        mEvaluatorHandler = capture.getValue();
    }

    private void stubStatics() {
        lenient()
                .when(QnsUtils.isWfcEnabledByPlatform(sMockContext, mSlotIndex))
                .thenAnswer((Answer<Boolean>) invocation -> mWfcPlatformEnabled);
        lenient()
                .when(QnsUtils.isWfcEnabled(sMockContext, mSlotIndex, false))
                .thenAnswer((Answer<Boolean>) invocation -> mSettingWfcEnabled);
        lenient()
                .when(QnsUtils.getWfcMode(sMockContext, mSlotIndex, false))
                .thenAnswer((Answer<Integer>) invocation -> mSettingWfcMode);
        lenient()
                .when(QnsUtils.isWfcEnabled(sMockContext, mSlotIndex, true))
                .thenAnswer((Answer<Boolean>) invocation -> mSettingWfcRoamingEnabled);
        lenient()
                .when(QnsUtils.getWfcMode(sMockContext, mSlotIndex, true))
                .thenAnswer((Answer<Integer>) invocation -> mSettingWfcRoamingMode);
    }

    @After
    public void tearDown() throws Exception {
        mMockitoSession.finishMocking();
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
        info.setDataTech(ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN);
        info.setVoiceTech(ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN);
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
        info.setDataTech(ServiceState.RIL_RADIO_TECHNOLOGY_LTE);
        info.setVoiceTech(ServiceState.RIL_RADIO_TECHNOLOGY_LTE);
        info.setDataRegState(ServiceState.STATE_IN_SERVICE);
        infoIms = qnsTelephonyListener.new QnsTelephonyInfoIms(info, true, true, true, true);
        ane.onQnsTelephonyInfoChanged(infoIms);
        ane.updateLastNotifiedQualifiedNetwork(accessNetworks);
        assertTrue(ane.needHandoverPolicyCheck());
        assertTrue(ane.moveTransportTypeAllowed());

        info = qnsTelephonyListener.new QnsTelephonyInfo();
        info.setCellularAvailable(true);
        info.setCoverage(false);
        info.setDataTech(ServiceState.RIL_RADIO_TECHNOLOGY_NR);
        info.setVoiceTech(ServiceState.RIL_RADIO_TECHNOLOGY_NR);
        info.setDataRegState(ServiceState.STATE_IN_SERVICE);
        infoIms = qnsTelephonyListener.new QnsTelephonyInfoIms(info, true, true, true, true);
        ane.onQnsTelephonyInfoChanged(infoIms);
        ane.updateLastNotifiedQualifiedNetwork(accessNetworks);
        assertTrue(ane.needHandoverPolicyCheck());
        assertTrue(ane.moveTransportTypeAllowed());
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
        info.setDataTech(ServiceState.RIL_RADIO_TECHNOLOGY_LTE);
        info.setVoiceTech(ServiceState.RIL_RADIO_TECHNOLOGY_LTE);
        info.setDataRegState(ServiceState.STATE_IN_SERVICE);
        QnsTelephonyListener.QnsTelephonyInfoIms infoIms =
                qnsTelephonyListener.new QnsTelephonyInfoIms(info, true, true, false, false);
        ane.onQnsTelephonyInfoChanged(infoIms);

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
        ane.onTryWfcConnectionStateChanged(true);
        assertTrue(ane.isWfcEnabled());
        assertEquals(QnsConstants.WIFI_PREF, ane.getPreferredMode());
        ane.onTryWfcConnectionStateChanged(false);
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
        info.setDataTech(ServiceState.RIL_RADIO_TECHNOLOGY_LTE);
        info.setVoiceTech(ServiceState.RIL_RADIO_TECHNOLOGY_LTE);
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

        ApnSetting apnSettingForCelluar =
                new ApnSetting.Builder()
                        .setApnTypeBitmask(ApnSetting.TYPE_IMS)
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
                .thenReturn(apnSettingForCelluar);

        assertTrue(ane.moveTransportTypeAllowed());
    }

    @Test
    public void testOnWfcEnabledChanged_Home() throws InterruptedException {
        mWfcPlatformEnabled = true;
        mSettingWfcMode = QnsConstants.WIFI_PREF;
        mSettingWfcRoamingMode = QnsConstants.WIFI_PREF;
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
        mWfcPlatformEnabled = true;
        mSettingWfcRoamingEnabled = false;
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
        mSettingWfcEnabled = true;
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
        mSettingWfcMode = QnsConstants.CELL_PREF;
        mWfcPlatformEnabled = true;
        mSettingWfcEnabled = true;
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
        info.setDataTech(ServiceState.RIL_RADIO_TECHNOLOGY_LTE);
        info.setVoiceTech(ServiceState.RIL_RADIO_TECHNOLOGY_LTE);
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
        info.setDataTech(ServiceState.RIL_RADIO_TECHNOLOGY_LTE);
        info.setVoiceTech(ServiceState.RIL_RADIO_TECHNOLOGY_LTE);
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
                    }
            );
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
}
