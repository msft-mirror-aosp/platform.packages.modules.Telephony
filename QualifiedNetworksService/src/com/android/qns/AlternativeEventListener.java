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

import static android.telephony.PreciseCallState.PRECISE_CALL_STATE_ACTIVE;
import static android.telephony.PreciseCallState.PRECISE_CALL_STATE_ALERTING;
import static android.telephony.PreciseCallState.PRECISE_CALL_STATE_DIALING;
import static android.telephony.PreciseCallState.PRECISE_CALL_STATE_DISCONNECTED;
import static android.telephony.PreciseCallState.PRECISE_CALL_STATE_HOLDING;
import static android.telephony.PreciseCallState.PRECISE_CALL_STATE_IDLE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.telephony.Annotation;
import android.telephony.PreciseDataConnectionState;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;

class AlternativeEventListener {
    private static final int EVENT_SRVCC_STATE_CHANGED = 13001;
    private static final SparseArray<AlternativeEventListener> sAlternativeInterfaceManager =
            new SparseArray<>();
    private final String mLogTag;
    private Context mContext;
    private int mSlotIndex;
    private QnsRegistrant mCallTypeChangedEventListener;
    private QnsRegistrant mEmergencyCallTypeChangedEventListener;
    private QnsRegistrant mEmergencyPreferredTransportTypeChanged;
    private QnsRegistrant mTryWfcConnectionState;
    private QnsRegistrant mLowRtpQuallityListener;
    private QnsRegistrant mEmcLowRtpQuallityListener;
    private AlternativeEventCb mEventCb;
    private AlternativeEventProvider mEventProvider;
    private Handler mHandler;
    private CallInfoManager mCallInfoManager = new CallInfoManager();

    class CallInfo {
        int mId;
        int mType;
        int mState;

        CallInfo(int id, int type, int state) {
            mId = id;
            mType = type;
            mState = state;
        }
    }

    /** Manager of CallInfo list. */
    private class CallInfoManager {
        private SparseArray<CallInfo> mCallInfos = new SparseArray<>();
        QnsCarrierConfigManager.RtpMetricsConfig mRtpMetricsConfig;
        int mEmergencyCallState = PRECISE_CALL_STATE_IDLE;
        int mLastReportedCallType = QnsConstants.CALL_TYPE_IDLE;

        CallInfoManager() {
            mRtpMetricsConfig = null;
        }

        void updateCallInfo(
                int id,
                @QnsConstants.QnsCallType int type,
                @Annotation.PreciseCallStates int state) {
            if (mRtpMetricsConfig != null && state == PRECISE_CALL_STATE_ACTIVE) {
                requestRtpThreshold(mRtpMetricsConfig);
            }
            if (type == QnsConstants.CALL_TYPE_EMERGENCY) {
                mEmergencyCallState = state;
                return;
            }
            if (type == QnsConstants.CALL_TYPE_VIDEO || type == QnsConstants.CALL_TYPE_VOICE) {
                CallInfo info = mCallInfos.get(id);
                if (info == null) {
                    if (state == PRECISE_CALL_STATE_ACTIVE) {
                        mCallInfos.put(id, new CallInfo(id, type, state));
                        log("add callinfo with id " + id);
                    }
                } else {
                    if (info.mType != type) {
                        log("CallId[" + info.mId + "] type changed " + info.mType + " > " + id);
                        info.mType = type;
                    }
                    if (state == PRECISE_CALL_STATE_HOLDING) {
                        log("CallId[" + info.mId + "] changed to HOLDING ");
                        info.mState = state;
                    } else if (state == PRECISE_CALL_STATE_ACTIVE) {
                        log("CallId[" + info.mId + "] changed to ACTIVE ");
                        info.mState = state;
                    } else if (state == PRECISE_CALL_STATE_DISCONNECTED) {
                        log("delete callInfo callId:" + id);
                        mCallInfos.remove(id);
                    }
                }
            }
        }

