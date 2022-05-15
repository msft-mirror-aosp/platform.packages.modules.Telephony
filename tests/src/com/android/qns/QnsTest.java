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

    @Mock protected TelephonyManager mockTelephonyManager;
    @Mock protected CarrierConfigManager mockCarrierConfigManager;
    @Mock protected ConnectivityManager mockConnectivityManager;
    @Mock protected ImsManager mockImsManager;
    @Mock protected SubscriptionManager mockSubscriptionManager;
    @Mock protected WifiManager mockWifiManager;
    @Mock protected CountryDetector mockCountryDetector;
    @Mock protected SharedPreferences mockSharedPreferences;
    @Mock protected SharedPreferences.Editor mockSharedPreferencesEditor;

    @Mock protected ImsMmTelManager mockImsMmTelManager;
    @Mock protected SubscriptionInfo mockSubscriptionInfo;
    @Mock protected WifiInfo mMockWifiInfo;
    @Mock protected Resources mMockResources;

    private boolean mReady = false;
    private final Object mLock = new Object();

    protected void setUp() throws Exception {
        sMockContext = spy(ApplicationProvider.getApplicationContext());
        stubContext();
        stubManagers();
        stubOthers();
        addPermissions();
    }

    private void stubContext() {
        when(sMockContext
                .getSystemService(TelephonyManager.class)).thenReturn(mockTelephonyManager);
        when(sMockContext.getSystemService(SubscriptionManager.class))
                .thenReturn(mockSubscriptionManager);
        when(sMockContext.getSystemService(CarrierConfigManager.class))
                .thenReturn(mockCarrierConfigManager);
        when(sMockContext.getSystemService(ConnectivityManager.class))
                .thenReturn(mockConnectivityManager);
        when(sMockContext.getSystemService(ImsManager.class)).thenReturn(mockImsManager);
        when(sMockContext.getSystemService(WifiManager.class)).thenReturn(mockWifiManager);
        when(sMockContext.getSystemService(CountryDetector.class)).thenReturn(mockCountryDetector);
        when(sMockContext.getSharedPreferences(anyString(), anyInt()))
                .thenReturn(mockSharedPreferences);

        when(sMockContext.getResources()).thenReturn(mMockResources);
    }

    private void stubManagers() {
        when(mockTelephonyManager.createForSubscriptionId(anyInt()))
                .thenReturn(mockTelephonyManager);
        when(mockTelephonyManager.getSimCarrierId()).thenReturn(0);
        when(mockTelephonyManager.getSimCountryIso()).thenReturn("ca");
        when(mockImsManager.getImsMmTelManager(anyInt())).thenReturn(mockImsMmTelManager);
        when(mockSubscriptionManager.getActiveSubscriptionInfo(anyInt()))
                .thenReturn(mockSubscriptionInfo);
        when(mockSubscriptionManager.getActiveSubscriptionInfoForSimSlotIndex(anyInt()))
                .thenReturn(mockSubscriptionInfo);

        when(mockWifiManager.getConnectionInfo()).thenReturn(mMockWifiInfo);

        when(mockCountryDetector.detectCountry())
                .thenReturn(new Country("US", Country.COUNTRY_SOURCE_LOCATION));
        when(mockSharedPreferences.edit()).thenReturn(mockSharedPreferencesEditor);
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
