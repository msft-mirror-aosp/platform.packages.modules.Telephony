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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.telephony.AccessNetworkConstants;
import android.telephony.PreciseDataConnectionState;
import android.telephony.TelephonyManager;
import android.telephony.data.ApnSetting;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;

public class DataConnectionStatusTracker {
    private static final int EVENT_DATA_CONNECTION_STATE_CHANGED = 11001;
    protected final Context mContext;
    protected final int mSlotIndex;
    private final String LOG_TAG;
    private final int mApnType;
    @VisibleForTesting protected final Handler mHandler;
    private DataConnectionChangedInfo mLastUpdatedDcChangedInfo;
    private final QnsTelephonyListener mQnsTelephonyListener;
    public static final int STATE_INACTIVE = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_CONNECTED = 2;
    public static final int STATE_HANDOVER = 3;

    @IntDef(
            value = {
                STATE_INACTIVE,
                STATE_CONNECTING,
                STATE_CONNECTED,
                STATE_HANDOVER,
            })
    public @interface DataConnectionState {}

    public static final int EVENT_DATA_CONNECTION_DISCONNECTED = 0;
    public static final int EVENT_DATA_CONNECTION_STARTED = 1;
    public static final int EVENT_DATA_CONNECTION_CONNECTED = 2;
    public static final int EVENT_DATA_CONNECTION_FAILED = 3;
    public static final int EVENT_DATA_CONNECTION_HANDOVER_STARTED = 4;
    public static final int EVENT_DATA_CONNECTION_HANDOVER_SUCCESS = 5;
    public static final int EVENT_DATA_CONNECTION_HANDOVER_FAILED = 6;

    @IntDef(
            value = {
                EVENT_DATA_CONNECTION_DISCONNECTED,
                EVENT_DATA_CONNECTION_STARTED,
                EVENT_DATA_CONNECTION_CONNECTED,
                EVENT_DATA_CONNECTION_FAILED,
                EVENT_DATA_CONNECTION_HANDOVER_STARTED,
                EVENT_DATA_CONNECTION_HANDOVER_SUCCESS,
                EVENT_DATA_CONNECTION_HANDOVER_FAILED,
            })
    public @interface DataConnectionChangedEvent {}

    private final QnsRegistrantList mDataConnectionStatusRegistrants;
    private int mState = STATE_INACTIVE;
    private int mDataConnectionFailCause;
    private int mTransportType = AccessNetworkConstants.TRANSPORT_TYPE_INVALID;
    private SparseArray<ApnSetting> mApnSettings = new SparseArray<>();

    public DataConnectionStatusTracker(
            @NonNull Context context, Looper looper, int slotIndex, int apnType) {
        LOG_TAG =
                QnsConstants.QNS_TAG
                        + "_"
                        + DataConnectionStatusTracker.class.getSimpleName()
                        + "_"
                        + slotIndex
                        + "_"
                        + QnsUtils.getStringApnTypes(apnType);

        mContext = context;
        mSlotIndex = slotIndex;
        mApnType = apnType;

        mHandler = new DataConnectionStatusTrackerHandler(looper);
        mDataConnectionStatusRegistrants = new QnsRegistrantList();
        mQnsTelephonyListener = QnsTelephonyListener.getInstance(context, slotIndex);
        mQnsTelephonyListener.registerPreciseDataConnectionStateChanged(
                mApnType, mHandler, EVENT_DATA_CONNECTION_STATE_CHANGED, null, true);
    }

    protected void log(String s) {
        Log.d(LOG_TAG, s);
    }

    public boolean isInactiveState() {
        return mState == STATE_INACTIVE;
    }

    public boolean isActiveState() {
        return mState == STATE_CONNECTED || mState == STATE_HANDOVER;
    }

    public boolean isHandoverState() {
        return mState == STATE_HANDOVER;
    }

    public boolean isConnectionInProgress() {
        return mState == STATE_CONNECTING || mState == STATE_HANDOVER;
    }

    public int getLastTransportType() {
        return mTransportType;
    }

    public int getLastFailCause() {
        return mDataConnectionFailCause;
    }

    /** Returns Latest APN setting for the transport type */
    public ApnSetting getLastApnSetting(int transportType) {
        try {
            return mApnSettings.get(transportType);
        } catch (Exception e) {
            return null;
        }
    }