        boolean isCallIdle() {
            return mCallInfos.size() == 0;
        }

        CallInfo getActiveCallInfo() {
            for (int i = 0; i < mCallInfos.size(); i++) {
                int key = mCallInfos.keyAt(i);
                if (mCallInfos.get(key).mState == PRECISE_CALL_STATE_ACTIVE) {
                    return mCallInfos.get(key);
                }
            }
            return null;
        }

        void clearCallInfo() {
            mCallInfos.clear();
        }

        boolean hasVideoCall() {
            for (int i = 0; i < mCallInfos.size(); i++) {
                int key = mCallInfos.keyAt(i);
                if (mCallInfos.get(key).mType == QnsConstants.CALL_TYPE_VIDEO) {
                    return true;
                }
            }
            return false;
        }

        void requestRtpThreshold(QnsCarrierConfigManager.RtpMetricsConfig config) {
            if (config != null) {
                mRtpMetricsConfig =
                        new QnsCarrierConfigManager.RtpMetricsConfig(
                                config.mJitter,
                                config.mPktLossRate,
                                config.mPktLossTime,
                                config.mNoRtpInterval);
            }
            CallInfo info = getActiveCallInfo();
            if (mEventProvider != null && info != null) {
                log("requestRtpThreshold callId:" + info.mId + " " + config.toString());
                mEventProvider.requestRtpThreshold(
                        info.mId,
                        mRtpMetricsConfig.mJitter,
                        mRtpMetricsConfig.mPktLossRate,
                        mRtpMetricsConfig.mPktLossTime,
                        mRtpMetricsConfig.mNoRtpInterval);
            }
        }
    }

    class MessageHandler extends Handler {
        MessageHandler(Looper l) {
            super(l);
        }

        @Override
        public void handleMessage(Message message) {
            Log.d(mLogTag, "handleMessage msg=" + message.what);
            QnsAsyncResult ar = (QnsAsyncResult) message.obj;
            int state = (int) ar.mResult;
            switch (message.what) {
                case EVENT_SRVCC_STATE_CHANGED:
                    onSrvccStateChanged(state);
                    break;
                default:
                    Log.d(mLogTag, "Unknown message received!");
                    break;
            }
        }
    }

    private AlternativeEventListener(@NonNull Context context, int slotIndex) {
        mLogTag =
                QnsConstants.QNS_TAG
                        + "_"
                        + AlternativeEventListener.class.getSimpleName()
                        + "_"
                        + slotIndex;
        mContext = context;
        mSlotIndex = slotIndex;
        mHandler = new AlternativeEventListener.MessageHandler(mContext.getMainLooper());
        mEventCb = new AlternativeEventCb();
    }

    /**
     * getter of AlternativeEventListener instance.
     *
     * @param context application context
     * @param slotIndex slot id
     */
    static AlternativeEventListener getInstance(@NonNull Context context, int slotIndex) {
        AlternativeEventListener altIntMngr = sAlternativeInterfaceManager.get(slotIndex);
        if (altIntMngr == null) {
            altIntMngr = new AlternativeEventListener(context, slotIndex);
            slog("getInstance create new instance slotId" + slotIndex);
            sAlternativeInterfaceManager.put(slotIndex, altIntMngr);
        }
        return altIntMngr;
    }

    /**
     * register emergency preferred transport type changed event.
     *
     * @param h Handler want to receive event.
     * @param what event Id to receive
     * @param userObj user object
     */
    void registerEmergencyPreferredTransportTypeChanged(
            @NonNull Handler h, int what, Object userObj) {
        mEmergencyPreferredTransportTypeChanged = new QnsRegistrant(h, what, userObj);
    }

    /** Unregister emergency preferred transport type changed event. */
    void unregisterEmergencyPreferredTransportTypeChanged() {
        mEmergencyPreferredTransportTypeChanged = null;
    }

