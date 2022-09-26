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

import static android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN;
import static android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_LTE;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.SystemProperties;
import android.telephony.AccessNetworkConstants;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.ims.ImsException;
import android.telephony.ims.ImsManager;
import android.telephony.ims.ImsMmTelManager;
import android.telephony.ims.ImsStateCallback;
import android.telephony.ims.ProvisioningManager;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(JUnit4.class)
public class QnsImsManagerTest {

    @Mock Context mContext;
    @Mock ImsManager mImsManager;
    @Mock ImsMmTelManager mImsMmTelManager;
    @Mock CarrierConfigManager mCarrierConfigManager;
    @Mock SubscriptionInfo mSubscriptionInfo;
    @Mock SubscriptionManager mSubscriptionManager;
    @Mock Resources mResources;
    @Mock PersistableBundle mBundle;
    @Mock ProvisioningManager mProvisioningManager;
    @Mock QnsEventDispatcher mQnsEventDispatcher;
    @Mock PackageManager mPackageManager;
    @Mock TelephonyManager mTelephonyManager;
    private MockitoSession mMockitoSession;
    private QnsImsManager mQnsImsMgr;

    @Before
    public void setup() {
        mMockitoSession =
                mockitoSession()
                        .strictness(Strictness.LENIENT)
                        .mockStatic(SystemProperties.class)
                        .mockStatic(ProvisioningManager.class)
                        .mockStatic(QnsEventDispatcher.class)
                        .mockStatic(SubscriptionManager.class)
                        .startMocking();
        MockitoAnnotations.initMocks(this);
        mContext = spy(ApplicationProvider.getApplicationContext());
        doReturn(mImsManager).when(mContext).getSystemService(ImsManager.class);
        doReturn(mImsMmTelManager).when(mImsManager).getImsMmTelManager(anyInt());
        doReturn(mCarrierConfigManager).when(mContext).getSystemService(CarrierConfigManager.class);
        doReturn(mSubscriptionManager).when(mContext).getSystemService(SubscriptionManager.class);
        doReturn(mTelephonyManager).when(mContext).getSystemService(TelephonyManager.class);
        doReturn(mTelephonyManager).when(mTelephonyManager).createForSubscriptionId(anyInt());
        doReturn(mSubscriptionInfo, mSubscriptionInfo, null)
                .when(mSubscriptionManager)
                .getActiveSubscriptionInfoForSimSlotIndex(anyInt());
        doReturn(1).when(mSubscriptionInfo).getSubscriptionId();
        when(mContext.getResources()).thenReturn(mResources);
        when(mCarrierConfigManager.getConfigForSubId(anyInt())).thenReturn(mBundle);
        when(ProvisioningManager.createForSubscriptionId(anyInt()))
                .thenReturn(mProvisioningManager);
        when(QnsEventDispatcher.getInstance(any(), anyInt())).thenReturn(mQnsEventDispatcher);
        when(SubscriptionManager.isValidSubscriptionId(anyInt())).thenReturn(true);

        mQnsImsMgr = new QnsImsManager(mContext, 0);
    }

    @After
    public void tearDown() throws Exception {
        if (mMockitoSession != null) {
            mMockitoSession.finishMocking();
            mMockitoSession = null;
        }
    }

    @Test
    public void testQnsImsManagerIsWfcEnabledByPlatform() {
        when(mResources.getBoolean(com.android.internal.R.bool.config_device_wfc_ims_available))
                .thenReturn(false);
        assertFalse(mQnsImsMgr.isWfcEnabledByPlatform());

        when(SystemProperties.getInt(eq("persist.dbg.wfc_avail_ovr0"), anyInt())).thenReturn(1);
        assertTrue(mQnsImsMgr.isWfcEnabledByPlatform());
        when(SystemProperties.getInt(eq("persist.dbg.wfc_avail_ovr0"), anyInt())).thenReturn(-1);
        assertFalse(mQnsImsMgr.isWfcEnabledByPlatform());

        when(SystemProperties.getInt(eq("persist.dbg.wfc_avail_ovr"), anyInt())).thenReturn(1);
        assertTrue(mQnsImsMgr.isWfcEnabledByPlatform());
        when(SystemProperties.getInt(eq("persist.dbg.wfc_avail_ovr"), anyInt())).thenReturn(-1);
        assertFalse(mQnsImsMgr.isWfcEnabledByPlatform());

        when(mResources.getBoolean(com.android.internal.R.bool.config_device_wfc_ims_available))
                .thenReturn(true);
        when(mCarrierConfigManager.getConfigForSubId(anyInt())).thenReturn(mBundle);
        when(mBundle.getBoolean(eq(CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL)))
                .thenReturn(true);
        when(mBundle.getBoolean(eq(CarrierConfigManager.KEY_CARRIER_IMS_GBA_REQUIRED_BOOL)))
                .thenReturn(false);
        assertTrue(mQnsImsMgr.isWfcEnabledByPlatform());
    }

