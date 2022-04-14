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
import android.location.Country;
import android.location.CountryDetector;
import android.net.ConnectivityManager;
import android.net.wifi.WifiInfo;
import android.content.res.Resources;
import android.net.wifi.WifiManager;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.ims.ImsMmTelManager;

import androidx.test.core.app.ApplicationProvider;

import android.telephony.ims.ImsManager;

import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

public abstract class QnsTest {
    private static final long MAX_WAIT_TIME_MS = 10000;
    @Mock protected Context mockContext;

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
    @Mock protected WifiInfo mockWifiInfo;

    private boolean mReady = false;
    private boolean mNewDataStackEnabled = true;
    private Object mLock = new Object();

    protected void setUp() throws Exception {
        mockContext = spy(ApplicationProvider.getApplicationContext());
        stubContext();
        stubManagers();
        stubOthers();
        addPermissions();
    }

    private void stubContext() {
        when(mockContext.getSystemService(TelephonyManager.class)).thenReturn(mockTelephonyManager);
        when(mockContext.getSystemService(SubscriptionManager.class))
                .thenReturn(mockSubscriptionManager);
        when(mockContext.getSystemService(CarrierConfigManager.class))
                .thenReturn(mockCarrierConfigManager);
        when(mockContext.getSystemService(ConnectivityManager.class))
                .thenReturn(mockConnectivityManager);
        when(mockContext.getSystemService(ImsManager.class)).thenReturn(mockImsManager);
        when(mockContext.getSystemService(WifiManager.class)).thenReturn(mockWifiManager);
        when(mockContext.getSystemService(CountryDetector.class)).thenReturn(mockCountryDetector);
        when(mockContext.getSharedPreferences(anyString(), anyInt()))
                .thenReturn(mockSharedPreferences);
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

        when(mockWifiManager.getConnectionInfo()).thenReturn(mockWifiInfo);

        when(mockCountryDetector.detectCountry())
                .thenReturn(new Country("US", Country.COUNTRY_SOURCE_LOCATION));
        when(mockSharedPreferences.edit()).thenReturn(mockSharedPreferencesEditor);
    }

    private void stubOthers() {
        when(mockWifiInfo.getRssi()).thenReturn(-65);
    }

    private void addPermissions() {
        when(mockContext.checkPermission(anyString(), anyInt(), anyInt()))
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

    protected void setReady(boolean ready) {
        synchronized (mLock) {
            mReady = ready;
            mLock.notifyAll();
        }
    }

    protected PersistableBundle createBundleFor(String key, int value) {
        PersistableBundle bundle = new PersistableBundle();
        bundle.putInt(key, value);
        return bundle;
    }

    protected PersistableBundle createBundleFor(String key, int[] value) {
        PersistableBundle bundle = new PersistableBundle();
        bundle.putIntArray(key, value);
        return bundle;
    }

    protected PersistableBundle createBundleFor(String key, String value) {
        PersistableBundle bundle = new PersistableBundle();
        bundle.putString(key, value);
        return bundle;
    }

    protected PersistableBundle createBundleFor(String key, String[] value) {
        PersistableBundle bundle = new PersistableBundle();
        bundle.putStringArray(key, value);
        return bundle;
    }

    protected PersistableBundle createBundleFor(String key, boolean value) {
        PersistableBundle bundle = new PersistableBundle();
        bundle.putBoolean(key, value);
        return bundle;
    }

    protected PersistableBundle createBundleFor(String key, boolean[] value) {
        PersistableBundle bundle = new PersistableBundle();
        bundle.putBooleanArray(key, value);
        return bundle;
    }
}
