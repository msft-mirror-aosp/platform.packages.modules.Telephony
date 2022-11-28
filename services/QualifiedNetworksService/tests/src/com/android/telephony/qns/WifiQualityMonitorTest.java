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

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.telephony.AccessNetworkConstants;
import android.telephony.SignalThresholdInfo;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@RunWith(JUnit4.class)
public class WifiQualityMonitorTest {

    Context mContext;
    @Mock ConnectivityManager mConnectivityManager;
    @Mock WifiManager mWifiManager;
    private WifiInfo mWifiInfo;
    private WifiQualityMonitor mWifiQualityMonitor;
    Threshold[] mThs1 = new Threshold[1];
    Threshold[] mThs2 = new Threshold[1];
    Threshold[] mThs3 = new Threshold[1];
    int mSetRssi = -120;

    CountDownLatch mLatch;
    ThresholdListener mThresholdListener;

    private class ThresholdListener extends ThresholdCallback
            implements ThresholdCallback.WifiThresholdListener {

        ThresholdListener(Executor executor) {
            this.init(executor);
        }

        @Override
        public void onWifiThresholdChanged(Threshold[] thresholds) {
            mLatch.countDown();
        }
    }

    Executor mExecutor = runnable -> new Thread(runnable).start();

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = spy(ApplicationProvider.getApplicationContext());
        when(mContext.getSystemService(ConnectivityManager.class)).thenReturn(mConnectivityManager);
        when(mContext.getSystemService(WifiManager.class)).thenReturn(mWifiManager);
        mWifiInfo = new WifiInfo.Builder().setRssi(mSetRssi).build();
        mLatch = new CountDownLatch(1);
        mThresholdListener = new ThresholdListener(mExecutor);
        mWifiQualityMonitor = new WifiQualityMonitor(mContext);
    }

    @Test
    public void testGetCurrentQuality() {
        when(mWifiManager.getConnectionInfo()).thenReturn(mWifiInfo);
        int receiveRssi =
                mWifiQualityMonitor.getCurrentQuality(
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSI);
        Assert.assertEquals(mSetRssi, receiveRssi);
    }

    @Test
    public void testRegisterThresholdChange_RoveIn() {
        when(mWifiManager.getConnectionInfo()).thenReturn(mWifiInfo);
        mThs1[0] =
                new Threshold(
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSI,
                        -100,
                        QnsConstants.THRESHOLD_EQUAL_OR_LARGER,
                        QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER);
        mThs2[0] =
                new Threshold(
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSI,
                        -80,
                        QnsConstants.THRESHOLD_EQUAL_OR_LARGER,
                        QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER);
        mWifiQualityMonitor.registerThresholdChange(
                mThresholdListener, NetworkCapabilities.NET_CAPABILITY_IMS, mThs1, 0);
        mWifiQualityMonitor.registerThresholdChange(
                mThresholdListener, NetworkCapabilities.NET_CAPABILITY_XCAP, mThs2, 0);
        int regThreshold = mWifiQualityMonitor.getRegisteredThreshold();
        // smaller threshold should register
        Assert.assertEquals(mThs1[0].getThreshold(), regThreshold);
    }

    @Test
    public void testUnregisterThresholdChange_RoveIn() {
        testRegisterThresholdChange_RoveIn();
        mWifiQualityMonitor.unregisterThresholdChange(NetworkCapabilities.NET_CAPABILITY_IMS, 0);
        int regThreshold = mWifiQualityMonitor.getRegisteredThreshold();
        Assert.assertEquals(mThs2[0].getThreshold(), regThreshold);
    }

    @Test
    public void testRegisterThresholdChange_RoveOut() {
        when(mWifiManager.getConnectionInfo()).thenReturn(mWifiInfo);
        mThs1[0] =
                new Threshold(
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSI,
                        -100,
                        QnsConstants.THRESHOLD_EQUAL_OR_SMALLER,
                        QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER);
        mThs2[0] =
                new Threshold(
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSI,
                        -80,
                        QnsConstants.THRESHOLD_EQUAL_OR_SMALLER,
                        QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER);
        mWifiQualityMonitor.registerThresholdChange(
                mThresholdListener, NetworkCapabilities.NET_CAPABILITY_IMS, mThs1, 0);
        mWifiQualityMonitor.registerThresholdChange(
                mThresholdListener, NetworkCapabilities.NET_CAPABILITY_XCAP, mThs2, 0);
        int regThreshold = mWifiQualityMonitor.getRegisteredThreshold();
        // bigger threshold should register
        Assert.assertEquals(mThs2[0].getThreshold(), regThreshold);
    }

    @Test
    public void testUnregisterThresholdChange_RoveOut() {
        testRegisterThresholdChange_RoveOut();
        mWifiQualityMonitor.unregisterThresholdChange(NetworkCapabilities.NET_CAPABILITY_XCAP, 0);
        int regThreshold = mWifiQualityMonitor.getRegisteredThreshold();
        Assert.assertEquals(mThs1[0].getThreshold(), regThreshold);
    }

    @Test
    public void testUpdateThresholdsForNetCapability_RoveIn_Add() {
        testRegisterThresholdChange_RoveIn();
        mThs3[0] =
                new Threshold(
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSI,
                        -110,
                        QnsConstants.THRESHOLD_EQUAL_OR_LARGER,
                        QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER);
        mWifiQualityMonitor.updateThresholdsForNetCapability(
                NetworkCapabilities.NET_CAPABILITY_IMS, 0, mThs3);
        int regThreshold = mWifiQualityMonitor.getRegisteredThreshold();
        Assert.assertEquals(mThs3[0].getThreshold(), regThreshold);
    }

    @Test
    public void testUpdateThresholdsForNetCapability_RoveIn_Remove() {
        testUpdateThresholdsForNetCapability_RoveIn_Add();
        mWifiQualityMonitor.updateThresholdsForNetCapability(
                NetworkCapabilities.NET_CAPABILITY_IMS, 0, null);
        int regThreshold = mWifiQualityMonitor.getRegisteredThreshold();
        Assert.assertEquals(mThs2[0].getThreshold(), regThreshold);
    }

    @Test
    public void testUpdateThresholdsForNetCapability_RoveOut_Add() {
        testRegisterThresholdChange_RoveOut();
        mThs3[0] =
                new Threshold(
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSI,
                        -75,
                        QnsConstants.THRESHOLD_EQUAL_OR_SMALLER,
                        QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER);
        mWifiQualityMonitor.updateThresholdsForNetCapability(
                NetworkCapabilities.NET_CAPABILITY_IMS, 0, mThs3);
        int regThreshold = mWifiQualityMonitor.getRegisteredThreshold();
        Assert.assertEquals(mThs3[0].getThreshold(), regThreshold);
    }

    @Test
    public void testUpdateThresholdsForNetCapability_RoveOut_Remove() {
        testRegisterThresholdChange_RoveOut();
        mWifiQualityMonitor.updateThresholdsForNetCapability(
                NetworkCapabilities.NET_CAPABILITY_IMS, 0, null);
        int regThreshold = mWifiQualityMonitor.getRegisteredThreshold();
        Assert.assertEquals(mThs2[0].getThreshold(), regThreshold);
    }

    @Test
    public void testBackhaulTimer() throws InterruptedException {
        mSetRssi = -65;
        mLatch = new CountDownLatch(4);
        mWifiInfo = new WifiInfo.Builder().setRssi(mSetRssi).build();
        when(mWifiManager.getConnectionInfo()).thenReturn(mWifiInfo);
        mThs1[0] =
                new Threshold(
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSI,
                        -100,
                        QnsConstants.THRESHOLD_EQUAL_OR_LARGER,
                        QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER);
        mThs2[0] =
                new Threshold(
                        AccessNetworkConstants.AccessNetworkType.IWLAN,
                        SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSI,
                        -80,
                        QnsConstants.THRESHOLD_EQUAL_OR_LARGER,
                        QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER);
        mThs3 =
                new Threshold[] {
                    new Threshold(
                            AccessNetworkConstants.AccessNetworkType.IWLAN,
                            SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSI,
                            -70,
                            QnsConstants.THRESHOLD_EQUAL_OR_LARGER,
                            QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER),
                    new Threshold(
                            AccessNetworkConstants.AccessNetworkType.IWLAN,
                            SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSI,
                            -68,
                            QnsConstants.THRESHOLD_EQUAL_OR_LARGER,
                            QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER)
                };
        mWifiQualityMonitor.registerThresholdChange(
                mThresholdListener, NetworkCapabilities.NET_CAPABILITY_IMS, mThs1, 0);
        mWifiQualityMonitor.registerThresholdChange(
                mThresholdListener, NetworkCapabilities.NET_CAPABILITY_XCAP, mThs1, 0);
        mWifiQualityMonitor.registerThresholdChange(
                mThresholdListener, NetworkCapabilities.NET_CAPABILITY_EIMS, mThs2, 0);
        mWifiQualityMonitor.registerThresholdChange(
                mThresholdListener, NetworkCapabilities.NET_CAPABILITY_MMS, mThs3, 0);
        Intent i = new Intent();
        i.setAction(WifiManager.RSSI_CHANGED_ACTION);
        i.putExtra(WifiManager.EXTRA_NEW_RSSI, -75);
        mWifiQualityMonitor.mWifiStateIntentReceiver.onReceive(mContext, i);
        Assert.assertTrue(mLatch.await(5, TimeUnit.SECONDS));
    }

    @After
    public void tearDown() {
        mWifiQualityMonitor.close();
    }
}