    public void registerDataConnectionStatusChanged(Handler h, int what) {
        if (h != null) {
            mDataConnectionStatusRegistrants.addUnique(h, what, null);
        }
        if (mLastUpdatedDcChangedInfo != null) {
            QnsRegistrant r = new QnsRegistrant(h, what, null);
            r.notifyResult(mLastUpdatedDcChangedInfo);
        }
    }

    public void unRegisterDataConnectionStatusChanged(Handler h) {
        if (h != null) {
            mDataConnectionStatusRegistrants.remove(h);
        }
    }

    private void onDataConnectionStateChanged(PreciseDataConnectionState status) {
        int transportType = status.getTransportType();
        int state = status.getState();
        mDataConnectionFailCause = status.getLastCauseCode();
        log(
                "onDataConnectionChanged transportType:"
                        + QnsConstants.transportTypeToString(transportType)
                        + " state:"
                        + QnsConstants.dataStateToString(status.getState())
                        + " cause:"
                        + status.getLastCauseCode());

        switch (state) {
            case TelephonyManager.DATA_DISCONNECTED:
                if (mState == STATE_CONNECTED || mState == STATE_HANDOVER) {
                    mState = STATE_INACTIVE;
                    mTransportType = AccessNetworkConstants.TRANSPORT_TYPE_INVALID;
                    log("Connection Disconnected");
                    notifyDataConnectionStatusChangedEvent(EVENT_DATA_CONNECTION_DISCONNECTED);
                } else {
                    if (mState == STATE_CONNECTING) {
                        // Initial connect Failed.
                        mState = STATE_INACTIVE;
                        mTransportType = AccessNetworkConstants.TRANSPORT_TYPE_INVALID;
                        log("Initial connect failed");
                        notifyDataConnectionFailed(transportType);
                    }
                }
                break;

            case TelephonyManager.DATA_CONNECTING:
                if (mState == STATE_INACTIVE) {
                    mState = STATE_CONNECTING;
                    log(
                            "Initial Connect inited transport: "
                                    + QnsConstants.transportTypeToString(transportType));
                    notifyDataConnectionStarted(transportType);
                }
                break;

            case TelephonyManager.DATA_CONNECTED:
                if (mState == STATE_CONNECTING || mState == STATE_INACTIVE) {
                    mState = STATE_CONNECTED;
                    mTransportType = transportType;
                    log(
                            "Data Connected Transport: "
                                    + QnsConstants.transportTypeToString(mTransportType));
                    notifyDataConnectionStatusChangedEvent(EVENT_DATA_CONNECTION_CONNECTED);
                } else if (mState == STATE_HANDOVER && mTransportType != transportType) {
                    mState = STATE_CONNECTED;
                    mTransportType = transportType;
                    log(
                            "Handover completed to: "
                                    + QnsConstants.transportTypeToString(mTransportType));
                    notifyDataConnectionStatusChangedEvent(EVENT_DATA_CONNECTION_HANDOVER_SUCCESS);
                } else if (mState == STATE_HANDOVER && mTransportType == transportType) {
                    mState = STATE_CONNECTED;
                    log(
                            "Handover failed and return to: "
                                    + QnsConstants.transportTypeToString(mTransportType));
                    notifyDataConnectionStatusChangedEvent(EVENT_DATA_CONNECTION_HANDOVER_FAILED);
                }
                break;

            case TelephonyManager.DATA_SUSPENDED:
                if (mState == STATE_HANDOVER && mTransportType != transportType) {
                    mState = STATE_CONNECTED;
                    mTransportType = transportType;
                    log(
                            "QNS assumes Handover completed to: "
                                    + QnsConstants.transportTypeToString(mTransportType));
                    notifyDataConnectionStatusChangedEvent(EVENT_DATA_CONNECTION_HANDOVER_SUCCESS);
                }
                break;

            case TelephonyManager.DATA_HANDOVER_IN_PROGRESS:
                if (mState == STATE_CONNECTED && mTransportType == transportType) {
                    mState = STATE_HANDOVER;
                    log(
                            "Handover initiated from "
                                    + QnsConstants.transportTypeToString(transportType));
                    notifyDataConnectionStatusChangedEvent(EVENT_DATA_CONNECTION_HANDOVER_STARTED);
                } else {
                    log(
                            "Ignore STATE_HANDOVER since request is not for Src TransportType: "
                                    + QnsConstants.transportTypeToString(mTransportType));
                }
                break;

            default:
                break;
        }
        mApnSettings.put(mTransportType, status.getApnSetting());
    }