    /**
     * register try WFC connection state change event.
     *
     * @param h Handler want to receive event
     * @param what event Id to receive
     * @param userObj user object
     */
    void registerTryWfcConnectionStateListener(
            @NonNull Handler h, int what, Object userObj) {
        mTryWfcConnectionState = new QnsRegistrant(h, what, userObj);
    }

    /**
     * Register low RTP quality event.
     *
     * @param netCapability Network Capability
     * @param h Handler want to receive event.
     * @param what event Id to receive
     * @param userObj user object
     */
    void registerLowRtpQualityEvent(
            int netCapability,
            @NonNull Handler h,
            int what,
            Object userObj,
            QnsCarrierConfigManager.RtpMetricsConfig config) {
        if (h != null) {
            QnsRegistrant r = new QnsRegistrant(h, what, userObj);
            if (netCapability == NetworkCapabilities.NET_CAPABILITY_IMS) {
                mLowRtpQuallityListener = r;
            } else if (netCapability == NetworkCapabilities.NET_CAPABILITY_EIMS) {
                mEmcLowRtpQuallityListener = r;
            }
            if (mEventProvider != null) {
                mCallInfoManager.requestRtpThreshold(config);
            }
        }
    }

    /**
     * Unregister low RTP quality event.
     *
     * @param netCapability Network Capability
     * @param h Handler want to receive event.
     */
    void unregisterLowRtpQualityEvent(int netCapability, @NonNull Handler h) {
        if (netCapability == NetworkCapabilities.NET_CAPABILITY_IMS) {
            mLowRtpQuallityListener = null;
        } else if (netCapability == NetworkCapabilities.NET_CAPABILITY_EIMS) {
            mEmcLowRtpQuallityListener = null;
        }
        mCallInfoManager.mRtpMetricsConfig = null;
    }

    /**
     * register call type changed event.
     *
     * @param netCapability Network Capability of caller
     * @param h Handler want to receive event.
     * @param what event Id to receive
     * @param userObj user object
     */
    void registerCallTypeChangedListener(
            int netCapability, @NonNull Handler h, int what, Object userObj) {
        if (netCapability != NetworkCapabilities.NET_CAPABILITY_IMS
                && netCapability != NetworkCapabilities.NET_CAPABILITY_EIMS) {
            log("registerCallTypeChangedListener : wrong netCapability");
            return;
        }
        if (h != null) {
            QnsRegistrant r = new QnsRegistrant(h, what, userObj);
            if (netCapability == NetworkCapabilities.NET_CAPABILITY_IMS) {
                mCallTypeChangedEventListener = r;
                QnsTelephonyListener.getInstance(mContext, mSlotIndex)
                        .registerSrvccStateListener(mHandler, EVENT_SRVCC_STATE_CHANGED, null);
            } else if (netCapability == NetworkCapabilities.NET_CAPABILITY_EIMS) {
                mEmergencyCallTypeChangedEventListener = r;
            }
        } else {
            log("registerCallTypeChangedListener : Handler is Null");
        }
    }

    /**
     * Unregister call type changed event.
     *
     * @param netCapability Network Capability of caller
     * @param h Handler want to receive event.
     */
    void unregisterCallTypeChangedListener(int netCapability, @NonNull Handler h) {
        if (netCapability != NetworkCapabilities.NET_CAPABILITY_IMS
                && netCapability != NetworkCapabilities.NET_CAPABILITY_EIMS) {
            log("unregisterCallTypeChangedListener : wrong netCapability");
            return;
        }
        if (h != null) {
            if (netCapability == NetworkCapabilities.NET_CAPABILITY_IMS) {
                mCallTypeChangedEventListener = null;
                QnsTelephonyListener.getInstance(mContext, mSlotIndex)
                        .unregisterSrvccStateChanged(mHandler);
            } else if (netCapability == NetworkCapabilities.NET_CAPABILITY_EIMS) {
                mEmergencyCallTypeChangedEventListener = null;
            }
        } else {
            log("unregisterCallTypeChangedListener : Handler is Null");
        }
    }