    @Test
    public void testQnsImsManagerIsWfcEnabledByUser() {
        when(mImsMmTelManager.isVoWiFiSettingEnabled()).thenReturn(true);
        assertTrue(mQnsImsMgr.isWfcEnabledByUser());

        when(mImsMmTelManager.isVoWiFiSettingEnabled()).thenReturn(false);
        assertFalse(mQnsImsMgr.isWfcEnabledByUser());
    }

    @Test
    public void testQnsImsManagerIsWfcRoamingEnabledByUser() {
        when(mImsMmTelManager.isVoWiFiRoamingSettingEnabled()).thenReturn(true);
        assertTrue(mQnsImsMgr.isWfcRoamingEnabledByUser());

        when(mImsMmTelManager.isVoWiFiRoamingSettingEnabled()).thenReturn(false);
        assertFalse(mQnsImsMgr.isWfcRoamingEnabledByUser());
    }

    @Test
    public void testQnsImsManagerIsWfcProvisionedOnDevice() {
        when(mBundle.getBoolean(
                        eq(CarrierConfigManager.KEY_CARRIER_VOLTE_OVERRIDE_WFC_PROVISIONING_BOOL)))
                .thenReturn(true);
        when(mProvisioningManager.isProvisioningRequiredForCapability(anyInt(), anyInt()))
                .thenReturn(true);
        when(mProvisioningManager.getProvisioningStatusForCapability(
                        anyInt(), eq(REGISTRATION_TECH_LTE)))
                .thenReturn(false);
        when(mProvisioningManager.getProvisioningStatusForCapability(
                        anyInt(), eq(REGISTRATION_TECH_IWLAN)))
                .thenReturn(true);
        assertFalse(mQnsImsMgr.isWfcProvisionedOnDevice());

        when(mBundle.getBoolean(
                        eq(CarrierConfigManager.KEY_CARRIER_VOLTE_OVERRIDE_WFC_PROVISIONING_BOOL)))
                .thenReturn(true);
        when(mProvisioningManager.isProvisioningRequiredForCapability(anyInt(), anyInt()))
                .thenReturn(true);
        when(mProvisioningManager.getProvisioningStatusForCapability(
                        anyInt(), eq(REGISTRATION_TECH_LTE)))
                .thenReturn(true);
        when(mProvisioningManager.getProvisioningStatusForCapability(
                        anyInt(), eq(REGISTRATION_TECH_IWLAN)))
                .thenReturn(true);
        assertTrue(mQnsImsMgr.isWfcProvisionedOnDevice());

        when(mBundle.getBoolean(
                        eq(CarrierConfigManager.KEY_CARRIER_VOLTE_OVERRIDE_WFC_PROVISIONING_BOOL)))
                .thenReturn(false);
        when(mProvisioningManager.isProvisioningRequiredForCapability(anyInt(), anyInt()))
                .thenReturn(true);
        when(mProvisioningManager.getProvisioningStatusForCapability(
                        anyInt(), eq(REGISTRATION_TECH_LTE)))
                .thenReturn(true);
        when(mProvisioningManager.getProvisioningStatusForCapability(
                        anyInt(), eq(REGISTRATION_TECH_IWLAN)))
                .thenReturn(false);
        assertFalse(mQnsImsMgr.isWfcProvisionedOnDevice());

        when(mBundle.getBoolean(
                        eq(CarrierConfigManager.KEY_CARRIER_VOLTE_OVERRIDE_WFC_PROVISIONING_BOOL)))
                .thenReturn(false);
        when(mProvisioningManager.isProvisioningRequiredForCapability(anyInt(), anyInt()))
                .thenReturn(false);
        assertTrue(mQnsImsMgr.isWfcProvisionedOnDevice());
    }

