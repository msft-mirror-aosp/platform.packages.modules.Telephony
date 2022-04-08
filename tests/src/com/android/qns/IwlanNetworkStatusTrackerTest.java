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
import static org.mockito.Mockito.*;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TelephonyNetworkSpecifier;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(JUnit4.class)
public class IwlanNetworkStatusTrackerTest extends QnsTest {

    private static final int INVALID_SUB_ID = -1;
    private IwlanNetworkStatusTracker mIwlanNetworkStatusTracker;
    private IwlanNetworkStatusTracker.IwlanAvailabilityInfo mIwlanAvailabilityInfo;
    private final TestHandler[] mHandlers = new TestHandler[2];
    private final HandlerThread[] ht = new HandlerThread[2];
    @Mock private Network mockNetwork;
    private MockitoSession mMockSession;
    private NetworkCapabilities mNetworkCapabilities;

    private class TestHandlerThread extends HandlerThread {

        public TestHandlerThread() {
            super("");
        }

        @Override
        protected void onLooperPrepared() {
            super.onLooperPrepared();
            mIwlanNetworkStatusTracker = IwlanNetworkStatusTracker.getInstance(mockContext);
            setReady(true);
        }
    }

    private class TestHandler extends Handler {
        private int mSlotId;
        CountDownLatch mLatch;

