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
import static android.telephony.PreciseCallState.PRECISE_CALL_STATE_DISCONNECTED;
import static android.telephony.PreciseCallState.PRECISE_CALL_STATE_HOLDING;
import static android.telephony.PreciseCallState.PRECISE_CALL_STATE_IDLE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Registrant;
import android.telephony.Annotation;
import android.telephony.TelephonyManager;
import android.telephony.data.ApnSetting;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;

public class AlternativeEventListener {
    private static final int EVENT_SRVCC_STATE_CHANGED = 13001;
    private static final SparseArray<AlternativeEventListener> sAlternativeInterfaceManager =
            new SparseArray<>();
    private final String LOG_TAG;
    private Context mContext;
    private int mSlotIndex;
    private Registrant mCallTypeChangedEventListener;
    private Registrant mEmergencyCallTypeChangedEventListener;
    private Registrant mEmergencyPreferredTransportTypeChanged;
    private Registrant mTryWfcConnectionState;
    private Registrant mLowRtpQuallityListener;
    private Registrant mEmcLowRtpQuallityListener;
    private AlternativeEventCb mEventCb;
    private AlternativeEventProvider mEventProvider;
    private Handler mHandler;
    private CallInfoManager mCallInfoManager = new CallInfoManager();

    class CallInfo {
        int mId;
        int mType;
        int mState;

        public CallInfo(int id, int type, int state) {
            mId = id;
            mType = type;
            mState = state;
        }
    }

    /** Manager of CallInfo list. */
    private class CallInfoManager {
        private SparseArray<CallInfo> callInfos = new SparseArray<>();
        QnsCarrierConfigManager.RtpMetricsConfig mRtpMetricsConfig;
        int mEmergencyCallState = PRECISE_CALL_STATE_IDLE;
        int mLastReportedCallType = QnsConstants.CALL_TYPE_IDLE;

        public CallInfoManager() {
            mRtpMetricsConfig = null;
        }

        public void updateCallInfo(
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
                CallInfo info = callInfos.get(id);
                if (info == null) {
                    if (state == PRECISE_CALL_STATE_ACTIVE) {
                        callInfos.put(id, new CallInfo(id, type, state));
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
                        callInfos.remove(id);
                    }
                }
            }
        }

        boolean isCallIdle() {
            return callInfos.size() == 0;
        }

        CallInfo getActiveCallInfo() {
            for (int i = 0; i < callInfos.size(); i++) {
                int key = callInfos.keyAt(i);
                if (callInfos.get(key).mState == PRECISE_CALL_STATE_ACTIVE) {
                    return callInfos.get(key);
                }
            }
            return null;
        }

        void clearCallInfo() {
            callInfos.clear();
        }

