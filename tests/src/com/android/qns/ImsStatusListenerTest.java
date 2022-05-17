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

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.os.AsyncResult;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.test.TestLooper;
import android.telephony.AccessNetworkConstants;
import android.telephony.ims.ImsException;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.ImsRegistrationAttributes;
import android.telephony.ims.RegistrationManager;
import android.telephony.ims.stub.ImsRegistrationImplBase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@RunWith(JUnit4.class)
public class ImsStatusListenerTest extends QnsTest {
    private static final int EVENT_DATA_CONNECTION_STATE_CHANGED = 7001;
    private ImsStatusListener mImsStatusListener;
    private Handler mHandler;
    private TestLooper mTestLooper;
    private CountDownLatch mLatch;

    private final HandlerThread mHandlerThread =
            new HandlerThread("") {
                @Override
                protected void onLooperPrepared() {
                    super.onLooperPrepared();
                    mImsStatusListener = ImsStatusListener.getInstance(sMockContext, 0);
                    setReady(true);
                }
            };

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        super.setUp();
        mHandlerThread.start();
        waitUntilReady();
        mTestLooper = new TestLooper();
        mHandler = new Handler(mTestLooper.getLooper());
        mLatch = new CountDownLatch(1);
    }

    @After
    public void tearDown() throws Exception {
        mImsStatusListener.close();
    }

    @Test
    public void testUnregisterImsRegistrationStatusChanged() {
        testNotifyImsRegistrationChangedEventWithOnTechnologyChangeFailed();
        ImsReasonInfo reasonInfo =
                new ImsReasonInfo(
                        ImsReasonInfo.CODE_LOCAL_NOT_REGISTERED, ImsReasonInfo.CODE_UNSPECIFIED);
        mImsStatusListener.unregisterImsRegistrationStatusChanged(mHandler);
        mImsStatusListener.notifyImsRegistrationChangedEvent(
                QnsConstants.IMS_REGISTRATION_CHANGED_ACCESS_NETWORK_CHANGE_FAILED,
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                reasonInfo);
        assertNull(mTestLooper.nextMessage());
    }

    /*
     * This methods covers tests for registerImsRegistrationStatusChanged() along with
     * notifyImsRegistrationChangedEvent()
     **/
    @Test
    public void testNotifyImsRegistrationChangedEventWithOnTechnologyChangeFailed() {
        ImsReasonInfo reasonInfo =
                new ImsReasonInfo(
                        ImsReasonInfo.CODE_LOCAL_NOT_REGISTERED, ImsReasonInfo.CODE_UNSPECIFIED);
        mImsStatusListener.registerImsRegistrationStatusChanged(mHandler, 1);
        mImsStatusListener.mImsRegistrationCallback.onTechnologyChangeFailed(
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN, reasonInfo);
        Message msg = mTestLooper.nextMessage();
        assertNotNull(msg);
        assertNotNull(msg.obj);
        AsyncResult ar = (AsyncResult) msg.obj;
        assertNotNull(ar);
        ImsStatusListener.ImsRegistrationChangedEv bean =
                (ImsStatusListener.ImsRegistrationChangedEv) ar.result;

        assertNotNull(bean);
        assertEquals(QnsConstants.IMS_REGISTRATION_CHANGED_ACCESS_NETWORK_CHANGE_FAILED, msg.what);
        assertEquals(AccessNetworkConstants.TRANSPORT_TYPE_WWAN, bean.getTransportType());
        assertNotNull(bean.getReasonInfo());
        assertEquals(ImsReasonInfo.CODE_LOCAL_NOT_REGISTERED, bean.getReasonInfo().getCode());
        assertEquals(ImsReasonInfo.CODE_UNSPECIFIED, bean.getReasonInfo().getExtraCode());
    }

    @Test
    public void testNotifyImsRegistrationChangedEventOnUnregistered() {
        ImsReasonInfo reasonInfo =
                new ImsReasonInfo(
                        ImsReasonInfo.CODE_LOCAL_NOT_REGISTERED, ImsReasonInfo.CODE_UNSPECIFIED);
        mImsStatusListener.registerImsRegistrationStatusChanged(mHandler, 0);
        mImsStatusListener.mImsRegistrationCallback.onUnregistered(reasonInfo);
        Message msg = mTestLooper.nextMessage();
        assertNotNull(msg);
        assertNotNull(msg.obj);
        AsyncResult ar = (AsyncResult) msg.obj;
        assertNotNull(ar);
        ImsStatusListener.ImsRegistrationChangedEv bean =
                (ImsStatusListener.ImsRegistrationChangedEv) ar.result;

        assertNotNull(bean);
        assertEquals(QnsConstants.IMS_REGISTRATION_CHANGED_UNREGISTERED, msg.what);
        assertEquals(AccessNetworkConstants.TRANSPORT_TYPE_INVALID, bean.getTransportType());
        assertNotNull(bean.getReasonInfo());
        assertEquals(ImsReasonInfo.CODE_LOCAL_NOT_REGISTERED, bean.getReasonInfo().getCode());
        assertEquals(ImsReasonInfo.CODE_UNSPECIFIED, bean.getReasonInfo().getExtraCode());
    }

    @Test
    public void testNotifyImsRegistrationChangedEventOnRegistered() {
        ImsRegistrationAttributes attr =
                new ImsRegistrationAttributes.Builder(ImsRegistrationImplBase.REGISTRATION_TECH_LTE)
                        .build();
        mImsStatusListener.registerImsRegistrationStatusChanged(mHandler, 2);
        mImsStatusListener.mImsRegistrationCallback.onRegistered(attr);
        Message msg = mTestLooper.nextMessage();
        assertNotNull(msg);
        assertNotNull(msg.obj);
        AsyncResult ar = (AsyncResult) msg.obj;
        assertNotNull(ar);
        ImsStatusListener.ImsRegistrationChangedEv bean =
                (ImsStatusListener.ImsRegistrationChangedEv) ar.result;
        assertNotNull(bean);
        assertEquals(QnsConstants.IMS_REGISTRATION_CHANGED_REGISTERED, msg.what);
        assertEquals(AccessNetworkConstants.TRANSPORT_TYPE_WWAN, bean.getTransportType());
    }

    @Test
    public void testRegisterImsRegistrationCallback() throws ImsException, InterruptedException {
        AsyncResult asyncResult =
                new AsyncResult(
                        null,
                        new DataConnectionStatusTracker.DataConnectionChangedInfo(
                                DataConnectionStatusTracker.EVENT_DATA_CONNECTION_CONNECTED,
                                DataConnectionStatusTracker.STATE_CONNECTED,
                                AccessNetworkConstants.TRANSPORT_TYPE_WWAN),
                        null);
        Message.obtain(
                        mImsStatusListener.mHandler,
                        EVENT_DATA_CONNECTION_STATE_CHANGED,
                        asyncResult)
                .sendToTarget();
        mLatch.await(2, TimeUnit.SECONDS);
        verify(mockImsMmTelManager)
                .registerImsRegistrationCallback(
                        any(Executor.class), any(RegistrationManager.RegistrationCallback.class));
    }

    @Test
    public void testUnregisterImsRegistrationCallback() throws ImsException, InterruptedException {
        testRegisterImsRegistrationCallback();
        AsyncResult asyncResult =
                new AsyncResult(
                        null,
                        new DataConnectionStatusTracker.DataConnectionChangedInfo(
                                DataConnectionStatusTracker.EVENT_DATA_CONNECTION_DISCONNECTED,
                                DataConnectionStatusTracker.STATE_INACTIVE,
                                AccessNetworkConstants.TRANSPORT_TYPE_WWAN),
                        null);
        Message.obtain(
                        mImsStatusListener.mHandler,
                        EVENT_DATA_CONNECTION_STATE_CHANGED,
                        asyncResult)
                .sendToTarget();
        mLatch.await(2, TimeUnit.SECONDS);
        verify(mockImsMmTelManager)
                .unregisterImsRegistrationCallback(
                        any(RegistrationManager.RegistrationCallback.class));
    }
}