    private void notifyDataConnectionStarted(int transportType) {
        DataConnectionChangedInfo info =
                new DataConnectionChangedInfo(EVENT_DATA_CONNECTION_STARTED, mState, transportType);
        mLastUpdatedDcChangedInfo = info;
        mDataConnectionStatusRegistrants.notifyResult(info);
    }

    private void notifyDataConnectionFailed(int transportType) {
        DataConnectionChangedInfo info =
                new DataConnectionChangedInfo(EVENT_DATA_CONNECTION_FAILED, mState, transportType);
        mLastUpdatedDcChangedInfo = info;
        mDataConnectionStatusRegistrants.notifyResult(info);
    }

    private void notifyDataConnectionStatusChangedEvent(int event) {
        DataConnectionChangedInfo info =
                new DataConnectionChangedInfo(event, mState, mTransportType);
        mLastUpdatedDcChangedInfo = info;
        mDataConnectionStatusRegistrants.notifyResult(info);
    }

    public void close() {
        mQnsTelephonyListener.unregisterPreciseDataConnectionStateChanged(mApnType, mHandler);
        mDataConnectionStatusRegistrants.removeAll();
    }

    public static String stateToString(int state) {
        switch (state) {
            case STATE_INACTIVE:
                return "STATE_INCATIVE";
            case STATE_CONNECTING:
                return "STATE_CONNCTING";
            case STATE_CONNECTED:
                return "STATE_CONNECTED";
            case STATE_HANDOVER:
                return "STATE_HANDOVER";
        }
        return "INVALID";
    }

    public static String eventToString(int event) {
        switch (event) {
            case EVENT_DATA_CONNECTION_DISCONNECTED:
                return "EVENT_DATA_CONNECTION_DISCONNECTED";
            case EVENT_DATA_CONNECTION_STARTED:
                return "EVENT_DATA_CONNECTION_STARTED";
            case EVENT_DATA_CONNECTION_CONNECTED:
                return "EVENT_DATA_CONNECTION_CONNECTED";
            case EVENT_DATA_CONNECTION_FAILED:
                return "EVENT_DATA_CONNECTION_FAILED";
            case EVENT_DATA_CONNECTION_HANDOVER_STARTED:
                return "EVENT_DATA_CONNECTION_HANDOVER_STARTED";
            case EVENT_DATA_CONNECTION_HANDOVER_SUCCESS:
                return "EVENT_DATA_CONNECTION_HANDOVER_SUCCESS";
            case EVENT_DATA_CONNECTION_HANDOVER_FAILED:
                return "EVENT_DATA_CONNECTION_HANDOVER_FAILED";
        }
        return "INVALID";
    }

    public static class DataConnectionChangedInfo {
        private final int mEvent;
        private final int mState;
        private final int mCurrentTransportType;

        @Override
        public String toString() {
            return "DataConnectionChangedInfo{"
                    + "mEvent="
                    + eventToString(mEvent)
                    + ", mState="
                    + stateToString(mState)
                    + ", mCurrentTransportType="
                    + QnsConstants.transportTypeToString(mCurrentTransportType)
                    + '}';
        }

        public DataConnectionChangedInfo(
                @DataConnectionChangedEvent int event,
                @DataConnectionState int state,
                @AccessNetworkConstants.TransportType int transportType) {
            mEvent = event;
            mState = state;
            mCurrentTransportType = transportType;
        }

        public int getState() {
            return mState;
        }

        public int getEvent() {
            return mEvent;
        }

        public int getTransportType() {
            return mCurrentTransportType;
        }
    }

    class DataConnectionStatusTrackerHandler extends Handler {
        public DataConnectionStatusTrackerHandler(Looper l) {
            super(l);
        }

        @Override
        public void handleMessage(Message message) {
            log("handleMessage msg=" + message.what);
            QnsAsyncResult ar = (QnsAsyncResult) message.obj;
            switch (message.what) {
                case EVENT_DATA_CONNECTION_STATE_CHANGED:
                    onDataConnectionStateChanged((PreciseDataConnectionState) ar.result);
                    break;
                default:
                    log("never reach here msg=" + message.what);
            }
        }
    }
}