    @Test
    public void testQnsImsManagerIsCrossSimCallingEnabled() throws ImsException {
        when(mImsMmTelManager.isCrossSimCallingEnabled()).thenReturn(false);
        assertFalse(mQnsImsMgr.isCrossSimCallingEnabled());

        when(mImsMmTelManager.isCrossSimCallingEnabled()).thenReturn(true);
        when(mResources.getBoolean(com.android.internal.R.bool.config_device_wfc_ims_available))
                .thenReturn(true);
        when(mCarrierConfigManager.getConfigForSubId(anyInt())).thenReturn(mBundle);
        when(mBundle.getBoolean(eq(CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL)))
                .thenReturn(true);
        when(mBundle.getBoolean(eq(CarrierConfigManager.KEY_CARRIER_IMS_GBA_REQUIRED_BOOL)))
                .thenReturn(false);
        when(mBundle.getBoolean(eq(CarrierConfigManager.KEY_CARRIER_CROSS_SIM_IMS_AVAILABLE_BOOL)))
                .thenReturn(false);
        assertFalse(mQnsImsMgr.isCrossSimCallingEnabled());

        when(mBundle.getBoolean(eq(CarrierConfigManager.KEY_CARRIER_CROSS_SIM_IMS_AVAILABLE_BOOL)))
                .thenReturn(true);
        when(mBundle.getBoolean(
                        eq(CarrierConfigManager.KEY_CARRIER_VOLTE_OVERRIDE_WFC_PROVISIONING_BOOL)))
                .thenReturn(true);
        when(mProvisioningManager.isProvisioningRequiredForCapability(anyInt(), anyInt()))
                .thenReturn(true);
        when(mProvisioningManager.getProvisioningStatusForCapability(
                        anyInt(), eq(REGISTRATION_TECH_LTE)))
                .thenReturn(false);
        when(mProvisioningManager.getProvisioningStatusForCapability(
                        anyInt(), eq(REGISTRATION_TECH_IWLAN)))
                .thenReturn(true);
        assertFalse(mQnsImsMgr.isCrossSimCallingEnabled());

        when(mBundle.getBoolean(eq(CarrierConfigManager.KEY_CARRIER_CROSS_SIM_IMS_AVAILABLE_BOOL)))
                .thenReturn(true);
        when(mBundle.getBoolean(
                        eq(CarrierConfigManager.KEY_CARRIER_VOLTE_OVERRIDE_WFC_PROVISIONING_BOOL)))
                .thenReturn(true);
        when(mProvisioningManager.isProvisioningRequiredForCapability(anyInt(), anyInt()))
                .thenReturn(true);
        when(mProvisioningManager.getProvisioningStatusForCapability(
                        anyInt(), eq(REGISTRATION_TECH_LTE)))
                .thenReturn(true);
        when(mProvisioningManager.getProvisioningStatusForCapability(
                        anyInt(), eq(REGISTRATION_TECH_IWLAN)))
                .thenReturn(true);
        assertTrue(mQnsImsMgr.isCrossSimCallingEnabled());
    }

    @Test
    public void testQnsImsManagerGetWfcMode() {
        when(mImsMmTelManager.getVoWiFiModeSetting()).thenReturn(0);
        assertEquals(0, mQnsImsMgr.getWfcMode(false));

        when(mImsMmTelManager.getVoWiFiModeSetting()).thenReturn(1);
        assertEquals(1, mQnsImsMgr.getWfcMode(false));

        when(mImsMmTelManager.getVoWiFiModeSetting()).thenReturn(2);
        assertEquals(2, mQnsImsMgr.getWfcMode(false));
    }

    @Test
    public void testQnsImsManagerGetWfcRoamingMode() {
        when(mImsMmTelManager.getVoWiFiRoamingModeSetting()).thenReturn(0);
        assertEquals(0, mQnsImsMgr.getWfcMode(true));

        when(mImsMmTelManager.getVoWiFiRoamingModeSetting()).thenReturn(1);
        assertEquals(1, mQnsImsMgr.getWfcMode(true));

        when(mImsMmTelManager.getVoWiFiRoamingModeSetting()).thenReturn(2);
        assertEquals(2, mQnsImsMgr.getWfcMode(true));
    }

