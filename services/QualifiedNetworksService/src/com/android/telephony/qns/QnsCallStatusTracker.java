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

import android.annotation.NonNull;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.telephony.CallState;
import android.telephony.PreciseCallState;
import android.telephony.PreciseDataConnectionState;
import android.telephony.TelephonyManager;
import android.telephony.ims.ImsCallProfile;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Tracking IMS Call status and update call type changed event to ANE.
 */
public class QnsCallStatusTracker {
    private final String mLogTag;
    private QnsTelephonyListener mTelephonyListener;
    private List<CallState> mCallStates = new ArrayList<>();
    private QnsRegistrant mCallTypeChangedEventListener;
    private QnsRegistrant mEmergencyCallTypeChangedEventListener;
    private int mLastNormalCallType = QnsConstants.CALL_TYPE_IDLE;
    private int mLastEmergencyCallType = QnsConstants.CALL_TYPE_IDLE;
    private boolean mEmergencyOverIms;
    private Consumer<List<CallState>> mCallStatesConsumer =
            callStateList -> updateCallState(callStateList);
    private Consumer<Integer> mSrvccStateConsumer = state -> onSrvccStateChangedInternal(state);

    QnsCallStatusTracker(QnsTelephonyListener telephonyListener, int slotIndex) {
        mLogTag = QnsCallStatusTracker.class.getSimpleName() + "_" + slotIndex;
        mTelephonyListener = telephonyListener;
        mTelephonyListener.addCallStatesChangedCallback(mCallStatesConsumer);
        mTelephonyListener.addSrvccStateChangedCallback(mSrvccStateConsumer);
    }

    void close() {
        mTelephonyListener.removeCallStatesChangedCallback(mCallStatesConsumer);
        mTelephonyListener.removeSrvccStateChangedCallback(mSrvccStateConsumer);
    }

    void updateCallState(List<CallState> callStateList) {
        List<CallState> imsCallStateList = new ArrayList<>();
        StringBuilder sb = new StringBuilder("");

        if (callStateList.size() > 0) {
            for (CallState cs : callStateList) {
                if (cs.getImsCallServiceType() != ImsCallProfile.SERVICE_TYPE_NONE
                        || cs.getImsCallType() != ImsCallProfile.CALL_TYPE_NONE) {
                    if (cs.getCallState() != PreciseCallState.PRECISE_CALL_STATE_DISCONNECTED) {
                        imsCallStateList.add(cs);
                        sb.append("{" + cs + "}");
                    }
                }
            }
        }
        int ongoingCallNum = imsCallStateList.size();
        mCallStates = imsCallStateList;
        Log.d(mLogTag, "updateCallState callNum:(" + ongoingCallNum + "): [" + sb + "]");
        if (imsCallStateList.size() == 0) {
            if (mLastNormalCallType != QnsConstants.CALL_TYPE_IDLE) {
                mLastNormalCallType = QnsConstants.CALL_TYPE_IDLE;
                if (mCallTypeChangedEventListener != null) {
                    mCallTypeChangedEventListener.notifyResult(mLastNormalCallType);
                }
            }
            if (mLastEmergencyCallType != QnsConstants.CALL_TYPE_IDLE) {
                mLastEmergencyCallType = QnsConstants.CALL_TYPE_IDLE;
                if (mEmergencyOverIms) {
                    mEmergencyOverIms = false;
                    if (mCallTypeChangedEventListener != null) {
                        mCallTypeChangedEventListener.notifyResult(mLastNormalCallType);
                    }
                } else {
                    if (mEmergencyCallTypeChangedEventListener != null) {
                        mEmergencyCallTypeChangedEventListener.notifyResult(mLastEmergencyCallType);
                    }
                }
            }
        } else {
            //1. Notify Call Type IDLE, if the call was removed from the call list.
            if (mLastNormalCallType != QnsConstants.CALL_TYPE_IDLE
                    && !hasVideoCall() && !hasVoiceCall()) {
                mLastNormalCallType = QnsConstants.CALL_TYPE_IDLE;
                if (mCallTypeChangedEventListener != null) {
                    mCallTypeChangedEventListener.notifyResult(mLastNormalCallType);
                }
            }
            if (mLastEmergencyCallType != QnsConstants.CALL_TYPE_IDLE && !hasEmergencyCall()) {
                mLastEmergencyCallType = QnsConstants.CALL_TYPE_IDLE;
                if (mEmergencyOverIms) {
                    mEmergencyOverIms = false;
                    if (mCallTypeChangedEventListener != null) {
                        mCallTypeChangedEventListener.notifyResult(mLastEmergencyCallType);
                    }
                } else {
                    if (mEmergencyCallTypeChangedEventListener != null) {
                        mEmergencyCallTypeChangedEventListener.notifyResult(mLastEmergencyCallType);
                    }
                }
            }
            //2. Notify a new ongoing call type
            if (hasEmergencyCall() && mLastEmergencyCallType != QnsConstants.CALL_TYPE_EMERGENCY) {
                mLastEmergencyCallType = QnsConstants.CALL_TYPE_EMERGENCY;
                if (!isDataNetworkConnected(NetworkCapabilities.NET_CAPABILITY_EIMS)
                        && isDataNetworkConnected(NetworkCapabilities.NET_CAPABILITY_IMS)) {
                    if (mCallTypeChangedEventListener != null) {
                        mCallTypeChangedEventListener.notifyResult(mLastEmergencyCallType);
                        mEmergencyOverIms = true;
                    }
                } else {
                    if (mEmergencyCallTypeChangedEventListener != null) {
                        mEmergencyCallTypeChangedEventListener.notifyResult(mLastEmergencyCallType);
                    }
                }
            } else if (hasVideoCall()) {
                if (mLastNormalCallType != QnsConstants.CALL_TYPE_VIDEO) {
                    mLastNormalCallType = QnsConstants.CALL_TYPE_VIDEO;
                    mCallTypeChangedEventListener.notifyResult(mLastNormalCallType);
                }
            } else if (hasVoiceCall()) {
                if (mLastNormalCallType != QnsConstants.CALL_TYPE_VOICE) {
                    mLastNormalCallType = QnsConstants.CALL_TYPE_VOICE;
                    mCallTypeChangedEventListener.notifyResult(mLastNormalCallType);
                }
            }
        }
    }

