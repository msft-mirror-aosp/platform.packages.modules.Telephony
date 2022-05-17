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

import android.content.Context;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.telephony.AccessNetworkConstants;
import android.telephony.data.ApnSetting;
import android.telephony.ims.ImsException;
import android.telephony.ims.ImsManager;
import android.telephony.ims.ImsMmTelManager;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.ImsRegistrationAttributes;
import android.telephony.ims.RegistrationManager;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

public class ImsStatusListener {
    private static final int EVENT_DATA_CONNECTION_STATE_CHANGED = 7001;
    private final String TAG;
    private final Context mContext;
    private final int mSlotIndex;
    private Executor mExecutor;
    private HandlerThread mHandlerThread;
    @VisibleForTesting protected Handler mHandler;
    private static Map<Integer, ImsStatusListener> sImsStatusListeners = new ConcurrentHashMap<>();
    private RegistrantList mImsRegistrationStatusListeners = new RegistrantList();
    private DataConnectionStatusTracker mDataConnectionStatusTracker;
    private boolean mCallbackRegistered;
    private int mSubId = QnsConstants.INVALID_SUB_ID;
    private ImsRegistrationChangedEv mLastImsRegStatus;

    static class ImsRegistrationChangedEv {
        @QnsConstants.QnsImsRegiEvent private final int mEvent;
        @AccessNetworkConstants.TransportType private final int mTransportType;
        private final ImsReasonInfo mReasonInfo;

        ImsRegistrationChangedEv(int event, int transportType, ImsReasonInfo reason) {
            mEvent = event;
            mTransportType = transportType;
            mReasonInfo = reason;
        }

        int getEvent() {
            return mEvent;
        }

        int getTransportType() {
            return mTransportType;
        }

        ImsReasonInfo getReasonInfo() {
            return mReasonInfo;
        }

        public String toString() {
            String reason = mReasonInfo == null ? "null" : mReasonInfo.toString();
            return "ImsRegistrationChangedEv["
                    + AccessNetworkConstants.transportTypeToString(mTransportType)
                    + "] "
                    + "Event:"
                    + mEvent
                    + " reason:"
                    + reason;
        }
    }

    @VisibleForTesting
    final RegistrationManager.RegistrationCallback mImsRegistrationCallback =
            new RegistrationManager.RegistrationCallback() {
                @Override
                public void onRegistered(ImsRegistrationAttributes attribute) {
                    int transportType = attribute.getTransportType();
                    Log.d(
                            TAG,
                            "on IMS registered on :"
                                    + AccessNetworkConstants.transportTypeToString(transportType));
                    notifyImsRegistrationChangedEvent(
                            QnsConstants.IMS_REGISTRATION_CHANGED_REGISTERED, transportType, null);
                }

                @Override
                public void onTechnologyChangeFailed(int transportType, ImsReasonInfo reason) {
                    Log.d(
                            TAG,
                            "onTechnologyChangeFailed["
                                    + AccessNetworkConstants.transportTypeToString(transportType)
                                    + "] "
                                    + reason.toString());
                    notifyImsRegistrationChangedEvent(
                            QnsConstants.IMS_REGISTRATION_CHANGED_ACCESS_NETWORK_CHANGE_FAILED,
                            transportType,
                            reason);
                }

                @Override
                public void onUnregistered(ImsReasonInfo reason) {
                    Log.d(TAG, "onUnregistered " + reason.toString());
                    notifyImsRegistrationChangedEvent(
                            QnsConstants.IMS_REGISTRATION_CHANGED_UNREGISTERED,
                            AccessNetworkConstants.TRANSPORT_TYPE_INVALID,
                            reason);
                }
            };

    @VisibleForTesting
    void notifyImsRegistrationChangedEvent(int event, int transportType, ImsReasonInfo reason) {
        mLastImsRegStatus = new ImsRegistrationChangedEv(event, transportType, reason);
        mImsRegistrationStatusListeners.notifyResult(mLastImsRegStatus);
    }

    boolean isImsRegistered(int transportType) {
        if (mLastImsRegStatus != null
                && mLastImsRegStatus.getTransportType() == transportType
                && mLastImsRegStatus.getEvent()
                        == QnsConstants.IMS_REGISTRATION_CHANGED_REGISTERED) {
            return true;
        }
        return false;
    }

    class ImsStatusListenerHandler extends Handler {
        public ImsStatusListenerHandler(Looper l) {
            super(l);
        }

        @Override
        public void handleMessage(Message message) {
            Log.d(TAG, "handleMessage msg=" + message.what);
            AsyncResult ar = (AsyncResult) message.obj;
            switch (message.what) {
                case EVENT_DATA_CONNECTION_STATE_CHANGED:
                    onDataConnectionStateChanged(
                            (DataConnectionStatusTracker.DataConnectionChangedInfo) ar.result);
                    break;
                default:
                    Log.d(TAG, "never reach here msg=" + message.what);
            }
        }
    }