    /**
     * register EventProvider to EventListener
     *
     * @param provider extends of AlternativeEventProvider
     */
    void setEventProvider(AlternativeEventProvider provider) {
        log("setEventProvider provider " + provider);
        mEventProvider = provider;
        provider.registerCallBack(mEventCb);
    }

    @VisibleForTesting
    void onSrvccStateChanged(int srvccState) {
        if (srvccState == TelephonyManager.SRVCC_STATE_HANDOVER_COMPLETED) {
            mCallInfoManager.clearCallInfo();
            int callType = QnsConstants.CALL_TYPE_IDLE;
            mCallTypeChangedEventListener.notifyResult(callType);
        }
    }

    void clearNormalCallInfo() {
        mCallInfoManager.clearCallInfo();
        mCallInfoManager.mLastReportedCallType = QnsConstants.CALL_TYPE_IDLE;
        unregisterLowRtpQualityEvent(NetworkCapabilities.NET_CAPABILITY_IMS, null);
    }

    @VisibleForTesting
    void notifyRtpLowQuality(int callType, int reason) {
        if (callType == QnsConstants.CALL_TYPE_VOICE) {
            if (mLowRtpQuallityListener != null) {
                mLowRtpQuallityListener.notifyResult(reason);
            } else {
                log("notifyRtpLowQuality mLowRtpQuallityListener is null.");
            }
        } else if (callType == QnsConstants.CALL_TYPE_EMERGENCY) {
            if (mEmcLowRtpQuallityListener != null) {
                mEmcLowRtpQuallityListener.notifyResult(reason);
            } else {
                log("notifyRtpLowQuality mEmcLowRtpQuallityListener is null.");
            }
            if (mCallInfoManager.mLastReportedCallType == QnsConstants.CALL_TYPE_EMERGENCY) {
                if (mLowRtpQuallityListener != null) {
                    log("notifyRtpLowQuality for emergency call to IMS ANE");
                    mLowRtpQuallityListener.notifyResult(reason);
                } else {
                    log("notifyRtpLowQuality mLowRtpQuallityListener is null.");
                }
            }
        }
    }

    class AlternativeEventCb implements AlternativeEventProvider.EventCallback {
        @Override
        public void onCallInfoChanged(
                int id,
                @QnsConstants.QnsCallType int type,
                @Annotation.PreciseCallStates int state) {
            log("onCallInfoChanged callId" + id + "  type" + type + "  state" + state);

            mCallInfoManager.updateCallInfo(id, type, state);
            if (type == QnsConstants.CALL_TYPE_EMERGENCY) {
                if (mEmergencyCallTypeChangedEventListener != null) {
                    if (state == PRECISE_CALL_STATE_DISCONNECTED) {
                        mEmergencyCallTypeChangedEventListener.notifyResult(
                                QnsConstants.CALL_TYPE_IDLE);
                    } else if (state == PRECISE_CALL_STATE_ACTIVE) {
                        mEmergencyCallTypeChangedEventListener.notifyResult(
                                QnsConstants.CALL_TYPE_EMERGENCY);
                    }
                }
                if (mCallTypeChangedEventListener != null) {
                    if ((state == PRECISE_CALL_STATE_ACTIVE
                                    || state == PRECISE_CALL_STATE_DIALING
                                    || state == PRECISE_CALL_STATE_ALERTING)
                            && !isDataNetworkConnected(NetworkCapabilities.NET_CAPABILITY_EIMS)
                            && isDataNetworkConnected(NetworkCapabilities.NET_CAPABILITY_IMS)) {
                        log("Emergency call is progressing without emergency PDN");
                        if (mCallInfoManager.mLastReportedCallType
                                != QnsConstants.CALL_TYPE_EMERGENCY) {
                            mCallTypeChangedEventListener.notifyResult(
                                    QnsConstants.CALL_TYPE_EMERGENCY);
                            mCallInfoManager.mLastReportedCallType =
                                    QnsConstants.CALL_TYPE_EMERGENCY;
                        }
                    } else if (state == PRECISE_CALL_STATE_DISCONNECTED) {
                        log("Emergency call disconnected");
                        if (mCallInfoManager.mLastReportedCallType
                                == QnsConstants.CALL_TYPE_EMERGENCY) {
                            mCallTypeChangedEventListener.notifyResult(QnsConstants.CALL_TYPE_IDLE);
                            mCallInfoManager.mLastReportedCallType = QnsConstants.CALL_TYPE_IDLE;
                        }
                    }
                }
                return;
            }
            if (mCallTypeChangedEventListener != null) {
                int callType = QnsConstants.CALL_TYPE_IDLE;
                if (mCallInfoManager.isCallIdle()) {
                    callType = QnsConstants.CALL_TYPE_IDLE;
                } else if (mCallInfoManager.hasVideoCall()) {
                    callType = QnsConstants.CALL_TYPE_VIDEO;
                } else {
                    callType = QnsConstants.CALL_TYPE_VOICE;
                }
                if (mCallTypeChangedEventListener != null
                        && mCallInfoManager.mLastReportedCallType != callType) {
                    mCallTypeChangedEventListener.notifyResult(callType);
                    mCallInfoManager.mLastReportedCallType = callType;
                }
            }
        }