    @Test
    public void testQnsImsManagerGetWfcModeFromCarrierConfig() throws ImsException {
        mQnsImsMgr.dispose();
        doThrow(new ImsException("Test Exception"))
                .when(mImsMmTelManager)
                .registerImsStateCallback(any(), any());
        mQnsImsMgr = new QnsImsManager(mContext, 0);

        when(mCarrierConfigManager.getConfigForSubId(anyInt())).thenReturn(mBundle);
        when(mBundle.getInt(eq(CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_MODE_INT)))
                .thenReturn(0);
        assertEquals(0, mQnsImsMgr.getWfcMode(false));

        when(mBundle.getInt(eq(CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_MODE_INT)))
                .thenReturn(1);
        assertEquals(1, mQnsImsMgr.getWfcMode(false));

        when(mBundle.getInt(eq(CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_MODE_INT)))
                .thenReturn(2);
        assertEquals(2, mQnsImsMgr.getWfcMode(false));
    }

    @Test
    public void testQnsImsManagerGetWfcRoamingModeFromCarrierConfig() throws ImsException {
        mQnsImsMgr.dispose();
        doThrow(new ImsException("Test Exception"))
                .when(mImsMmTelManager)
                .registerImsStateCallback(any(), any());
        mQnsImsMgr = new QnsImsManager(mContext, 0);

        when(mCarrierConfigManager.getConfigForSubId(anyInt())).thenReturn(mBundle);
        when(mBundle.getInt(eq(CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_ROAMING_MODE_INT)))
                .thenReturn(0);
        assertEquals(0, mQnsImsMgr.getWfcMode(true));

        when(mBundle.getInt(eq(CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_ROAMING_MODE_INT)))
                .thenReturn(1);
        assertEquals(1, mQnsImsMgr.getWfcMode(true));

        when(mBundle.getInt(eq(CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_ROAMING_MODE_INT)))
                .thenReturn(2);
        assertEquals(2, mQnsImsMgr.getWfcMode(true));
    }

    @Test
    public void testQnsImsManagerGetImsServiceState() throws InterruptedException {
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.hasSystemFeature(any())).thenReturn(false);
        boolean bException = false;
        try {
            mQnsImsMgr.getImsServiceState();
        } catch (ImsException e) {
            bException = true;
        }
        assertTrue(bException);

