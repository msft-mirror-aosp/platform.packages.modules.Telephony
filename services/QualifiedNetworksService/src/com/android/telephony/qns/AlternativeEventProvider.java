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

package com.android.telephony.qns;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.telephony.AccessNetworkConstants;
import android.telephony.Annotation;
import android.util.Log;

/**
 * AlternativeEventProvider class
 */
public abstract class AlternativeEventProvider {
    private int mSlotId;
    private Context mContext;
    private EventCallback mEventCb;
    private final String mLogTag;

    public AlternativeEventProvider(Context context, int slotId) {
        mSlotId = slotId;
        mContext = context;
        mLogTag =
                QnsConstants.QNS_TAG
                        + "_"
                        + AlternativeEventProvider.class.getSimpleName()
                        + "_"
                        + slotId;
        AlternativeEventListener.getInstance(mContext, mSlotId).setEventProvider(this);
    }

    /**
     * Thins is to register RTP threshold.
     *
     * @param callId CallId
     * @param jitter RTP jitter value
     * @param packetLossRate RTP packet loss rate
     * @param packetLossTimeInMilliSec timer for RTP packet loss
     * @param noRtpTimeInMilliSec time for no incoming RTP
     */
    public abstract void requestRtpThreshold(
            int callId,
            int jitter,
            int packetLossRate,
            int packetLossTimeInMilliSec,
            int noRtpTimeInMilliSec);

    /**
     * This is to set signal strength threshold.
     *
     * @param threshold signal strength threshold values
     */
    public abstract void setEcnoSignalThreshold(@Nullable int[] threshold);

    /**
     * Event provider calls this to notify call info changed.
     *
     * @param id CallId
     * @parma type CallType
     * @param state CallState
     */
    public void notifyCallInfo(int id, int type, int state) {
        if (mEventCb != null) {
            mEventCb.onCallInfoChanged(id, type, state);
        }
    }

    /**
     * Event provider calls this to notify RTP low quality
     *
     * @param reason RTP low quality reason.
     */
    public void notifyRtpLowQuality(@QnsConstants.RtpLowQualityReason int reason) {
        if (mEventCb != null) {
            mEventCb.onVoiceRtpLowQuality(reason);
        }
    }

    /**
     * Event provider needs to call this to notify Emergency preference change
     *
     * @param type Emergency transport type preference for initial data connection.
     */
    public void notifyEmergencyPreferredTransportType(
            @AccessNetworkConstants.TransportType int type) {
        if (mEventCb != null) {
            mEventCb.onEmergencyPreferenceChanged(type);
        }
    }

    /**
     * Event provider needs to call this to notify try WFC connection state change
     *
     * @param isEnabled try WFC connection
     */
    public void notifyTryWfcConnectionState(boolean isEnabled) {
        if (mEventCb != null) {
            mEventCb.onTryWfcConnectionStateChanged(isEnabled);
        }
    }

    /**
     * Listener need to register CallBack with implements of EventCallback.
     *
     * @param eventCb implements of EventCallback.
     */
    public void registerCallBack(@NonNull EventCallback eventCb) {
        log("registerCallBack" + eventCb);
        mEventCb = eventCb;
    }

    /**
     * Event callback for call related item
     */
    public interface EventCallback {
        /**
         * call type event notification.
         *
         * @param id CallId
         * @parma type CallType
         * @param state CallState
         */
        void onCallInfoChanged(
                int id,
                @QnsConstants.QnsCallType int type,
                @Annotation.PreciseCallStates int state);

        /**
         * RTP event notification.
         *
         * @param reason
         */
        void onVoiceRtpLowQuality(@QnsConstants.RtpLowQualityReason int reason);

        /**
         * Notify Emergency Transport Type Preference for initial connect.
         *
         * @param transport
         */
        void onEmergencyPreferenceChanged(@AccessNetworkConstants.TransportType int transport);

        /**
         * Try WFC connection state change notification.
         *
         * @param isEnabled
         */
        void onTryWfcConnectionStateChanged(boolean isEnabled);
    }

    protected void log(String s) {
        Log.d(mLogTag, s);
    }
}