        @Override
        public void onVoiceRtpLowQuality(@QnsConstants.RtpLowQualityReason int reason) {
            if (mCallInfoManager.mEmergencyCallState == PRECISE_CALL_STATE_ACTIVE) {
                notifyRtpLowQuality(QnsConstants.CALL_TYPE_EMERGENCY, reason);
            } else if (!mCallInfoManager.isCallIdle() && !mCallInfoManager.hasVideoCall()) {
                notifyRtpLowQuality(QnsConstants.CALL_TYPE_VOICE, reason);
            }
        }

        @Override
        public void onEmergencyPreferenceChanged(int preferredTransportType) {
            if (mEmergencyPreferredTransportTypeChanged != null) {
                mEmergencyPreferredTransportTypeChanged.notifyResult(preferredTransportType);
            }
        }

        @Override
        public void onTryWfcConnectionStateChanged(boolean isEnabled) {
            mTryWfcConnectionState.notifyResult(isEnabled);
        }
    }

    protected boolean isIdleState() {
        return mCallInfoManager.isCallIdle();
    }

    protected void setEcnoSignalThreshold(@Nullable int[] threshold) {
        if (mEventProvider != null) {
            mEventProvider.setEcnoSignalThreshold(threshold);
        }
    }

    private boolean isDataNetworkConnected(int netCapability) {
        PreciseDataConnectionState preciseDataStatus =
                QnsTelephonyListener.getInstance(mContext, mSlotIndex)
                        .getLastPreciseDataConnectionState(netCapability);
        if (preciseDataStatus == null) return false;
        int state = preciseDataStatus.getState();
        return (state == TelephonyManager.DATA_CONNECTED
                || state == TelephonyManager.DATA_HANDOVER_IN_PROGRESS
                || state == TelephonyManager.DATA_SUSPENDED);
    }

    @VisibleForTesting
    protected void close() {
        mCallTypeChangedEventListener = null;
        mEmergencyCallTypeChangedEventListener = null;
        mEmergencyPreferredTransportTypeChanged = null;
        mTryWfcConnectionState = null;
        mLowRtpQuallityListener = null;
        mEmcLowRtpQuallityListener = null;
        sAlternativeInterfaceManager.remove(mSlotIndex);
    }

    protected void log(String s) {
        Log.d(mLogTag, s);
    }

    protected static void slog(String s) {
        Log.d("AlternativeEventListener", s);
    }
}