        bException = false;
        when(mPackageManager.hasSystemFeature(any())).thenReturn(true);
        try {
            mQnsImsMgr.getImsServiceState();
        } catch (ImsException e) {
            bException = true;
        }
        assertFalse(bException);
    }

    @Test
    public void testQnsImsManagerInit() throws NoSuchFieldException, IllegalAccessException {
        Field initializedField = QnsImsManager.class.getDeclaredField("mQnsImsManagerInitialized");
        initializedField.setAccessible(true);
        boolean qnsImsManagerInitialized = (boolean) initializedField.get(mQnsImsMgr);
        assertTrue(qnsImsManagerInitialized);

        when(mImsManager.getImsMmTelManager(anyInt())).thenReturn(null);
        mQnsImsMgr = new QnsImsManager(mContext, 1);
        qnsImsManagerInitialized = (boolean) initializedField.get(mQnsImsMgr);
        assertFalse(qnsImsManagerInitialized);
    }

    @Test
    public void testQnsImsManagerIsGbaValid()
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method method = QnsImsManager.class.getDeclaredMethod("isGbaValid");
        method.setAccessible(true);
        assertTrue((Boolean) method.invoke(mQnsImsMgr));

        when(mBundle.getBoolean(eq(CarrierConfigManager.KEY_CARRIER_IMS_GBA_REQUIRED_BOOL)))
                .thenReturn(true);

        when(mContext.getSystemService(TelephonyManager.class)).thenReturn(null);
        assertFalse((Boolean) method.invoke(mQnsImsMgr));

        when(mContext.getSystemService(TelephonyManager.class)).thenReturn(mTelephonyManager);
        when(mTelephonyManager.createForSubscriptionId(anyInt())).thenReturn(mTelephonyManager);
        when(mTelephonyManager.getIsimIst()).thenReturn(null);
        assertTrue((Boolean) method.invoke(mQnsImsMgr));

        when(mTelephonyManager.getIsimIst()).thenReturn("22");
        assertTrue((Boolean) method.invoke(mQnsImsMgr));

        when(mTelephonyManager.getIsimIst()).thenReturn("21");
        assertFalse((Boolean) method.invoke(mQnsImsMgr));
    }

    @Test
    public void testQnsImsManagerQnsImsRegistrationState() {
        QnsImsManager.ImsRegistrationState stateReg =
                new QnsImsManager.ImsRegistrationState(
                        QnsConstants.IMS_REGISTRATION_CHANGED_REGISTERED,
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                        null);
        QnsImsManager.ImsRegistrationState stateUnreg =
                new QnsImsManager.ImsRegistrationState(
                        QnsConstants.IMS_REGISTRATION_CHANGED_UNREGISTERED,
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                        null);
        QnsImsManager.ImsRegistrationState stateAccessNetworkChangeFail =
                new QnsImsManager.ImsRegistrationState(
                        QnsConstants.IMS_REGISTRATION_CHANGED_ACCESS_NETWORK_CHANGE_FAILED,
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                        null);
        assertTrue(stateReg.toString().contains("IMS_REG"));
        assertTrue(stateUnreg.toString().contains("IMS_UNREG"));
        assertTrue(stateAccessNetworkChangeFail.toString().contains("IMS_ACCESS"));
    }

    @Test
    public void testQnsImsManagerQnsIsImsRegistered() {
        mQnsImsMgr.notifyImsRegistrationChangedEvent(
                QnsConstants.IMS_REGISTRATION_CHANGED_REGISTERED,
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                null);
        assertTrue(mQnsImsMgr.isImsRegistered(AccessNetworkConstants.TRANSPORT_TYPE_WWAN));
        assertFalse(mQnsImsMgr.isImsRegistered(AccessNetworkConstants.TRANSPORT_TYPE_WLAN));

        mQnsImsMgr.notifyImsRegistrationChangedEvent(
                QnsConstants.IMS_REGISTRATION_CHANGED_UNREGISTERED,
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                null);
        assertFalse(mQnsImsMgr.isImsRegistered(AccessNetworkConstants.TRANSPORT_TYPE_WWAN));
        assertFalse(mQnsImsMgr.isImsRegistered(AccessNetworkConstants.TRANSPORT_TYPE_WLAN));
    }

    @Test
    public void testImsStateEvents()
            throws InterruptedException, NoSuchFieldException, IllegalAccessException {
        final int eventImsStateChanged = 11005;
        CountDownLatch imsAvailableLatch = new CountDownLatch(2);
        CountDownLatch imsUnavailableLatch = new CountDownLatch(2);
        HandlerThread handlerThread = new HandlerThread("testImsStateEvent");
        handlerThread.start();
        Handler handler =
                new Handler(handlerThread.getLooper()) {
                    @Override
                    public void handleMessage(@NonNull Message msg) {
                        QnsAsyncResult ar = (QnsAsyncResult) msg.obj;
                        switch (msg.what) {
                            case eventImsStateChanged:
                                if (ar != null) {
                                    QnsImsManager.ImsState state =
                                            (QnsImsManager.ImsState) ar.result;
                                    if (state.isImsAvailable()) {
                                        imsAvailableLatch.countDown();
                                    } else {
                                        imsUnavailableLatch.countDown();
                                    }
                                }
                                break;
                        }
                    }
                };

        Field callbackField = QnsImsManager.class.getDeclaredField("mQnsImsStateCallback");
        callbackField.setAccessible(true);
        ImsStateCallback imsStateCallback = (ImsStateCallback) callbackField.get(mQnsImsMgr);

        mQnsImsMgr.registerImsStateChanged(handler, eventImsStateChanged);
        mQnsImsMgr.notifyImsStateChanged(new QnsImsManager.ImsState(true));
        mQnsImsMgr.notifyImsStateChanged(new QnsImsManager.ImsState(false));

        imsStateCallback.onAvailable();
        imsStateCallback.onAvailable();
        imsStateCallback.onUnavailable(100);
        imsStateCallback.onUnavailable(100);

        assertTrue(imsAvailableLatch.await(100, TimeUnit.MILLISECONDS));
        assertTrue(imsUnavailableLatch.await(100, TimeUnit.MILLISECONDS));

        mQnsImsMgr.unregisterImsStateChanged(handler);
        mQnsImsMgr.notifyImsStateChanged(new QnsImsManager.ImsState(true));
        mQnsImsMgr.notifyImsStateChanged(new QnsImsManager.ImsState(false));

        assertTrue(imsAvailableLatch.await(100, TimeUnit.MILLISECONDS));
        assertTrue(imsUnavailableLatch.await(100, TimeUnit.MILLISECONDS));
    }
}
