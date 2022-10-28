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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.isA;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.net.ConnectivityManager.NetworkCallback;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.test.TestLooper;
import android.telephony.AccessNetworkConstants;

import com.android.dx.mockito.inline.extended.StaticMockitoSession;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

public class WifiBackhaulMonitorTest extends QnsTest {

    private static final int EVENT_START_RTT_CHECK = 1;
    private static final int EVENT_IMS_REGISTRATION_STATE_CHANGED = 2;

    @Mock private QnsCarrierConfigManager mMockConfigManager;
    @Mock private QnsImsManager mMockQnsImsManager;
    @Mock private Network mMockNetwork;
    @Mock private Process mProcessV4;
    @Mock private Process mProcessV6;
    @Mock private Runtime mRuntime;

    private WifiBackhaulMonitor mWbm;
    private int mSlotIndex = 0;

    private TestLooper mTestLooper;
    private Handler mHandler;
    private StaticMockitoSession mMockitoSession;
    private LinkProperties mLinkProperties = new LinkProperties();
    private String mServerAddress;
    private int[] mRttConfigs;

    HandlerThread mHt =
            new HandlerThread("") {
                @Override
                protected void onLooperPrepared() {
                    super.onLooperPrepared();
                    mWbm = WifiBackhaulMonitor.getInstance(sMockContext, mSlotIndex);
                    setReady(true);
                }
            };
    private NetworkCallback mCallback;
    private Handler mRttHandler;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        super.setUp();

        mServerAddress = "";
        mRttConfigs = null;
        mLinkProperties.setInterfaceName("iwlan0");