    private ImsStatusListener(Context context, int slotIndex) {
        TAG =
                QnsConstants.QNS_TAG
                        + "_"
                        + ImsStatusListener.class.getSimpleName()
                        + "_"
                        + slotIndex;
        mContext = context;
        mSlotIndex = slotIndex;
        mHandlerThread = new HandlerThread(ImsStatusListener.class.getSimpleName() + slotIndex);
        mHandlerThread.start();
        mHandler = new ImsStatusListenerHandler(mHandlerThread.getLooper());
        mExecutor = new HandlerExecutor(mHandler);
        mDataConnectionStatusTracker =
                new DataConnectionStatusTracker(
                        context, mHandlerThread.getLooper(), slotIndex, ApnSetting.TYPE_IMS);
        mDataConnectionStatusTracker.registerDataConnectionStatusChanged(
                mHandler, EVENT_DATA_CONNECTION_STATE_CHANGED);
    }

    public static ImsStatusListener getInstance(Context context, int slotIndex) {
        return sImsStatusListeners.computeIfAbsent(
                slotIndex, k -> new ImsStatusListener(context, slotIndex));
    }

    private void onDataConnectionStateChanged(
            DataConnectionStatusTracker.DataConnectionChangedInfo info) {
        Log.d(TAG, "onDataConnectionStateChanged info:" + info);
        switch (info.getEvent()) {
            case DataConnectionStatusTracker.EVENT_DATA_CONNECTION_CONNECTED:
                registerImsRegistrationCallback(mContext, mSlotIndex, mExecutor);
                break;
            case DataConnectionStatusTracker.EVENT_DATA_CONNECTION_DISCONNECTED:
                unregisterImsRegistrationCallback(mContext, mSubId);
                break;
        }
    }

    private void registerImsRegistrationCallback(Context context, int slotIndex, Executor ex) {
        if (!mCallbackRegistered) {
            Log.d(TAG, "ImsRegistrationCallback is not registered, register.");
            try {
                ImsManager imsMngr = context.getSystemService(ImsManager.class);
                if (imsMngr == null) {
                    Log.e(TAG, "ImsRegistrationCallback can't get imsManager.");
                    return;
                }
                int subId = QnsUtils.getSubId(context, slotIndex);
                ImsMmTelManager mmTelManager = imsMngr.getImsMmTelManager(subId);
                if (mmTelManager == null) {
                    Log.e(TAG, "ImsRegistrationCallback can't get mmTelManager.");
                    return;
                }
                mmTelManager.registerImsRegistrationCallback(ex, mImsRegistrationCallback);
                Log.d(TAG, "ImsRegistrationCallback registered slotIndex:" + slotIndex);
                mCallbackRegistered = true;
                mSubId = subId;
            } catch (ImsException e) {
                Log.d(TAG, "registerImsRegistrationCallback no ImsService:" + e);
                mCallbackRegistered = false;
            } catch (IllegalArgumentException e) {
                Log.d(
                        TAG,
                        "registerImsRegistrationCallback: registration failed, invalid"
                                + " subscription, Exception"
                                + e.getMessage());
                mCallbackRegistered = false;
            }
        }
    }

    private void unregisterImsRegistrationCallback(Context context, int subId) {
        if (mCallbackRegistered) {
            try {
                Log.d(TAG, "unregisterImsRegistrationCallback.");
                ImsManager imsMngr = context.getSystemService(ImsManager.class);
                ImsMmTelManager mmTelManager = imsMngr.getImsMmTelManager(subId);
                mmTelManager.unregisterImsRegistrationCallback(mImsRegistrationCallback);
                mCallbackRegistered = false;
            } catch (Exception e) {
                Log.d(TAG, "unregisterImsRegistrationCallback exception:" + e);
            }
        }
        mLastImsRegStatus = null;
    }

    void close() {
        unregisterImsRegistrationCallback(mContext, mSubId);
        mDataConnectionStatusTracker.unRegisterDataConnectionStatusChanged(mHandler);
        mHandlerThread.quitSafely();
        sImsStatusListeners.remove(mSlotIndex);
    }

    void registerImsRegistrationStatusChanged(Handler h, int what) {
        Registrant r = new Registrant(h, what, null);
        mImsRegistrationStatusListeners.add(r);
    }

    void unregisterImsRegistrationStatusChanged(Handler h) {
        mImsRegistrationStatusListeners.remove(h);
        if (mImsRegistrationStatusListeners.size() == 0) {
            Log.d(TAG, "unregisterImsRegistrationStatusChanged no more registrant. close.");
            close();
        }
    }
}