        boolean hasVideoCall() {
            for (int i = 0; i < callInfos.size(); i++) {
                int key = callInfos.keyAt(i);
                if (callInfos.get(key).mType == QnsConstants.CALL_TYPE_VIDEO) {
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
        public MessageHandler(Looper l) {
            super(l);
        }

        @Override
        public void handleMessage(Message message) {
            Log.d(LOG_TAG, "handleMessage msg=" + message.what);
            AsyncResult ar = (AsyncResult) message.obj;
            int state = (int) ar.result;
            switch (message.what) {
                case EVENT_SRVCC_STATE_CHANGED:
                    onSrvccStateChanged(state);
                    break;
                default:
                    Log.d(LOG_TAG, "Unknown message received!");
                    break;
            }
        }
    }

    private AlternativeEventListener(@NonNull Context context, int slotIndex) {
        LOG_TAG =
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
    public static AlternativeEventListener getInstance(@NonNull Context context, int slotIndex) {
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
    public void registerEmergencyPreferredTransportTypeChanged(
            @NonNull Handler h, int what, Object userObj) {
        mEmergencyPreferredTransportTypeChanged = new Registrant(h, what, userObj);
    }

    /** Unregister emergency preferred transport type changed event. */
    public void unregisterEmergencyPreferredTransportTypeChanged() {
        mEmergencyPreferredTransportTypeChanged = null;
    }

    /**
     * register try WFC connection state change event.
     *
     * @param h Handler want to receive event
     * @param what event Id to receive
     * @param userObj user object
     */
    public void registerTryWfcConnectionStateListener(
            @NonNull Handler h, int what, Object userObj) {
        mTryWfcConnectionState = new Registrant(h, what, userObj);
    }

    /**
     * Register low RTP quality event.
     *
     * @param apnType APN type
     * @param h Handler want to receive event.
     * @param what event Id to receive
     * @param userObj user object
     */
    public void registerLowRtpQualityEvent(
            int apnType,
            @NonNull Handler h,
            int what,
            Object userObj,
            QnsCarrierConfigManager.RtpMetricsConfig config) {
        if (h != null) {
            Registrant r = new Registrant(h, what, userObj);
            if (apnType == ApnSetting.TYPE_IMS) {
                mLowRtpQuallityListener = r;
            } else if (apnType == ApnSetting.TYPE_EMERGENCY) {
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
     * @param apnType APN type
     * @param h Handler want to receive event.
     */
    public void unregisterLowRtpQualityEvent(int apnType, @NonNull Handler h) {
        if (apnType == ApnSetting.TYPE_IMS) {
            mLowRtpQuallityListener = null;
        } else if (apnType == ApnSetting.TYPE_EMERGENCY) {
            mEmcLowRtpQuallityListener = null;
        }
        mCallInfoManager.mRtpMetricsConfig = null;
    }

    /**
     * register call type changed event.
     *
     * @param apnType apnType of caller
     * @param h Handler want to receive event.
     * @param what event Id to receive
     * @param userObj user object
     */
    public void registerCallTypeChangedListener(
            @ApnSetting.ApnType int apnType, @NonNull Handler h, int what, Object userObj) {
        if (apnType != ApnSetting.TYPE_IMS && apnType != ApnSetting.TYPE_EMERGENCY) {
            log("registerCallTypeChangedListener : wrong ApnType");
            return;
        }
        if (h != null) {
            Registrant r = new Registrant(h, what, userObj);
            if (apnType == ApnSetting.TYPE_IMS) {
                mCallTypeChangedEventListener = r;
                QnsTelephonyListener.getInstance(mContext, mSlotIndex)
                        .registerSrvccStateListener(mHandler, EVENT_SRVCC_STATE_CHANGED, null);
            } else if (apnType == ApnSetting.TYPE_EMERGENCY) {
                mEmergencyCallTypeChangedEventListener = r;
            }
        } else {
            log("registerCallTypeChangedListener : Handler is Null");
        }
    }

    /**
     * Unregister call type changed event.
     *
     * @param apnType apnType of caller
     * @param h Handler want to receive event.
     */
    public void unregisterCallTypeChangedListener(
            @ApnSetting.ApnType int apnType, @NonNull Handler h) {
        if (apnType != ApnSetting.TYPE_IMS && apnType != ApnSetting.TYPE_EMERGENCY) {
            log("unregisterCallTypeChangedListener : wrong ApnType");
            return;
        }
        if (h != null) {
            if (apnType == ApnSetting.TYPE_IMS) {
                mCallTypeChangedEventListener = null;
                QnsTelephonyListener.getInstance(mContext, mSlotIndex)
                        .unregisterSrvccStateChanged(mHandler);
            } else if (apnType == ApnSetting.TYPE_EMERGENCY) {
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
    public void setEventProvider(AlternativeEventProvider provider) {
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
        }
    }

    public class AlternativeEventCb implements AlternativeEventProvider.EventCallback {
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
        Log.d(LOG_TAG, s);
    }

    protected static void slog(String s) {
        Log.d("AlternativeEventListener", s);
    }
}
