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

package com.android.telephony.qns;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;

@RunWith(JUnit4.class)
public class CellularNetworkStatusTrackerTest extends QnsTest {

    private MockitoSession mMockitoSession;
    @Mock private QnsTelephonyListener mMockQnsTelephonyListener;
    private CellularNetworkStatusTracker mCellularNetworkStatusTracker;
    private final int mSlotIndex = 0;
    private Handler mHandler;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        super.setUp();
        mMockitoSession = mockitoSession().mockStatic(QnsTelephonyListener.class).startMocking();
        lenient()
                .when(QnsTelephonyListener.getInstance(sMockContext, mSlotIndex))
                .thenReturn(mMockQnsTelephonyListener);
        mCellularNetworkStatusTracker =
                CellularNetworkStatusTracker.getInstance(sMockContext, mSlotIndex);
        mHandler = new Handler(Looper.getMainLooper());
    }

    @After
    public void tearDown() throws Exception {
        mMockitoSession.finishMocking();
        mCellularNetworkStatusTracker.close();
    }

    @Test
    public void testGetInstance() {
        lenient()
                .when(QnsTelephonyListener.getInstance(sMockContext, mSlotIndex + 1))
                .thenReturn(mMockQnsTelephonyListener);

        CellularNetworkStatusTracker cnst_0 =
                CellularNetworkStatusTracker.getInstance(sMockContext, mSlotIndex);
        CellularNetworkStatusTracker cnst_1 =
                CellularNetworkStatusTracker.getInstance(sMockContext, mSlotIndex + 1);

        assertSame(cnst_0, mCellularNetworkStatusTracker);
        assertNotSame(cnst_1, mCellularNetworkStatusTracker);
        cnst_1.close();
    }

    @Test
    public void testRegisterQnsTelephonyInfoChanged() {
        mCellularNetworkStatusTracker.registerQnsTelephonyInfoChanged(
                NetworkCapabilities.NET_CAPABILITY_IMS, mHandler, 0);
        verify(mMockQnsTelephonyListener)
                .registerQnsTelephonyInfoChanged(
                        NetworkCapabilities.NET_CAPABILITY_IMS, mHandler, 0, null, true);

        mCellularNetworkStatusTracker.registerQnsTelephonyInfoChanged(
                NetworkCapabilities.NET_CAPABILITY_XCAP, mHandler, 1);
        verify(mMockQnsTelephonyListener)
                .registerQnsTelephonyInfoChanged(
                        NetworkCapabilities.NET_CAPABILITY_XCAP, mHandler, 1, null, true);

        mCellularNetworkStatusTracker.registerQnsTelephonyInfoChanged(
                NetworkCapabilities.NET_CAPABILITY_MMS, mHandler, 2);
        verify(mMockQnsTelephonyListener)
                .registerQnsTelephonyInfoChanged(
                        NetworkCapabilities.NET_CAPABILITY_MMS, mHandler, 2, null, true);

        mCellularNetworkStatusTracker.registerQnsTelephonyInfoChanged(
                NetworkCapabilities.NET_CAPABILITY_EIMS, mHandler, 0);
        verify(mMockQnsTelephonyListener)
                .registerQnsTelephonyInfoChanged(
                        NetworkCapabilities.NET_CAPABILITY_EIMS, mHandler, 0, null, true);
    }

    @Test
    public void testUnregisterQnsTelephonyInfoChanged() {
        testRegisterQnsTelephonyInfoChanged();
        mCellularNetworkStatusTracker.unregisterQnsTelephonyInfoChanged(
                NetworkCapabilities.NET_CAPABILITY_IMS, mHandler);
        verify(mMockQnsTelephonyListener)
                .unregisterQnsTelephonyInfoChanged(
                        NetworkCapabilities.NET_CAPABILITY_IMS, mHandler);

        mCellularNetworkStatusTracker.unregisterQnsTelephonyInfoChanged(
                NetworkCapabilities.NET_CAPABILITY_XCAP, mHandler);
        verify(mMockQnsTelephonyListener)
                .unregisterQnsTelephonyInfoChanged(
                        NetworkCapabilities.NET_CAPABILITY_XCAP, mHandler);

        mCellularNetworkStatusTracker.unregisterQnsTelephonyInfoChanged(
                NetworkCapabilities.NET_CAPABILITY_MMS, mHandler);
        verify(mMockQnsTelephonyListener)
                .unregisterQnsTelephonyInfoChanged(
                        NetworkCapabilities.NET_CAPABILITY_MMS, mHandler);

        mCellularNetworkStatusTracker.unregisterQnsTelephonyInfoChanged(
                NetworkCapabilities.NET_CAPABILITY_EIMS, mHandler);
        verify(mMockQnsTelephonyListener)
                .unregisterQnsTelephonyInfoChanged(
                        NetworkCapabilities.NET_CAPABILITY_EIMS, mHandler);
    }

    @Test
    public void testIsAirplaneModeEnabled() {
        when(mMockQnsTelephonyListener.isAirplaneModeEnabled()).thenReturn(true, false);
        assertTrue(mCellularNetworkStatusTracker.isAirplaneModeEnabled());
        assertFalse(mCellularNetworkStatusTracker.isAirplaneModeEnabled());
    }

    @Test
    public void testIsSupportVoPS() {
        when(mMockQnsTelephonyListener.isSupportVoPS()).thenReturn(true, false);
        assertTrue(mCellularNetworkStatusTracker.isSupportVoPS());
        assertFalse(mCellularNetworkStatusTracker.isSupportVoPS());
    }

    @Test
    public void testIsVoiceBarring() {
        when(mMockQnsTelephonyListener.isVoiceBarring()).thenReturn(true, false);
        assertTrue(mCellularNetworkStatusTracker.isVoiceBarring());
        assertFalse(mCellularNetworkStatusTracker.isVoiceBarring());
    }
}
