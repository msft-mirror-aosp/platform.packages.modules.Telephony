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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.telephony.AccessNetworkConstants;
import android.telephony.DataFailCause;
import android.telephony.PreciseDataConnectionState;
import android.telephony.TelephonyManager;
import android.telephony.data.ApnSetting;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.util.TelephonyUtils;

public class DataConnectionStatusTracker {
    private static final int EVENT_DATA_CONNECTION_STATE_CHANGED = 11001;
    protected final Context mContext;
    protected final int mSlotIndex;
    private final String LOG_TAG;
    private final int mApnType;
    @VisibleForTesting protected final Handler mHandler;
    private DataConnectionChangedInfo mLastUpdatedDcChangedInfo;
    /*For internal test [s] This should be removed later.*/
    private boolean mTestMode = false;
    private int mTestHoFailMode = 0;
    /*For internal test [e] This should be removed later.*/
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

    private final RegistrantList mDataConnectionStatusRegistrants;
    private int mState = STATE_INACTIVE;
    private int mDataConnectionFailCause;
    private int mTransportType = AccessNetworkConstants.TRANSPORT_TYPE_INVALID;

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
        mDataConnectionStatusRegistrants = new RegistrantList();
        mQnsTelephonyListener = QnsTelephonyListener.getInstance(context, slotIndex);
        mQnsTelephonyListener.registerPreciseDataConnectionStateChanged(
                mApnType, mHandler, EVENT_DATA_CONNECTION_STATE_CHANGED, null, true);
        /*For internal test [s] This should be removed later.*/
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("android.intent.action.QNS_DC_CHANGED");
        mContext.registerReceiver(mIntentReceiver, intentFilter);
        /*For internal test [e]*/
    }

    protected void log(String s) {
        Log.d(LOG_TAG, s);
    }

    /*For internal test [s] This should be removed later.*/
    public void notifyTransportTypeChanged(int transportType) {
        if (!mTestMode) {
            return;
        }
        log(
                "notifyTransportTypeChanged target:"
                        + AccessNetworkConstants.transportTypeToString(transportType)
                        + " current:"
                        + AccessNetworkConstants.transportTypeToString(mTransportType));
        int prevTransportType = mTransportType;
        if (mState == STATE_CONNECTED && transportType != mTransportType) {
            PreciseDataConnectionState dc1 =
                    new PreciseDataConnectionState.Builder()
                            .setTransportType(transportType)
                            .setState(TelephonyManager.DATA_CONNECTING)
                            .setNetworkType(TelephonyManager.NETWORK_TYPE_LTE)
                            .build();
            AsyncResult ar1 = new AsyncResult(null, dc1, null);
            Message msg = mHandler.obtainMessage(EVENT_DATA_CONNECTION_STATE_CHANGED, ar1);
            mHandler.sendMessageDelayed(msg, 300);
            if (mTestHoFailMode == 0) {
                PreciseDataConnectionState dc2 =
                        new PreciseDataConnectionState.Builder()
                                .setTransportType(transportType)
                                .setState(TelephonyManager.DATA_CONNECTED)
                                .setNetworkType(TelephonyManager.NETWORK_TYPE_LTE)
                                .build();
                AsyncResult ar2 = new AsyncResult(null, dc2, null);
                Message msg2 = mHandler.obtainMessage(EVENT_DATA_CONNECTION_STATE_CHANGED, ar2);
                mHandler.sendMessageDelayed(msg2, 3300);

                PreciseDataConnectionState dc3 =
                        new PreciseDataConnectionState.Builder()
                                .setTransportType(prevTransportType)
                                .setState(TelephonyManager.DATA_DISCONNECTED)
                                .setNetworkType(TelephonyManager.NETWORK_TYPE_LTE)
                                .build();
                AsyncResult ar3 = new AsyncResult(null, dc3, null);
                Message msg3 = mHandler.obtainMessage(EVENT_DATA_CONNECTION_STATE_CHANGED, ar3);
                mHandler.sendMessageDelayed(msg3, 3800);
            } else {
                PreciseDataConnectionState dc4 =
                        new PreciseDataConnectionState.Builder()
                                .setTransportType(transportType)
                                .setFailCause(33)
                                .setState(TelephonyManager.DATA_DISCONNECTED)
                                .setNetworkType(TelephonyManager.NETWORK_TYPE_LTE)
                                .build();
                AsyncResult ar4 = new AsyncResult(null, dc4, null);
                Message msg2 = mHandler.obtainMessage(EVENT_DATA_CONNECTION_STATE_CHANGED, ar4);
                mHandler.sendMessageDelayed(msg2, 13300);
            }
        }
    }
    /*For internal test [e] */

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

    public void registerDataConnectionStatusChanged(Handler h, int what) {
        if (h != null) {
            mDataConnectionStatusRegistrants.addUnique(h, what, null);
        }
        if (mLastUpdatedDcChangedInfo != null) {
            Registrant r = new Registrant(h, what, null);
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
                        + AccessNetworkConstants.transportTypeToString(transportType)
                        + " state:"
                        + TelephonyUtils.dataStateToString(status.getState())
                        + " cause:"
                        + DataFailCause.toString(status.getLastCauseCode()));

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
                        notifyDataConnectionStatusChangedEvent(EVENT_DATA_CONNECTION_FAILED);
                    }
                }
                break;

            case TelephonyManager.DATA_CONNECTING:
                if (mState == STATE_INACTIVE) {
                    mState = STATE_CONNECTING;
                    log(
                            "Initial Connect inited transport: "
                                    + AccessNetworkConstants.transportTypeToString(transportType));
                    notifyDataConnectionStatusChangedEvent(EVENT_DATA_CONNECTION_STARTED);
                }
                break;

            case TelephonyManager.DATA_CONNECTED:
                if (mState == STATE_CONNECTING || mState == STATE_INACTIVE) {
                    mState = STATE_CONNECTED;
                    mTransportType = transportType;
                    log(
                            "Data Connected Transport: "
                                    + AccessNetworkConstants.transportTypeToString(mTransportType));
                    notifyDataConnectionStatusChangedEvent(EVENT_DATA_CONNECTION_CONNECTED);
                } else if (mState == STATE_HANDOVER && mTransportType != transportType) {
                    mState = STATE_CONNECTED;
                    mTransportType = transportType;
                    log(
                            "Handover completed to: "
                                    + AccessNetworkConstants.transportTypeToString(mTransportType));
                    notifyDataConnectionStatusChangedEvent(EVENT_DATA_CONNECTION_HANDOVER_SUCCESS);
                } else if (mState == STATE_HANDOVER && mTransportType == transportType) {
                    mState = STATE_CONNECTED;
                    log(
                            "Handover failed and return to: "
                                    + AccessNetworkConstants.transportTypeToString(mTransportType));
                    notifyDataConnectionStatusChangedEvent(EVENT_DATA_CONNECTION_HANDOVER_FAILED);
                }
                break;

            case TelephonyManager.DATA_HANDOVER_IN_PROGRESS:
                if (mState == STATE_CONNECTED && mTransportType == transportType) {
                    mState = STATE_HANDOVER;
                    log(
                            "Handover initiated from "
                                    + AccessNetworkConstants.transportTypeToString(transportType));
                    notifyDataConnectionStatusChangedEvent(EVENT_DATA_CONNECTION_HANDOVER_STARTED);
                } else {
                    log(
                            "Ignore STATE_HANDOVER since request is not for Src TransportType: "
                                    + AccessNetworkConstants.transportTypeToString(mTransportType));
                }
                break;

            default:
                break;
        }
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
        mContext.unregisterReceiver(mIntentReceiver);
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
                    + AccessNetworkConstants.transportTypeToString(mCurrentTransportType)
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
            AsyncResult ar = (AsyncResult) message.obj;
            switch (message.what) {
                case EVENT_DATA_CONNECTION_STATE_CHANGED:
                    onDataConnectionStateChanged((PreciseDataConnectionState) ar.result);
                    break;
                default:
                    log("never reach here msg=" + message.what);
            }
        }
    }

    /*For internal test This should be removed later.*/
    private final BroadcastReceiver mIntentReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    int event = -1;
                    log("onReceive: " + action);
                    switch (action) {
                        case "android.intent.action.QNS_DC_CHANGED":
                            int apntype = intent.getIntExtra("apntype", ApnSetting.TYPE_IMS);
                            if (apntype != mApnType) {
                                return;
                            }
                            mTestHoFailMode = intent.getIntExtra("hofailmode", 0);
                            int dctransporttype =
                                    intent.getIntExtra(
                                            "transport",
                                            AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
                            mTransportType = dctransporttype;
                            int dcevent =
                                    intent.getIntExtra("event", EVENT_DATA_CONNECTION_DISCONNECTED);
                            switch (dcevent) {
                                case EVENT_DATA_CONNECTION_DISCONNECTED:
                                    mState = STATE_INACTIVE;
                                    break;
                                case EVENT_DATA_CONNECTION_CONNECTED:
                                    mState = STATE_CONNECTED;
                                    break;
                                case EVENT_DATA_CONNECTION_HANDOVER_STARTED:
                                    mState = STATE_HANDOVER;
                                    break;
                                case EVENT_DATA_CONNECTION_HANDOVER_SUCCESS:
                                    mState = STATE_CONNECTED;
                                    break;
                                case EVENT_DATA_CONNECTION_HANDOVER_FAILED:
                                    mState = STATE_CONNECTED;
                                    break;
                                default:
                                    break;
                            }
                            mTestMode = true;
                            log(
                                    "TEST: QNS_DC_CHANGED apntype:"
                                            + apntype
                                            + "  state:"
                                            + mState
                                            + "  transport:"
                                            + dctransporttype
                                            + "  dcevent"
                                            + dcevent
                                            + "  testHoFailMode:"
                                            + mTestHoFailMode);
                            notifyDataConnectionStatusChangedEvent(dcevent);
                            break;
                    }
                }
            };
    /*For internal test [e]*/
}