        TestHandler(HandlerThread ht, int slotId) {
            super(ht.getLooper());
            mSlotId = slotId;
        }

        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            AsyncResult ar = (AsyncResult) msg.obj;
            mIwlanAvailabilityInfo = (IwlanNetworkStatusTracker.IwlanAvailabilityInfo) ar.result;
            if (mLatch != null) {
                mLatch.countDown();
            }
        }
    }
    ;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        super.setUp();
        ht[0] = new TestHandlerThread();
        ht[0].start();
        waitUntilReady();
        ht[1] = new TestHandlerThread();
        ht[1].start();
        waitUntilReady();
        mHandlers[0] = new TestHandler(ht[0], 0);
        mHandlers[1] = new TestHandler(ht[1], 1);
        mMockSession = mockitoSession().mockStatic(QnsUtils.class).startMocking();
    }

    @After
    public void tearDown() {
        for (HandlerThread handlerThread : ht) {
            if (handlerThread != null) {
                handlerThread.quit();
            }
        }
        if (mIwlanNetworkStatusTracker != null) {
            mIwlanNetworkStatusTracker.unregisterIwlanNetworksChanged(0, mHandlers[0]);
            mIwlanNetworkStatusTracker.unregisterIwlanNetworksChanged(1, mHandlers[1]);
            mIwlanNetworkStatusTracker.close();
        }
        try {
            QnsEventDispatcher.getInstance(mockContext, 0).dispose();
            QnsProvisioningListener.getInstance(mockContext, 0).close();
            QnsEventDispatcher.getInstance(mockContext, 1).dispose();
            QnsProvisioningListener.getInstance(mockContext, 1).close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        mIwlanAvailabilityInfo = null;
        mMockSession.finishMocking();
    }

    @Test
    public void testHandleMessage_InvalidSubID() throws InterruptedException {
        // Wait for a while until initialized
        mHandlers[0].mLatch = new CountDownLatch(1);
        assertFalse(mHandlers[0].mLatch.await(100, TimeUnit.MILLISECONDS));

        // When call register, should get an event immediately
        mHandlers[0].mLatch = new CountDownLatch(2);
        mIwlanNetworkStatusTracker.registerIwlanNetworksChanged(0, mHandlers[0], 1);
        mHandlers[0].mLatch.await(200, TimeUnit.MILLISECONDS);

        // If sim is invalid, no event is notified because of already info was notified.
        mHandlers[0].mLatch = new CountDownLatch(1);
        studSubIdToNetworkCapabilities(INVALID_SUB_ID);
        mIwlanNetworkStatusTracker.onCrossSimEnabledEvent(true, 0);
        assertFalse(mHandlers[0].mLatch.await(200, TimeUnit.MILLISECONDS));
        assertNotNull(mIwlanAvailabilityInfo);
        assertFalse(mIwlanAvailabilityInfo.isCrossWfc());
    }

    @Test
    public void testHandleMessage_ValidSubID() throws InterruptedException {
        int dds = 1, currentSlot = 0;
        mHandlers[0].mLatch = new CountDownLatch(3);
        lenient().when(QnsUtils.getSubId(mockContext, currentSlot)).thenReturn(0);
        lenient()
                .when(QnsUtils.isCrossSimCallingEnabled(mockContext, currentSlot))
                .thenReturn(true);
        lenient().when(QnsUtils.isDefaultDataSubs(currentSlot)).thenReturn(false);
        mIwlanNetworkStatusTracker.registerIwlanNetworksChanged(currentSlot, mHandlers[0], 1);
        mHandlers[0].mLatch.await(200, TimeUnit.MILLISECONDS);
        studSubIdToNetworkCapabilities(dds);
        mIwlanNetworkStatusTracker.onCrossSimEnabledEvent(true, currentSlot);
        // Ignore countdown as it may differ due to multiple threads in IwlanNetworkStatusTracker.
        mHandlers[0].mLatch.await(300, TimeUnit.MILLISECONDS);
        assertNotNull(mIwlanAvailabilityInfo);
        assertTrue(mIwlanAvailabilityInfo.isCrossWfc());
    }

    private void studSubIdToNetworkCapabilities(int subId) {
        TelephonyNetworkSpecifier tns = new TelephonyNetworkSpecifier(subId);
        mNetworkCapabilities =
                new NetworkCapabilities.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                        .setNetworkSpecifier(tns)
                        .build();
        when(mockConnectivityManager.getActiveNetwork()).thenReturn(mockNetwork);
        when(mockConnectivityManager.getNetworkCapabilities(mockNetwork))
                .thenReturn(mNetworkCapabilities);
    }

    @Test
    public void testHandleMessage_DisableCrossSim() throws InterruptedException {
        int currentSlot = 0;
        testHandleMessage_ValidSubID();
        mHandlers[0].mLatch = new CountDownLatch(1);
        lenient().when(QnsUtils.getSubId(mockContext, currentSlot)).thenReturn(0);
        lenient()
                .when(QnsUtils.isCrossSimCallingEnabled(mockContext, currentSlot))
                .thenReturn(false);
        lenient().when(QnsUtils.isDefaultDataSubs(currentSlot)).thenReturn(false);
        mIwlanNetworkStatusTracker.onCrossSimEnabledEvent(false, 0);
        assertTrue(mHandlers[0].mLatch.await(100, TimeUnit.MILLISECONDS));
        assertNotNull(mIwlanAvailabilityInfo);
        assertFalse(mIwlanAvailabilityInfo.isCrossWfc());
    }

    private ConnectivityManager.NetworkCallback setupNetworkCallback() {
        ArgumentCaptor<ConnectivityManager.NetworkCallback> callbackArg =
                ArgumentCaptor.forClass(ConnectivityManager.NetworkCallback.class);
        verify(mockConnectivityManager, atLeastOnce())
                .registerDefaultNetworkCallback(callbackArg.capture(), isA(Handler.class));
        ConnectivityManager.NetworkCallback networkCallback = callbackArg.getValue();
        assertNotNull(networkCallback);
        return networkCallback;
    }

    @Test
    public void testDefaultNetworkCallback_Wifi() throws InterruptedException {
        testDefaultNetworkCallback(true);
    }

    @Test
    public void testDefaultNetworkCallback_Cellular() throws InterruptedException {
        testDefaultNetworkCallback(false);
    }

    public void testDefaultNetworkCallback(boolean isWifi) throws InterruptedException {
        mHandlers[0].mLatch = new CountDownLatch(4);
        mIwlanNetworkStatusTracker.registerIwlanNetworksChanged(0, mHandlers[0], 1);
        ConnectivityManager.NetworkCallback networkCallback = setupNetworkCallback();
        TelephonyNetworkSpecifier tns = new TelephonyNetworkSpecifier(0);
        mNetworkCapabilities =
                new NetworkCapabilities.Builder()
                        .addTransportType(
                                isWifi
                                        ? NetworkCapabilities.TRANSPORT_WIFI
                                        : NetworkCapabilities.TRANSPORT_CELLULAR)
                        .setNetworkSpecifier(tns)
                        .build();
        when(mockConnectivityManager.getNetworkCapabilities(mockNetwork))
                .thenReturn(mNetworkCapabilities);
        mHandlers[0].mLatch.await(100, TimeUnit.MILLISECONDS);
        networkCallback.onAvailable(mockNetwork);
        // Ignore countdown as it may differ due to multiple threads in IwlanNetworkStatusTracker.
        //Wait for 100ms to receive report from onAvailable callback.
        mHandlers[0].mLatch.await(100, TimeUnit.MILLISECONDS);
        verifyIwlanAvailabilityInfo(isWifi);

        mHandlers[0].mLatch = new CountDownLatch(1);
        networkCallback.onCapabilitiesChanged(mockNetwork, mNetworkCapabilities);
        // no callback is expected since onAvailable already reported information
        mHandlers[0].mLatch.await(100, TimeUnit.MILLISECONDS);
        verifyIwlanAvailabilityInfo(isWifi);
    }

    private void verifyIwlanAvailabilityInfo(boolean isWifi) {
        assertNotNull(mIwlanAvailabilityInfo);
        if (isWifi) {
            assertTrue(mIwlanAvailabilityInfo.getIwlanAvailable());
        } else {
            assertFalse(mIwlanAvailabilityInfo.getIwlanAvailable());
        }
        assertFalse(mIwlanAvailabilityInfo.isCrossWfc());
    }

    @Test
    public void testDefaultNetworkCallback_onLost() throws InterruptedException {
        testDefaultNetworkCallback(true);
        mHandlers[0].mLatch = new CountDownLatch(1);
        ConnectivityManager.NetworkCallback networkCallback = setupNetworkCallback();
        networkCallback.onLost(mockNetwork);
        assertTrue(mHandlers[0].mLatch.await(100, TimeUnit.MILLISECONDS));
        assertNotNull(mIwlanAvailabilityInfo);
        assertFalse(mIwlanAvailabilityInfo.getIwlanAvailable());
    }

    @Test
    public void testRegisterIwlanNetworksChanged() throws Exception {
        mHandlers[0].mLatch = new CountDownLatch(2);
        mIwlanNetworkStatusTracker.registerIwlanNetworksChanged(0, mHandlers[0], 1);
        // Ignore countdown as it may differ due to multiple threads in IwlanNetworkStatusTracker.
        mHandlers[0].mLatch.await(100, TimeUnit.MILLISECONDS);
        assertNotNull(mIwlanAvailabilityInfo);
        assertFalse(mIwlanAvailabilityInfo.getIwlanAvailable());
        assertFalse(mIwlanAvailabilityInfo.isCrossWfc());
    }

    @Test
    public void testUnregisterIwlanNetworksChanged() throws InterruptedException {
        mHandlers[0].mLatch = new CountDownLatch(3);
        mIwlanNetworkStatusTracker.registerIwlanNetworksChanged(0, mHandlers[0], 1);
        ConnectivityManager.NetworkCallback networkCallback = setupNetworkCallback();
        TelephonyNetworkSpecifier tns = new TelephonyNetworkSpecifier(0);
        mNetworkCapabilities =
                new NetworkCapabilities.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .setNetworkSpecifier(tns)
                        .build();
        when(mockConnectivityManager.getNetworkCapabilities(mockNetwork))
                .thenReturn(mNetworkCapabilities);

        mIwlanNetworkStatusTracker.unregisterIwlanNetworksChanged(0, mHandlers[0]);
        networkCallback.onAvailable(mockNetwork);
        assertFalse(mHandlers[0].mLatch.await(100, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testIsInternationalRoaming() throws Exception {
        boolean isInternationalRoaming;
        when(mockSharedPreferences.getString(any(), any())).thenReturn("US");
        isInternationalRoaming =
                mIwlanNetworkStatusTracker.isInternationalRoaming(mockContext, anyInt());
        assertTrue(isInternationalRoaming);

        when(mockSharedPreferences.getString(any(), any())).thenReturn("CA");
        isInternationalRoaming =
                mIwlanNetworkStatusTracker.isInternationalRoaming(mockContext, anyInt());
        assertFalse(isInternationalRoaming);
    }

    @Test
    public void testWifiDisabling() throws InterruptedException {
        testDefaultNetworkCallback(true);
        mHandlers[0].mLatch = new CountDownLatch(1);
        mIwlanNetworkStatusTracker.onWifiDisabling();
        mHandlers[0].mLatch.await(100, TimeUnit.MILLISECONDS);
        assertNotNull(mIwlanAvailabilityInfo);
        assertFalse(mIwlanAvailabilityInfo.getIwlanAvailable());
    }
}