        mMockitoSession =
                mockitoSession()
                        .mockStatic(QnsCarrierConfigManager.class)
                        .mockStatic(QnsImsManager.class)
                        .mockStatic(Runtime.class)
                        .spyStatic(InetAddress.class)
                        .startMocking();
        mockDefaults();
        mHt.start();
        waitUntilReady();
        mTestLooper = new TestLooper();
        mHandler = new Handler(mTestLooper.getLooper());
    }

    private void mockDefaults() throws IOException {
        lenient()
                .when(QnsCarrierConfigManager.getInstance(sMockContext, mSlotIndex))
                .thenReturn(mMockConfigManager);
        lenient()
                .when(QnsImsManager.getInstance(sMockContext, mSlotIndex))
                .thenReturn(mMockQnsImsManager);

        lenient().when(Runtime.getRuntime()).thenReturn(mRuntime);

        InetAddress[] inetAddresses = new InetAddress[2];
        inetAddresses[0] = InetAddress.getByAddress(new byte[] {0, 0, 0, 0});
        inetAddresses[1] =
                InetAddress.getByAddress(
                        new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        lenient().when(InetAddress.getAllByName(anyString())).thenReturn(inetAddresses);

        doReturn(mLinkProperties).when(mMockConnectivityManager).getLinkProperties(mMockNetwork);
        when(mRuntime.exec(anyString()))
                .thenAnswer(
                   (Answer<Process>)
                        invocation -> {
                            String arg = invocation.getArgument(0);
                            if (arg.startsWith("ping6")) {
                                return mProcessV6;
                            }
                            return mProcessV4;
                        });
        doAnswer(ret -> mServerAddress).when(mMockConfigManager).getWlanRttServerAddressConfig();
        doAnswer(invocation -> mRttConfigs).when(mMockConfigManager).getWlanRttOtherConfigs();
    }

    @After
    public void tearDown() throws Exception {
        mWbm.close();
        mMockitoSession.finishMocking();
    }

    @Test
    public void testIsRttCheckEnabled() {
        mServerAddress = null;
        assertFalse(mWbm.isRttCheckEnabled());

        mServerAddress = "";
        assertTrue(mWbm.isRttCheckEnabled());
    }

    @Test
    public void registerForRttStatusChange() {
        Handler handler = new Handler(mTestLooper.getLooper());

        mWbm.registerForRttStatusChange(mHandler, 1);
        mWbm.registerForRttStatusChange(handler, 2);

        verify(mMockQnsImsManager, times(1))
                .registerImsRegistrationStatusChanged(isA(Handler.class), anyInt());
        verify(mMockConnectivityManager, times(1))
                .registerNetworkCallback(isA(NetworkRequest.class), isA(NetworkCallback.class));

        mWbm.unRegisterForRttStatusChange(mHandler);
        mWbm.unRegisterForRttStatusChange(handler);

        verify(mMockQnsImsManager, times(1))
                .unregisterImsRegistrationStatusChanged(isA(Handler.class));
        verify(mMockConnectivityManager, times(1))
                .unregisterNetworkCallback(isA(NetworkCallback.class));
    }

    @Test
    public void testRttWhenNoWlanInterface() {
        mWbm.registerForRttStatusChange(mHandler, 1);
        mWbm.requestRttCheck();
        waitFor(50);
        verifyResultAs(false);
    }

    @Test
    public void testRttWhenNoConfigurations() {
        mWbm.registerForRttStatusChange(mHandler, 1);
        captureNetworkCallback();
        mCallback.onAvailable(mMockNetwork);
        mWbm.requestRttCheck();
        waitFor(50);
        verifyResultAs(true);
    }

    @Test
    public void testRttPassed_v4() {
        mRttConfigs = new int[] {5, 200, 32, 100, 60000};
        InputStream stream =
                new ByteArrayInputStream(getCommandOutputs()[0].getBytes(StandardCharsets.UTF_8));
        doReturn(stream).when(mProcessV4).getInputStream();

        mWbm.registerForRttStatusChange(mHandler, 1);
        captureNetworkCallback();
        mCallback.onAvailable(mMockNetwork);
        mWbm.requestRttCheck();
        waitFor(50);
        verifyResultAs(true);
    }

    @Test
    public void testRtt_v4Failed_v6Passed() {
        mRttConfigs = new int[] {5, 200, 32, 70, 60000};
        InputStream stream_v4 =
                new ByteArrayInputStream(getCommandOutputs()[0].getBytes(StandardCharsets.UTF_8));
        InputStream stream_v6 =
                new ByteArrayInputStream(getCommandOutputs()[1].getBytes(StandardCharsets.UTF_8));
        doReturn(stream_v4).when(mProcessV4).getInputStream();
        doReturn(stream_v6).when(mProcessV6).getInputStream();

        mWbm.registerForRttStatusChange(mHandler, 1);
        captureNetworkCallback();
        mCallback.onAvailable(mMockNetwork);
        mWbm.requestRttCheck();
        waitFor(50);
        verifyResultAs(true);
    }

    @Test
    public void testRtt_v4v6Failed() {
        mRttConfigs = new int[] {5, 200, 32, 60, 60000};
        InputStream stream_v4 =
                new ByteArrayInputStream(getCommandOutputs()[0].getBytes(StandardCharsets.UTF_8));
        InputStream stream_v6 =
                new ByteArrayInputStream(getCommandOutputs()[1].getBytes(StandardCharsets.UTF_8));
        doReturn(stream_v4).when(mProcessV4).getInputStream();
        doReturn(stream_v6).when(mProcessV6).getInputStream();

        mWbm.registerForRttStatusChange(mHandler, 1);
        captureNetworkCallback();
        mCallback.onAvailable(mMockNetwork);
        mWbm.requestRttCheck();
        waitFor(50);
        verifyResultAs(false);
    }

    @Test
    public void testRttSchedulingStart() {
        mRttConfigs = new int[] {5, 200, 32, 100, 60000};
        mWbm.registerForRttStatusChange(mHandler, 1);
        mRttHandler = captureHandler();
        captureNetworkCallback();
        mCallback.onAvailable(mMockNetwork);
        mWbm.setCellularAvailable(true);
        Message.obtain(
                        mRttHandler,
                        EVENT_IMS_REGISTRATION_STATE_CHANGED,
                        new QnsAsyncResult(
                                null,
                                new QnsImsManager.ImsRegistrationState(
                                        QnsConstants.IMS_REGISTRATION_CHANGED_REGISTERED,
                                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                                        null),
                                null))
                .sendToTarget();
        waitFor(100);
        assertTrue(mRttHandler.hasMessages(EVENT_START_RTT_CHECK));
    }

    @Test
    public void testRttSchedulingStop_WifiOff() {
        testRttSchedulingStart();
        mCallback.onLost(mMockNetwork);
        assertFalse(mRttHandler.hasMessages(EVENT_START_RTT_CHECK));
    }

    @Test
    public void testRttSchedulingStop_NoCellular() {
        testRttSchedulingStart();
        mWbm.setCellularAvailable(false);
        assertFalse(mRttHandler.hasMessages(EVENT_START_RTT_CHECK));
    }

    @Test
    public void testRttSchedulingStop_VoWifiDisconnected() {
        testRttSchedulingStart();
        Message.obtain(
                        mRttHandler,
                        EVENT_IMS_REGISTRATION_STATE_CHANGED,
                        new QnsAsyncResult(
                                null,
                                new QnsImsManager.ImsRegistrationState(
                                        QnsConstants.IMS_REGISTRATION_CHANGED_UNREGISTERED,
                                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                                        null),
                                null))
                .sendToTarget();
        waitFor(100);
        assertFalse(mRttHandler.hasMessages(EVENT_START_RTT_CHECK));
    }

    @Test
    public void testRttSchedulingStop_VoLTEConnected() {
        testRttSchedulingStart();
        Message.obtain(
                        mRttHandler,
                        EVENT_IMS_REGISTRATION_STATE_CHANGED,
                        new QnsAsyncResult(
                                null,
                                new QnsImsManager.ImsRegistrationState(
                                        QnsConstants.IMS_REGISTRATION_CHANGED_REGISTERED,
                                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                                        null),
                                null))
                .sendToTarget();
        waitFor(100);
        assertFalse(mRttHandler.hasMessages(EVENT_START_RTT_CHECK));
    }

    private void verifyResultAs(boolean expected) {
        Message msg = mTestLooper.nextMessage();
        assertNotNull(msg);
        assertTrue(msg.obj instanceof QnsAsyncResult);
        QnsAsyncResult ar = (QnsAsyncResult) msg.obj;
        assertEquals(expected, ar.mResult);
    }

    private String[] getCommandOutputs() {
        String[] outputs =
                new String[] {
                    "PING www.google.com (172.217.163.196) from 172.20.10.2 wlan0: 32(60) bytes of"
                            + " data.\n"
                            + "40 bytes from maa05s06-in-f4.1e100.net (172.217.163.196): icmp_seq=1"
                            + " ttl=53 time=119 ms\n"
                            + "40 bytes from maa05s06-in-f4.1e100.net (172.217.163.196): icmp_seq=2"
                            + " ttl=53 time=110 ms\n"
                            + "40 bytes from maa05s06-in-f4.1e100.net (172.217.163.196): icmp_seq=3"
                            + " ttl=53 time=66.8 ms\n"
                            + "40 bytes from maa05s06-in-f4.1e100.net (172.217.163.196): icmp_seq=4"
                            + " ttl=53 time=63.7 ms\n"
                            + "40 bytes from maa05s06-in-f4.1e100.net (172.217.163.196): icmp_seq=5"
                            + " ttl=53 time=49.9 ms\n"
                            + "\n"
                            + "--- www.google.com ping statistics ---\n"
                            + "5 packets transmitted, 5 received, 0% packet loss, time 805ms\n"
                            + "rtt min/avg/max/mdev = 49.952/80.066/119.469/27.539 ms",
                    "PING maa05s21-in-x04.1e100.net(maa05s21-in-x04.1e100.net) 56 data bytes\n"
                            + "64 bytes from maa05s21-in-x04.1e100.net: icmp_seq=1 ttl=56 time=56.0"
                            + " ms\n"
                            + "64 bytes from maa05s21-in-x04.1e100.net: icmp_seq=2 ttl=56 time=54.3"
                            + " ms\n"
                            + "64 bytes from maa05s21-in-x04.1e100.net: icmp_seq=3 ttl=56 time=51.2"
                            + " ms\n"
                            + "64 bytes from maa05s21-in-x04.1e100.net: icmp_seq=4 ttl=56 time=89.2"
                            + " ms\n"
                            + "64 bytes from maa05s21-in-x04.1e100.net: icmp_seq=5 ttl=56 time=88.0"
                            + " ms\n"
                            + "\n"
                            + "--- maa05s21-in-x04.1e100.net ping statistics ---\n"
                            + "5 packets transmitted, 5 received, 0% packet loss, time 807ms\n"
                            + "rtt min/avg/max/mdev = 51.282/65.793/89.228/17.110 ms"
                };
        return outputs;
    }

    private void captureNetworkCallback() {
        ArgumentCaptor<NetworkCallback> argumentCaptor =
                ArgumentCaptor.forClass(NetworkCallback.class);
        verify(mMockConnectivityManager)
                .registerNetworkCallback(isA(NetworkRequest.class), argumentCaptor.capture());
        mCallback = argumentCaptor.getValue();
    }

    private Handler captureHandler() {
        ArgumentCaptor<Handler> argumentCaptor = ArgumentCaptor.forClass(Handler.class);
        verify(mMockQnsImsManager)
                .registerImsRegistrationStatusChanged(argumentCaptor.capture(), anyInt());
        return argumentCaptor.getValue();
    }
}