    boolean isCallIdle() {
        return mCallStates.size() == 0;
    }

    boolean hasEmergencyCall() {
        for (CallState cs : mCallStates) {
            if (cs.getImsCallServiceType() == ImsCallProfile.SERVICE_TYPE_EMERGENCY) {
                return true;
            }
        }
        return false;
    }

    boolean hasVideoCall() {
        for (CallState cs : mCallStates) {
            if (cs.getImsCallServiceType() == ImsCallProfile.SERVICE_TYPE_NORMAL
                    && cs.getImsCallType() == ImsCallProfile.CALL_TYPE_VT
                    && (cs.getCallState() != PreciseCallState.PRECISE_CALL_STATE_ALERTING
                    && cs.getCallState() != PreciseCallState.PRECISE_CALL_STATE_DIALING
                    && cs.getCallState() != PreciseCallState.PRECISE_CALL_STATE_INCOMING)) {
                return true;
            }
        }
        return false;
    }

    boolean hasVoiceCall() {
        for (CallState cs : mCallStates) {
            if (cs.getImsCallServiceType() == ImsCallProfile.SERVICE_TYPE_NORMAL
                    && cs.getImsCallType() == ImsCallProfile.CALL_TYPE_VOICE) {
                return true;
            }
        }
        return false;
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
            Log.d(mLogTag, "registerCallTypeChangedListener : wrong netCapability");
            return;
        }
        if (h != null) {
            QnsRegistrant r = new QnsRegistrant(h, what, userObj);
            if (netCapability == NetworkCapabilities.NET_CAPABILITY_IMS) {
                mCallTypeChangedEventListener = r;
            } else if (netCapability == NetworkCapabilities.NET_CAPABILITY_EIMS) {
                mEmergencyCallTypeChangedEventListener = r;
            }
        } else {
            Log.d(mLogTag, "registerCallTypeChangedListener : Handler is Null");
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
            Log.d(mLogTag, "unregisterCallTypeChangedListener : wrong netCapability");
            return;
        }
        if (h != null) {
            if (netCapability == NetworkCapabilities.NET_CAPABILITY_IMS) {
                mCallTypeChangedEventListener = null;
            } else if (netCapability == NetworkCapabilities.NET_CAPABILITY_EIMS) {
                mEmergencyCallTypeChangedEventListener = null;
            }
        } else {
            Log.d(mLogTag, "unregisterCallTypeChangedListener : Handler is Null");
        }
    }

    @VisibleForTesting
    void onSrvccStateChangedInternal(int srvccState) {
        if (srvccState == TelephonyManager.SRVCC_STATE_HANDOVER_COMPLETED) {
            mCallStates.clear();
            if (mLastNormalCallType != QnsConstants.CALL_TYPE_IDLE) {
                mLastNormalCallType = QnsConstants.CALL_TYPE_IDLE;
                if (mCallTypeChangedEventListener != null) {
                    mCallTypeChangedEventListener.notifyResult(mLastNormalCallType);
                }
            }
            if (mLastEmergencyCallType != QnsConstants.CALL_TYPE_IDLE) {
                mLastEmergencyCallType = QnsConstants.CALL_TYPE_IDLE;
                if (mEmergencyOverIms) {
                    mEmergencyOverIms = false;
                    if (mCallTypeChangedEventListener != null) {
                        mCallTypeChangedEventListener.notifyResult(mLastEmergencyCallType);
                    }
                } else {
                    if (mEmergencyCallTypeChangedEventListener != null) {
                        mEmergencyCallTypeChangedEventListener.notifyResult(mLastEmergencyCallType);
                    }
                }
            }
        }
    }

    private boolean isDataNetworkConnected(int netCapability) {
        PreciseDataConnectionState preciseDataStatus =
                mTelephonyListener.getLastPreciseDataConnectionState(netCapability);

        if (preciseDataStatus == null) return false;
        int state = preciseDataStatus.getState();
        return (state == TelephonyManager.DATA_CONNECTED
                || state == TelephonyManager.DATA_HANDOVER_IN_PROGRESS
                || state == TelephonyManager.DATA_SUSPENDED);
    }
}
