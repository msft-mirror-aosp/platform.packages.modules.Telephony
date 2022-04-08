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

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.AsyncResult;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.telephony.AccessNetworkConstants;
import android.telephony.ServiceState;
import android.telephony.data.ApnSetting;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(JUnit4.class)
public class AccessNetworkEvaluatorTest extends QnsTest {
    private static final String TEST_PLMN = "707333";

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
    private QualifiedNetworksServiceImpl.QualifiedNetworksInfo mQualifiedNetworksInfo;
    private CountDownLatch latch;
    private int mSlotIndex = 0;
    private int mApnType = ApnSetting.TYPE_IMS;
    private HandlerThread mHandlerThread;

    private class AneHandler extends Handler {
        AneHandler() {
            super(mHandlerThread.getLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            switch (msg.what) {
                case 1:
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
        ane =
                new AccessNetworkEvaluator(
                        mSlotIndex,
                        mApnType,
                        mockContext,
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
    }

    @After
    public void tearDown() throws Exception {
        if (mHandlerThread != null) {
            mHandlerThread.quit();
        }
        if (ane != null) {
            ane.close();
        }
        QnsCarrierConfigManager.getInstance(mockContext, 0).close();
        QnsCarrierConfigManager.getInstance(mockContext, 1).close();
        IwlanNetworkStatusTracker.getInstance(mockContext).close();
        WifiQualityMonitor.getInstance(mockContext).dispose();
    }

    @Test
    public void testRegisterForQualifiedNetworksChanged() {
        Handler h2 = new Handler(Looper.getMainLooper());
        ane.registerForQualifiedNetworksChanged(mHandler, 1);
        ane.registerForQualifiedNetworksChanged(mHandler, 2);
        assertEquals(1, ane.mQualifiedNetworksChangedRegistrants.size());
        ane.registerForQualifiedNetworksChanged(h2, 3);
        assertEquals(2, ane.mQualifiedNetworksChangedRegistrants.size());
    }

    @Test
    public void testUnregisterForQualifiedNetworksChanged() {
        Handler h2 = new Handler(Looper.getMainLooper());
        ane.registerForQualifiedNetworksChanged(mHandler, 1);
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
        ane.registerForQualifiedNetworksChanged(mHandler, 1);
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
        ane.registerForQualifiedNetworksChanged(mHandler, 1);
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
        ane.onIwlanNetworkStatusChanged(
                iwlanNetworkStatusTracker.new IwlanAvailabilityInfo(true, false));
        assertTrue(ane.mIwlanAvailable);
        ane.onIwlanNetworkStatusChanged(
                iwlanNetworkStatusTracker.new IwlanAvailabilityInfo(false, false));
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
                AccessNetworkConstants.TRANSPORT_TYPE_INVALID,
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
}
