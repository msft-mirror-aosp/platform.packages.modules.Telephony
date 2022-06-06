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

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.telephony.AccessNetworkConstants;
import android.telephony.AccessNetworkConstants.AccessNetworkType;
import android.telephony.data.ApnSetting;
import android.telephony.data.ThrottleStatus;

import com.android.qns.QualifiedNetworksServiceImpl.QualifiedNetworksInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public class QualifiedNetworksServiceImplTest extends QnsTest {

    QualifiedNetworksServiceImpl mQualifiedNetworksService;
    private static final int TEST_QNS_CONFIGURATION_LOADED = 1;
    private static final int TEST_QUALIFIED_NETWORKS_CHANGED = 2;
    private static final int TEST_QNS_CONFIGURATION_CHANGED = 2;
    MockitoSession mMockitoSession;
    @Mock private QnsCarrierConfigManager mMockConfigManager;
    @Mock private QnsProvisioningListener mMockProvisioningListener;
    @Mock private QnsTelephonyListener mMockQnsTelephonyListener;
    private int mSlotIndex = 0;
    @Mock private AccessNetworkEvaluator mMockAne;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        super.setUp();
        when(mockTelephonyManager.getActiveModemCount()).thenReturn(2);
        mMockitoSession =
                mockitoSession()
                        .mockStatic(QnsCarrierConfigManager.class)
                        .mockStatic(QnsTelephonyListener.class)
                        .mockStatic(QnsProvisioningListener.class)
                        .startMocking();
        mQualifiedNetworksService = new QualifiedNetworksServiceImpl();
        Field f = QualifiedNetworksServiceImpl.class.getDeclaredField("mContext");
        f.setAccessible(true);
        f.set(mQualifiedNetworksService, sMockContext);

        lenient()
                .when(QnsCarrierConfigManager.getInstance(isA(Context.class), anyInt()))
                .thenReturn(mMockConfigManager);
        lenient()
                .when(QnsTelephonyListener.getInstance(isA(Context.class), anyInt()))
                .thenReturn(mMockQnsTelephonyListener);
        lenient()
                .when(QnsProvisioningListener.getInstance(isA(Context.class), anyInt()))
                .thenReturn(mMockProvisioningListener);
    }

    @After
    public void tearDown() throws Exception {
        mQualifiedNetworksService.onDestroy();
        waitFor(100); // close triggers on different thread.
        mMockitoSession.finishMocking();
    }

    @Test
    public void testOnCreateNetworkAvailabilityProvider() {
        mQualifiedNetworksService.onCreateNetworkAvailabilityProvider(0);
        assertEquals(1, mQualifiedNetworksService.mProviderMap.size());

        // Invalid slot
        mQualifiedNetworksService.onCreateNetworkAvailabilityProvider(3);
        assertEquals(1, mQualifiedNetworksService.mProviderMap.size());

        mQualifiedNetworksService.onDestroy();
        assertTrue(mQualifiedNetworksService.mProviderMap.isEmpty());
    }

    @Test
    public void testQualifiedNetworksInfo() {
        QualifiedNetworksInfo info =
                new QualifiedNetworksInfo(ApnSetting.TYPE_IMS, new ArrayList<>());
        assertEquals(info.getApnType(), ApnSetting.TYPE_IMS);
        assertTrue(info.getAccessNetworkTypes().isEmpty());
        info.setAccessNetworkTypes(List.of(AccessNetworkType.EUTRAN));
        assertEquals(List.of(AccessNetworkType.EUTRAN), info.getAccessNetworkTypes());
        info.setApnType(ApnSetting.TYPE_MMS);
        assertEquals(ApnSetting.TYPE_MMS, info.getApnType());
    }

    @Test
    public void testAneCreation() {
        int supportedApns =
                ApnSetting.TYPE_IMS
                        | ApnSetting.TYPE_EMERGENCY
                        | ApnSetting.TYPE_MMS
                        | ApnSetting.TYPE_XCAP;
        when(mMockConfigManager.getQnsSupportedApnTypes())
                .thenReturn(supportedApns, ApnSetting.TYPE_IMS | ApnSetting.TYPE_XCAP);
        when(mMockConfigManager.getQnsSupportedTransportType(anyInt()))
                .thenAnswer(
                        (invocation) -> {
                            if (ApnSetting.TYPE_XCAP == (int) invocation.getArgument(0)) {
                                return QnsConstants.TRANSPORT_TYPE_ALLOWED_WWAN;
                            }
                            return QnsConstants.TRANSPORT_TYPE_ALLOWED_BOTH;
                        });

        QualifiedNetworksServiceImpl.NetworkAvailabilityProviderImpl provider =
                mQualifiedNetworksService.new NetworkAvailabilityProviderImpl(mSlotIndex);

        ArgumentCaptor<Handler> capture = ArgumentCaptor.forClass(Handler.class);
        verify(mMockConfigManager)
                .registerForConfigurationLoaded(
                        capture.capture(), eq(TEST_QNS_CONFIGURATION_LOADED));
        Handler configHandler = capture.getValue();
        configHandler.sendEmptyMessage(TEST_QNS_CONFIGURATION_LOADED);
        waitFor(3000); // ANEs take time to create.

        assertEquals(3, provider.mEvaluators.size());

        configHandler.sendEmptyMessage(TEST_QNS_CONFIGURATION_LOADED);
        waitFor(2000); // ANEs take time to create.

        assertEquals(1, provider.mEvaluators.size());

        provider.close();
    }

    @Test
    public void testReportThrottle_Enable() {
        long timer = 10000;
        buildThrottle(mSlotIndex, ApnSetting.TYPE_IMS, timer);
        verify(mMockAne)
                .updateThrottleStatus(true, timer, AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
    }

    @Test
    public void testReportThrottle_Disable() {
        long timer = -1;
        buildThrottle(mSlotIndex, ApnSetting.TYPE_IMS, -1);
        verify(mMockAne)
                .updateThrottleStatus(false, timer, AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
    }

    @Test
    public void testReportThrottle_WrongSlot() {
        long timer = 10000;
        buildThrottle(1, ApnSetting.TYPE_IMS, timer);
        verify(mMockAne, never()).updateThrottleStatus(anyBoolean(), anyLong(), anyInt());
    }

    @Test
    public void testReportThrottle_NoAne() {
        long timer = 10000;
        buildThrottle(mSlotIndex, ApnSetting.TYPE_XCAP, timer);
        verify(mMockAne, never()).updateThrottleStatus(anyBoolean(), anyLong(), anyInt());
    }

    private void buildThrottle(int slot, int apnType, long timer) {
        ThrottleStatus.Builder builder =
                new ThrottleStatus.Builder()
                        .setSlotIndex(slot)
                        .setTransportType(AccessNetworkConstants.TRANSPORT_TYPE_WLAN)
                        .setApnType(apnType);
        if (timer == -1) {
            builder.setNoThrottle();
        } else {
            builder.setThrottleExpiryTimeMillis(timer);
        }

        QualifiedNetworksServiceImpl.NetworkAvailabilityProviderImpl provider =
                mQualifiedNetworksService.new NetworkAvailabilityProviderImpl(mSlotIndex);
        provider.mEvaluators.put(ApnSetting.TYPE_IMS, mMockAne);
        provider.reportThrottleStatusChanged(List.of(builder.build()));
    }

    @Test
    public void testOnConfigurationChanged() {
        QualifiedNetworksServiceImpl.NetworkAvailabilityProviderImpl provider =
                mQualifiedNetworksService.new NetworkAvailabilityProviderImpl(mSlotIndex);
        provider.mConfigHandler.handleMessage(
                Message.obtain(provider.mConfigHandler, TEST_QNS_CONFIGURATION_LOADED, null));
        ArgumentCaptor<Handler> capture = ArgumentCaptor.forClass(Handler.class);

        verify(mMockConfigManager)
                .registerForConfigurationLoaded(
                        capture.capture(), eq(TEST_QNS_CONFIGURATION_LOADED));

        Handler configHandler = capture.getValue();
        configHandler.sendEmptyMessage(TEST_QNS_CONFIGURATION_CHANGED);
        provider.mConfigHandler.handleMessage(
                Message.obtain(provider.mConfigHandler, TEST_QNS_CONFIGURATION_CHANGED, null));
        provider.mConfigHandler.handleMessage(
                Message.obtain(provider.mConfigHandler, TEST_QUALIFIED_NETWORKS_CHANGED, null));
    }

    @Test
    public void testQnsChangedHandlerEvent() {
        QualifiedNetworksServiceImpl.NetworkAvailabilityProviderImpl provider =
                mQualifiedNetworksService.new NetworkAvailabilityProviderImpl(mSlotIndex);
        QualifiedNetworksInfo info =
                new QualifiedNetworksInfo(ApnSetting.TYPE_IMS, new ArrayList<>());
        info.setAccessNetworkTypes(List.of(AccessNetworkType.EUTRAN));
        AsyncResult ar = new AsyncResult(null, info, null);
        provider.mHandler.handleMessage(
                Message.obtain(provider.mHandler, TEST_QUALIFIED_NETWORKS_CHANGED, ar));
        assertEquals(info.getApnType(), ApnSetting.TYPE_IMS);
        assertEquals(List.of(AccessNetworkType.EUTRAN), info.getAccessNetworkTypes());
        provider.mHandler.handleMessage(
                Message.obtain(provider.mHandler, TEST_QNS_CONFIGURATION_LOADED, ar));
    }
}
