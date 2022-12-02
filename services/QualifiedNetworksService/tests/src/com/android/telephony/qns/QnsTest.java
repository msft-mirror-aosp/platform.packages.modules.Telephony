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

package com.android.telephony.qns;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.location.Country;
import android.location.CountryDetector;
import android.net.ConnectivityManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.ims.ImsManager;
import android.telephony.ims.ImsMmTelManager;

import androidx.test.core.app.ApplicationProvider;

import org.mockito.Mock;

import java.lang.reflect.Field;

public abstract class QnsTest {
    private static final long MAX_WAIT_TIME_MS = 10000;
    @Mock protected static Context sMockContext;

    @Mock protected TelephonyManager mMockTelephonyManager;
    @Mock protected CarrierConfigManager mMockCarrierConfigManager;
    @Mock protected ConnectivityManager mMockConnectivityManager;
    @Mock protected ImsManager mMockImsManager;
    @Mock protected SubscriptionManager mMockSubscriptionManager;
    @Mock protected WifiManager mMockWifiManager;
    @Mock protected CountryDetector mMockCountryDetector;
    @Mock protected SharedPreferences mMockSharedPreferences;
    @Mock protected SharedPreferences.Editor mMockSharedPreferencesEditor;

    @Mock protected ImsMmTelManager mMockImsMmTelManager;
    @Mock protected SubscriptionInfo mMockSubscriptionInfo;
    @Mock protected WifiInfo mMockWifiInfo;
    @Mock protected Resources mMockResources;

    // qns mocks
    @Mock AlternativeEventListener mMockAltEventListener;
    @Mock protected IwlanNetworkStatusTracker mMockIwlanNetworkStatusTracker;
    @Mock protected WifiQualityMonitor mMockWifiQm;
    @Mock protected CellularNetworkStatusTracker mMockCellNetStatusTracker;
    @Mock protected CellularQualityMonitor mMockCellularQm;
    @Mock protected QnsImsManager mMockQnsImsManager;
    @Mock protected QnsCarrierConfigManager mMockQnsConfigManager;
    @Mock protected QnsEventDispatcher mMockQnsEventDispatcher;
    @Mock protected QnsProvisioningListener mMockQnsProvisioningListener;
    @Mock protected QnsTelephonyListener mMockQnsTelephonyListener;
    @Mock protected QnsCallStatusTracker mMockQnsCallStatusTracker;
    @Mock protected WifiBackhaulMonitor mMockWifiBm;

    protected QnsComponents[] mQnsComponents = new QnsComponents[2];

    private boolean mReady = false;
    private final Object mLock = new Object();

    protected void setUp() throws Exception {
        sMockContext = spy(ApplicationProvider.getApplicationContext());
        stubContext();
        stubManagers();
        stubOthers();
        stubQnsComponents();
        addPermissions();
    }

    private void stubQnsComponents() {
        mQnsComponents[0] =
                new QnsComponents(
                        sMockContext,
                        mMockAltEventListener,
                        mMockCellNetStatusTracker,
                        mMockCellularQm,
                        mMockIwlanNetworkStatusTracker,
                        mMockQnsImsManager,
                        mMockQnsConfigManager,
                        mMockQnsEventDispatcher,
                        mMockQnsProvisioningListener,
                        mMockQnsTelephonyListener,
                        mMockQnsCallStatusTracker,
                        mMockWifiBm,
                        mMockWifiQm,
                        0);

        mQnsComponents[1] =
                new QnsComponents(
                        sMockContext,
                        mMockAltEventListener,
                        mMockCellNetStatusTracker,
                        mMockCellularQm,
                        mMockIwlanNetworkStatusTracker,
                        mMockQnsImsManager,
                        mMockQnsConfigManager,
                        mMockQnsEventDispatcher,
                        mMockQnsProvisioningListener,
                        mMockQnsTelephonyListener,
                        mMockQnsCallStatusTracker,
                        mMockWifiBm,
                        mMockWifiQm,
                        1);
    }

    private void stubContext() {
        when(sMockContext.getSystemService(TelephonyManager.class))
                .thenReturn(mMockTelephonyManager);
        when(sMockContext.getSystemService(TelephonyManager.class))
                .thenReturn(mMockTelephonyManager);
        when(sMockContext.getSystemService(SubscriptionManager.class))
                .thenReturn(mMockSubscriptionManager);
        when(sMockContext.getSystemService(CarrierConfigManager.class))
                .thenReturn(mMockCarrierConfigManager);
        when(sMockContext.getSystemService(ConnectivityManager.class))
                .thenReturn(mMockConnectivityManager);
        when(sMockContext.getSystemService(ImsManager.class)).thenReturn(mMockImsManager);
        when(sMockContext.getSystemService(WifiManager.class)).thenReturn(mMockWifiManager);
        when(sMockContext.getSystemService(CountryDetector.class)).thenReturn(mMockCountryDetector);
        when(sMockContext.getSharedPreferences(anyString(), anyInt()))
                .thenReturn(mMockSharedPreferences);

        when(sMockContext.getResources()).thenReturn(mMockResources);
    }

    private void stubManagers() {
        when(mMockTelephonyManager.createForSubscriptionId(anyInt()))
                .thenReturn(mMockTelephonyManager);
        when(mMockTelephonyManager.getSimCarrierId()).thenReturn(0);
        when(mMockTelephonyManager.getSimCountryIso()).thenReturn("ca");
        when(mMockImsManager.getImsMmTelManager(anyInt())).thenReturn(mMockImsMmTelManager);
        when(mMockSubscriptionManager.getActiveSubscriptionInfo(anyInt()))
                .thenReturn(mMockSubscriptionInfo);
        when(mMockSubscriptionManager.getActiveSubscriptionInfoForSimSlotIndex(anyInt()))
                .thenReturn(mMockSubscriptionInfo);

        when(mMockWifiManager.getConnectionInfo()).thenReturn(mMockWifiInfo);

        when(mMockCountryDetector.detectCountry())
                .thenReturn(new Country("US", Country.COUNTRY_SOURCE_LOCATION));
        when(mMockSharedPreferences.edit()).thenReturn(mMockSharedPreferencesEditor);
    }

    private void stubOthers() {
        when(mMockWifiInfo.getRssi()).thenReturn(-65);
    }

    private void addPermissions() {
        when(sMockContext.checkPermission(anyString(), anyInt(), anyInt()))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
    }

    protected void waitUntilReady() {
        synchronized (mLock) {
            if (!mReady) {
                try {
                    mLock.wait(MAX_WAIT_TIME_MS);
                } catch (InterruptedException e) {
                }
                if (!mReady) {
                    fail("Test is not ready!!");
                }
            }
        }
    }

    protected void waitFor(int waitTimeMillis) {
        synchronized (mLock) {
            try {
                mLock.wait(waitTimeMillis);
            } catch (InterruptedException e) {
            }
        }
    }

    protected void setReady(boolean ready) {
        synchronized (mLock) {
            mReady = ready;
            mLock.notifyAll();
        }
    }

    protected synchronized void setObject(
            final Class c, final String field, final Object obj, final Object newValue)
            throws Exception {
        Field f = c.getDeclaredField(field);
        f.setAccessible(true);
        f.set(obj, newValue);
    }
}
