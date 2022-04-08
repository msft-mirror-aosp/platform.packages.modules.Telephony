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
import android.content.Context;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Registrant;
import android.os.SystemClock;
import android.telephony.AccessNetworkConstants;
import android.telephony.Annotation;
import android.telephony.TelephonyManager;
import android.telephony.data.ApnSetting;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.qns.DataConnectionStatusTracker.DataConnectionChangedInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prevents HO pingpong between Cellular and IWLAN. Provide Throttling for certain cause. Provide
 * Handover not allowed policy.
 */
public class RestrictManager {
    private final String TAG;
    private final boolean DBG = true;
    static final int RESTRICT_TYPE_GUARDING = 1;
    static final int RESTRICT_TYPE_THROTTLING = 2;
    static final int RESTRICT_TYPE_HO_NOT_ALLOWED = 3;
    static final int RESTRICT_TYPE_NON_PREFERRED_TRANSPORT = 4;
    static final int RESTRICT_TYPE_RTP_LOW_QUALITY = 5;
    static final int RESTRICT_TYPE_RESTRICT_IWLAN_IN_CALL = 6;
    static final int RESTRICT_TYPE_RESTRICT_IWLAN_CS_CALL = 7;
    static final int RESTRICT_TYPE_FALLBACK_TO_WWAN_IMS_REGI_FAIL = 8;

    @IntDef(
            value = {
                RESTRICT_TYPE_GUARDING,
                RESTRICT_TYPE_THROTTLING,
                RESTRICT_TYPE_HO_NOT_ALLOWED,
                RESTRICT_TYPE_NON_PREFERRED_TRANSPORT,
                RESTRICT_TYPE_RTP_LOW_QUALITY,
                RESTRICT_TYPE_RESTRICT_IWLAN_IN_CALL,
                RESTRICT_TYPE_FALLBACK_TO_WWAN_IMS_REGI_FAIL,
            })
    public @interface RestrictType {}

    static final int RELEASE_EVENT_DISCONNECT = 1;
    static final int RELEASE_EVENT_WIFI_AP_CHANGED = 2;
    static final int RELEASE_EVENT_WFC_PREFER_MODE_CHANGED = 3;
    static final int RELEASE_EVENT_CALL_END = 4;
    static final int RELEASE_EVENT_IMS_NOT_SUPPORT_RAT = 5;

    @IntDef(
            value = {
                RELEASE_EVENT_DISCONNECT,
                RELEASE_EVENT_WIFI_AP_CHANGED,
                RELEASE_EVENT_WFC_PREFER_MODE_CHANGED,
                RELEASE_EVENT_CALL_END,
            })
    public @interface ReleaseEvent {}

    private static final int EVENT_DATA_CONNECTION_CHANGED = 3001;
    private static final int EVENT_CALL_STATE_CHANGED = 3002;
    private static final int EVENT_SRVCC_STATE_CHANGED = 3003;
    private static final int EVENT_IMS_REGISTRATION_STATE_CHANGED = 3004;
    private static final int EVENT_LOW_RTP_QUALITY_REPORTED = 3006;
    private static final int EVENT_RELEASE_RESTRICTION = 3008;
    private static final int EVENT_REGISTER_LOW_RTP_QUALITY = 3009;

    private static final int GUARDING_TIMER_HANDOVER_INIT = 30000;

    static final HashMap<Integer, int[]> sReleaseEventMap =
            new HashMap<Integer, int[]>() {
                {
                    put(
                            RESTRICT_TYPE_GUARDING,
                            new int[] {
                                RELEASE_EVENT_DISCONNECT, RELEASE_EVENT_WFC_PREFER_MODE_CHANGED
                            });
                    put(
                            RESTRICT_TYPE_RTP_LOW_QUALITY,
                            new int[] {RELEASE_EVENT_CALL_END, RELEASE_EVENT_WIFI_AP_CHANGED});
                    put(RESTRICT_TYPE_RESTRICT_IWLAN_IN_CALL, new int[] {RELEASE_EVENT_CALL_END});
                    put(
                            RESTRICT_TYPE_FALLBACK_TO_WWAN_IMS_REGI_FAIL,
                            new int[] {
                                RELEASE_EVENT_DISCONNECT, RELEASE_EVENT_IMS_NOT_SUPPORT_RAT
                            });
                }
            };
    private static final int[] ignorableRestrictionsOnSingleRat =
            new int[] {
                RESTRICT_TYPE_GUARDING,
                RESTRICT_TYPE_RTP_LOW_QUALITY,
                RESTRICT_TYPE_RESTRICT_IWLAN_IN_CALL,
                RESTRICT_TYPE_FALLBACK_TO_WWAN_IMS_REGI_FAIL
            };

    private QnsCarrierConfigManager mQnsCarrierConfigManager;
    private QnsTelephonyListener mTelephonyListener;
    private QnsEventDispatcher mQnsEventDispatcher;
    private Handler mHandler;

    @QnsConstants.CellularCoverage
    int mCellularCoverage; // QnsConstants.COVERAGE_HOME or QnsConstants.COVERAGE_ROAM

    int mCellularAccessNetwork;

    @VisibleForTesting Registrant mRestrictInfoRegistrant;
    private DataConnectionStatusTracker mDataConnectionStatusTracker;
    private CellularNetworkStatusTracker mCellularNetworkStatusTracker;
    private AlternativeEventListener mAltEventListener;
    private ImsStatusListener mImsStatusListener;
    private int mApnType;
    private int mSlotId;
    private int mDataConnectionState = DataConnectionStatusTracker.STATE_INACTIVE;
    private int mTransportType = AccessNetworkConstants.TRANSPORT_TYPE_INVALID;
    private int mWfcPreference;
    private int mWfcRoamingPreference;
    private int mCounterForIwlanRestrictionInCall;
    /** IMS call type */
    @QnsConstants.QnsCallType private int mImsCallType;
    /** Call state from TelephonyCallback.CallStateListener */
    @Annotation.CallState private int mCallState;

    private Map<Integer, RestrictInfo> mRestrictInfos = new ConcurrentHashMap<>();

    private class RestrictManagerHandler extends Handler {
        public RestrictManagerHandler(Looper l) {
            super(l);
        }

        @Override
        public void handleMessage(Message message) {
            AsyncResult ar;
            int transportType;
            Log.d(TAG, "handleMessage : " + message.what);
            switch (message.what) {
                case EVENT_DATA_CONNECTION_CHANGED:
                    ar = (AsyncResult) message.obj;
                    onDataConnectionChanged((DataConnectionChangedInfo) ar.result);
                    break;

                case EVENT_CALL_STATE_CHANGED:
                    ar = (AsyncResult) message.obj;
                    int callState = (int) ar.result;
                    onCallStateChanged(callState, mTransportType, mCellularAccessNetwork);
                    break;

                case EVENT_SRVCC_STATE_CHANGED:
                    ar = (AsyncResult) message.obj;
                    int srvccState = (int) ar.result;
                    onSrvccStateChanged(srvccState);
                    break;

                case EVENT_LOW_RTP_QUALITY_REPORTED:
                    ar = (AsyncResult) message.obj;
                    int reason = (int) ar.result;
                    Log.d(TAG, "EVENT_LOW_RTP_QUALITY_REPORTED reason: " + reason);
                    onLowRtpQualityEvent();
                    break;

                case EVENT_IMS_REGISTRATION_STATE_CHANGED:
                    ar = (AsyncResult) message.obj;
                    onImsRegistrationStateChanged(
                            (ImsStatusListener.ImsRegistrationChangedEv) ar.result);
                    break;

                case EVENT_RELEASE_RESTRICTION:
                    transportType = message.arg1;
                    Restriction restriction = (Restriction) message.obj;
                    Log.d(
                            TAG,
                            "EVENT_RELEASE_RESTRICTION : "
                                    + AccessNetworkConstants.transportTypeToString(transportType)
                                    + " "
                                    + restrictTypeToString(restriction.mRestrictType));
                    if (restriction
                            == mRestrictInfos
                                    .get(transportType)
                                    .getRestrictionMap()
                                    .get(restriction.mRestrictType)) {
                        releaseRestriction(transportType, restriction.mRestrictType);
                    }
                    break;

                case EVENT_REGISTER_LOW_RTP_QUALITY:
                    int currTransportType = message.arg1;
                    if (currTransportType == mTransportType) {
                        registerLowRtpQualityEvent();
                    }
                    break;

                case QnsEventDispatcher.QNS_EVENT_WFC_MODE_TO_WIFI_ONLY:
                    onWfcModeChanged(QnsConstants.WIFI_ONLY, QnsConstants.COVERAGE_HOME);
                    break;
                case QnsEventDispatcher.QNS_EVENT_WFC_MODE_TO_CELLULAR_PREFERRED:
                    onWfcModeChanged(QnsConstants.CELL_PREF, QnsConstants.COVERAGE_HOME);
                    break;

                case QnsEventDispatcher.QNS_EVENT_WFC_MODE_TO_WIFI_PREFERRED:
                    onWfcModeChanged(QnsConstants.WIFI_PREF, QnsConstants.COVERAGE_HOME);
                    break;
                case QnsEventDispatcher.QNS_EVENT_WFC_ROAMING_MODE_TO_WIFI_ONLY:
                    onWfcModeChanged(QnsConstants.WIFI_ONLY, QnsConstants.COVERAGE_ROAM);
                    break;

                case QnsEventDispatcher.QNS_EVENT_WFC_ROAMING_MODE_TO_CELLULAR_PREFERRED:
                    onWfcModeChanged(QnsConstants.CELL_PREF, QnsConstants.COVERAGE_ROAM);
                    break;
                case QnsEventDispatcher.QNS_EVENT_WFC_ROAMING_MODE_TO_WIFI_PREFERRED:
                    onWfcModeChanged(QnsConstants.WIFI_PREF, QnsConstants.COVERAGE_ROAM);
                    break;

                default:
                    break;
            }
        }
    }

    class Restriction {
        private final int mRestrictType;
        final ArrayList<Integer> mReleaseEventList;
        long mReleaseTime;

        Restriction(int type, int[] releaseEvents, int restrictTime) {
            mRestrictType = type;
            if (restrictTime == 0) {
                mReleaseTime = 0;
            } else {
                mReleaseTime = restrictTime + SystemClock.elapsedRealtime();
            }
            if (releaseEvents != null && releaseEvents.length > 0) {
                mReleaseEventList = new ArrayList<>();
                for (int i : releaseEvents) {
                    mReleaseEventList.add(i);
                }
            } else {
                mReleaseEventList = null;
            }
        }

        boolean needRelease(int event) {
            if (mReleaseEventList == null) {
                return false;
            }
            for (Integer i : mReleaseEventList) {
                if (event == i.intValue()) {
                    return true;
                }
            }
            return false;
        }

        void updateRestrictTime(int timeMillis) {
            mReleaseTime = SystemClock.elapsedRealtime() + timeMillis;
        }

        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append("[RESTRICTION type:").append(restrictTypeToString(mRestrictType));
            builder.append(" releaseEvents:( ");
            if (mReleaseEventList != null) {
                for (Integer i : mReleaseEventList) {
                    builder.append(i).append(" ");
                }
            }
            builder.append(") remainedTimeMillis:");
            if (mReleaseTime == 0) {
                builder.append("N/A");
            } else {
                long remain = mReleaseTime - SystemClock.elapsedRealtime();
                builder.append(remain);
            }
            builder.append("]");
            return builder.toString();
        }
    }

    public class RestrictInfo {
        private int mTransportMode; // AccessNetworkConstants.TRANSPORT_TYPE_WWAN;
        private HashMap<Integer, Restriction> mRestrictionMap = new HashMap<>();

        public RestrictInfo(int transportMode) {
            mTransportMode = transportMode;
        }

        HashMap<Integer, Restriction> getRestrictionMap() {
            return mRestrictionMap;
        }

        public boolean isRestricted() {
            return mRestrictionMap.size() != 0;
        }

        public boolean hasRestrictonType(@RestrictType int restrictType) {
            return mRestrictionMap.get(restrictType) != null;
        }

        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append("RestrictInfo[")
                    .append(AccessNetworkConstants.transportTypeToString(mTransportMode))
                    .append("] : ");
            if (isRestricted()) {
                for (Restriction restriction : mRestrictionMap.values()) {
                    builder.append(restriction.toString()).append(" ");
                }
            } else {
                builder.append("No restriction");
            }
            return builder.toString();
        }
    }

    RestrictManager(
            Context context,
            Looper loop,
            int slotId,
            int apnType,
            DataConnectionStatusTracker dcst) {
        this(context, loop, slotId, apnType, dcst, null, null);
    }

    @VisibleForTesting
    protected RestrictManager(
            Context context,
            Looper loop,
            int slotId,
            int apnType,
            DataConnectionStatusTracker dcst,
            QnsCarrierConfigManager configManager,
            AlternativeEventListener altListener) {
        mRestrictInfos.put(
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                new RestrictInfo(AccessNetworkConstants.TRANSPORT_TYPE_WWAN));
        mRestrictInfos.put(
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                new RestrictInfo(AccessNetworkConstants.TRANSPORT_TYPE_WLAN));
        TAG =
                RestrictManager.class.getSimpleName()
                        + "_"
                        + slotId
                        + "_"
                        + QnsUtils.getStringApnTypes(apnType);
        mTelephonyListener = QnsTelephonyListener.getInstance(context, slotId);
        mQnsEventDispatcher = QnsEventDispatcher.getInstance(context, slotId);
        if (configManager == null) {
            mQnsCarrierConfigManager = QnsCarrierConfigManager.getInstance(context, slotId);
        } else {
            mQnsCarrierConfigManager = configManager;
        }
        mSlotId = slotId;
        mHandler = new RestrictManagerHandler(loop);
        mApnType = apnType;
        mDataConnectionStatusTracker = dcst;
        if (altListener == null) {
            mAltEventListener = AlternativeEventListener.getInstance(context, mSlotId);
        } else {
            mAltEventListener = altListener;
        }
        mDataConnectionStatusTracker.registerDataConnectionStatusChanged(
                mHandler, EVENT_DATA_CONNECTION_CHANGED);
        if (mApnType == ApnSetting.TYPE_IMS) {
            mTelephonyListener.registerCallStateListener(
                    mHandler, EVENT_CALL_STATE_CHANGED, null, true);
            mTelephonyListener.registerSrvccStateListener(
                    mHandler, EVENT_SRVCC_STATE_CHANGED, null);
        }
        if (mApnType == ApnSetting.TYPE_IMS) {
            mImsStatusListener = ImsStatusListener.getInstance(context, slotId);
            mImsStatusListener.registerImsRegistrationStatusChanged(
                    mHandler, EVENT_IMS_REGISTRATION_STATE_CHANGED);
        }

        mWfcPreference = QnsUtils.getWfcMode(context, slotId, false);
        mWfcRoamingPreference = QnsUtils.getWfcMode(context, slotId, true);

        List<Integer> events = new ArrayList<>();
        events.add(QnsEventDispatcher.QNS_EVENT_WFC_MODE_TO_WIFI_ONLY);
        events.add(QnsEventDispatcher.QNS_EVENT_WFC_MODE_TO_CELLULAR_PREFERRED);
        events.add(QnsEventDispatcher.QNS_EVENT_WFC_MODE_TO_WIFI_PREFERRED);
        events.add(QnsEventDispatcher.QNS_EVENT_WFC_ROAMING_MODE_TO_WIFI_ONLY);
        events.add(QnsEventDispatcher.QNS_EVENT_WFC_ROAMING_MODE_TO_CELLULAR_PREFERRED);
        events.add(QnsEventDispatcher.QNS_EVENT_WFC_ROAMING_MODE_TO_WIFI_PREFERRED);
        mQnsEventDispatcher.registerEvent(events, mHandler);

        mCellularNetworkStatusTracker = CellularNetworkStatusTracker.getInstance(context, slotId);
        restrictNonPreferredTransport();
    }

    public void clearRestrictions() {
        mRestrictInfos.get(AccessNetworkConstants.TRANSPORT_TYPE_WWAN).getRestrictionMap().clear();
        mRestrictInfos.get(AccessNetworkConstants.TRANSPORT_TYPE_WLAN).getRestrictionMap().clear();
    }

    public void close() {
        mDataConnectionStatusTracker.unRegisterDataConnectionStatusChanged(mHandler);
        if (mApnType == ApnSetting.TYPE_IMS) {
            mTelephonyListener.unregisterCallStateChanged(mHandler);
            mTelephonyListener.unregisterSrvccStateChanged(mHandler);
        }
        mQnsEventDispatcher.unregisterEvent(mHandler);
        if (mApnType == ApnSetting.TYPE_IMS || mApnType == ApnSetting.TYPE_EMERGENCY) {
            mAltEventListener.unregisterLowRtpQualityEvent(mApnType, mHandler);
        }
        if (mApnType == ApnSetting.TYPE_IMS) {
            mImsStatusListener.unregisterImsRegistrationStatusChanged(mHandler);
        }
    }

    private void onWfcModeChanged(int prefMode, @QnsConstants.CellularCoverage int coverage) {
        Log.d(TAG, "onWfcModeChanged  prefMode :" + prefMode + "  coverage:" + coverage);
        if (coverage == QnsConstants.COVERAGE_HOME) {
            mWfcPreference = prefMode;
        } else if (coverage == QnsConstants.COVERAGE_ROAM) {
            mWfcRoamingPreference = prefMode;
        }
        if (mCellularCoverage == coverage) {
            if (prefMode == QnsConstants.CELL_PREF) {
                processReleaseEvent(
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                        RELEASE_EVENT_WFC_PREFER_MODE_CHANGED);
            }
            if (prefMode == QnsConstants.WIFI_PREF || prefMode == QnsConstants.WIFI_ONLY) {
                processReleaseEvent(
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                        RELEASE_EVENT_WFC_PREFER_MODE_CHANGED);
            }
        }
        checkIfCancelNonPreferredRestriction(getPreferredTransportType());
    }

    @VisibleForTesting
    void restrictNonPreferredTransport() {
        if (mApnType == ApnSetting.TYPE_IMS
                && !mCellularNetworkStatusTracker.isAirplaneModeEnabled()) {
            Log.d(TAG, "Restrict non-preferred transport at power up");
            int transportType = getPreferredTransportType();
            int waitingTimer =
                    mQnsCarrierConfigManager.getWaitingTimerForPreferredTransportOnPowerOn(
                            transportType);
            if (waitingTimer != QnsConstants.KEY_DEFAULT_VALUE) {
                int preventTransportType = getOtherTransport(transportType);
                Log.d(
                        TAG,
                        "prevent "
                                + AccessNetworkConstants.transportTypeToString(preventTransportType)
                                + " "
                                + waitingTimer
                                + " milli seconds");
                addRestriction(
                        preventTransportType,
                        RESTRICT_TYPE_NON_PREFERRED_TRANSPORT,
                        sReleaseEventMap.get(RESTRICT_TYPE_NON_PREFERRED_TRANSPORT),
                        waitingTimer);
            }
        }
    }

    private void checkIfCancelNonPreferredRestriction(int transportType) {
        if (mApnType == ApnSetting.TYPE_IMS) {
            releaseRestriction(transportType, RESTRICT_TYPE_NON_PREFERRED_TRANSPORT);
        }
    }

    private int getPreferredTransportType() {
        int transportType;
        int preference = mWfcPreference;
        if (mCellularCoverage == QnsConstants.COVERAGE_ROAM) {
            preference = mWfcRoamingPreference;
        }
        if (preference == QnsConstants.WIFI_PREF || preference == QnsConstants.WIFI_ONLY) {
            transportType = AccessNetworkConstants.TRANSPORT_TYPE_WLAN;
        } else {
            transportType = AccessNetworkConstants.TRANSPORT_TYPE_WWAN;
        }
        return transportType;
    }

    private void onCallStateChanged(int callState, int transportType, int cellularAn) {
        Log.d(
                TAG,
                "onCallStateChanged :"
                        + callState
                        + " transport:"
                        + transportType
                        + " cellularAN:"
                        + cellularAn);
        mCallState = callState;
        if (callState != TelephonyManager.CALL_STATE_IDLE) {
            if (transportType != AccessNetworkConstants.TRANSPORT_TYPE_WLAN
                    && cellularAn != AccessNetworkConstants.AccessNetworkType.EUTRAN
                    && cellularAn != AccessNetworkConstants.AccessNetworkType.NGRAN) {
                onCsCallStarted();
            }
        } else {
            releaseRestriction(
                    AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                    RESTRICT_TYPE_RESTRICT_IWLAN_CS_CALL);
        }
    }

    private void onSrvccStateChanged(int srvccState) {
        Log.d(TAG, "onSrvccStateChanged :" + srvccState);
        if (mImsCallType != QnsConstants.CALL_TYPE_IDLE
                && srvccState == TelephonyManager.SRVCC_STATE_HANDOVER_STARTED) {
            addRestriction(
                    AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                    RESTRICT_TYPE_RESTRICT_IWLAN_CS_CALL,
                    sReleaseEventMap.get(RESTRICT_TYPE_RESTRICT_IWLAN_CS_CALL),
                    0);
        } else if (mCallState == TelephonyManager.CALL_STATE_IDLE
                || srvccState == TelephonyManager.SRVCC_STATE_HANDOVER_CANCELED
                || srvccState == TelephonyManager.SRVCC_STATE_HANDOVER_FAILED) {
            releaseRestriction(
                    AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                    RESTRICT_TYPE_RESTRICT_IWLAN_CS_CALL);
        }
    }

    private void onCsCallStarted() {
        if (!mQnsCarrierConfigManager.allowImsOverIwlanCellularLimitedCase()) {
            addRestriction(
                    AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                    RESTRICT_TYPE_RESTRICT_IWLAN_CS_CALL,
                    sReleaseEventMap.get(RESTRICT_TYPE_RESTRICT_IWLAN_CS_CALL),
                    0);
        }
    }

    @VisibleForTesting
    void onLowRtpQualityEvent() {
        int lowRtpQualityRestrictTime =
                mQnsCarrierConfigManager.getHoRestrictedTimeOnLowRTPQuality(mTransportType);
        if ((mTransportType == AccessNetworkConstants.TRANSPORT_TYPE_WLAN
                        || mTransportType == AccessNetworkConstants.TRANSPORT_TYPE_WWAN)
                && lowRtpQualityRestrictTime > 0
                && mImsCallType != QnsConstants.CALL_TYPE_IDLE) {
            addRestriction(
                    mTransportType,
                    RESTRICT_TYPE_RTP_LOW_QUALITY,
                    sReleaseEventMap.get(RESTRICT_TYPE_RTP_LOW_QUALITY),
                    lowRtpQualityRestrictTime);
            unregisterLowRtpQualityEvent();
            int fallbackReason = mQnsCarrierConfigManager.getQnsIwlanHoRestrictReason();
            if (fallbackReason == QnsConstants.FALLBACK_REASON_RTP_OR_WIFI
                    || fallbackReason == QnsConstants.FALLBACK_REASON_RTP_ONLY) {
                increaseCounterToRestrictIwlanInCall();
            }
        }
    }

    @VisibleForTesting
    void onDataConnectionChanged(DataConnectionChangedInfo status) {
        mDataConnectionState = status.getState();
        mTransportType = status.getTransportType();
        Log.d(TAG, "onDataConnectionChanged transportType:" + status);
        switch (status.getEvent()) {
            case DataConnectionStatusTracker.EVENT_DATA_CONNECTION_DISCONNECTED:
                processDataConnectionDisconnected();
                break;
            case DataConnectionStatusTracker.EVENT_DATA_CONNECTION_STARTED:
                processDataConnectionStarted();
                break;
            case DataConnectionStatusTracker.EVENT_DATA_CONNECTION_CONNECTED:
                processDataConnectionConnected(mTransportType);
                break;
            case DataConnectionStatusTracker.EVENT_DATA_CONNECTION_HANDOVER_STARTED:
                processDataConnectionHandoverStarted();
                break;
            case DataConnectionStatusTracker.EVENT_DATA_CONNECTION_HANDOVER_SUCCESS:
                processDataConnectionHandoverSuccess();
                break;
            case DataConnectionStatusTracker.EVENT_DATA_CONNECTION_HANDOVER_FAILED:
                processDataConnectionHandoverFailed(mTransportType);
                break;
            default:
                Log.d(TAG, "unknown DataConnectionChangedEvent:");
                break;
        }
    }

    private void processDataConnectionConnected(int transportType) {
        checkIfCancelNonPreferredRestriction(getOtherTransport(transportType));
        if (mApnType == ApnSetting.TYPE_IMS) {
            processHandoverGuardingOperation(transportType);
        }
    }

    private void processDataConnectionDisconnected() {
        processReleaseEvent(AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RELEASE_EVENT_DISCONNECT);
        processReleaseEvent(AccessNetworkConstants.TRANSPORT_TYPE_WLAN, RELEASE_EVENT_DISCONNECT);
        mCounterForIwlanRestrictionInCall = 0;
    }

    private void processDataConnectionStarted() {}

    private void processDataConnectionHandoverStarted() {
        if ((mTransportType != AccessNetworkConstants.TRANSPORT_TYPE_INVALID)
                && !hasRestrictionType(mTransportType, RestrictManager.RESTRICT_TYPE_GUARDING))
            startGuarding(GUARDING_TIMER_HANDOVER_INIT, mTransportType);
    }

    private void processDataConnectionHandoverSuccess() {
        /* Handover Guarding Timer operation */
        processHandoverGuardingOperation(mTransportType);

        /* update LowRtpQualityListener */
        if (mTransportType == AccessNetworkConstants.TRANSPORT_TYPE_WLAN
                || mTransportType == AccessNetworkConstants.TRANSPORT_TYPE_WWAN) {
            Log.d(TAG, "Unregister & Register Low RTP quality for " + mTransportType);
            unregisterLowRtpQualityEvent();

            Message msg =
                    mHandler.obtainMessage(EVENT_REGISTER_LOW_RTP_QUALITY, mTransportType, 0, null);
            mHandler.sendMessageDelayed(msg, (long) QnsConstants.DEFAULT_MSG_DELAY_TIMER);
        }
    }

    private void processDataConnectionHandoverFailed(int transportType) {
        cancelGuarding(transportType);
    }

    private void processHandoverGuardingOperation(int transportType) {
        int guardingTransport = getOtherTransport(transportType);
        int delayMillis = getGuardingTimeMillis(guardingTransport, mImsCallType);

        if (delayMillis > 0) {
            startGuarding(delayMillis, guardingTransport);
        } else {
            cancelGuarding(guardingTransport);
        }
    }

    @VisibleForTesting
    void onImsRegistrationStateChanged(ImsStatusListener.ImsRegistrationChangedEv event) {
        Log.d(
                TAG,
                "onImsRegistrationStateChanged["
                        + AccessNetworkConstants.transportTypeToString(mTransportType)
                        + "] transportType["
                        + AccessNetworkConstants.transportTypeToString(event.getTransportType())
                        + "] RegistrationState["
                        + QnsConstants.imsRegistrationEventToString(event.getEvent()));
        int prefMode =
                mCellularCoverage == QnsConstants.COVERAGE_HOME
                        ? mWfcPreference
                        : mWfcRoamingPreference;
        switch (event.getEvent()) {
            case QnsConstants.IMS_REGISTRATION_CHANGED_UNREGISTERED:
                onImsUnregistered(event, mTransportType, prefMode);
                break;
            case QnsConstants.IMS_REGISTRATION_CHANGED_ACCESS_NETWORK_CHANGE_FAILED:
                onImsHoRegisterFailed(event, mTransportType, prefMode);
                break;
            default:
                break;
        }
    }

    private void onImsUnregistered(
            ImsStatusListener.ImsRegistrationChangedEv event, int transportType, int prefMode) {
        if (transportType == AccessNetworkConstants.TRANSPORT_TYPE_WLAN) {
            int fallbackTimeMillis =
                    mQnsCarrierConfigManager.getFallbackTimeImsUnreigstered(
                            event.getReasonInfo().getCode(), prefMode);
            if (fallbackTimeMillis > 0
                    && mQnsCarrierConfigManager.isAccessNetworkAllowed(
                            mCellularAccessNetwork, ApnSetting.TYPE_IMS)) {
                fallbackToWwanForImsRegitration(fallbackTimeMillis);
            }
        }
    }

    private void onImsHoRegisterFailed(
            ImsStatusListener.ImsRegistrationChangedEv event, int transportType, int prefMode) {
        if (transportType == AccessNetworkConstants.TRANSPORT_TYPE_WLAN
                && transportType == event.getTransportType()) {
            int fallbackTimeMillis =
                    mQnsCarrierConfigManager.getFallbackTimeImsHoRegisterFailed(
                            event.getReasonInfo().getCode(), prefMode);
            if (fallbackTimeMillis > 0
                    && mQnsCarrierConfigManager.isAccessNetworkAllowed(
                            mCellularAccessNetwork, ApnSetting.TYPE_IMS)) {
                fallbackToWwanForImsRegitration(fallbackTimeMillis);
            }
        }
    }

    private void fallbackToWwanForImsRegitration(int fallbackTimeMillis) {
        Log.d(TAG, "release ignorable restrictions on WWAN to fallback.");
        for (int restrictType : ignorableRestrictionsOnSingleRat) {
            releaseRestriction(AccessNetworkConstants.TRANSPORT_TYPE_WWAN, restrictType, true);
        }
        addRestriction(
                AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                RESTRICT_TYPE_FALLBACK_TO_WWAN_IMS_REGI_FAIL,
                sReleaseEventMap.get(RESTRICT_TYPE_FALLBACK_TO_WWAN_IMS_REGI_FAIL),
                fallbackTimeMillis);
    }

    public void requestGuardingRestriction(int transportType) {
        if (mDataConnectionStatusTracker.isActiveState()) {
            startGuarding(GUARDING_TIMER_HANDOVER_INIT, transportType);
        }
    }

    @VisibleForTesting
    void setCellularCoverage(@QnsConstants.CellularCoverage int coverage) {
        Log.d(TAG, "setCellularCoverage:" + QnsConstants.coverageToString(coverage));
        mCellularCoverage = coverage;
        checkIfCancelNonPreferredRestriction(getPreferredTransportType());
    }

    protected void setQnsCallType(@QnsConstants.QnsCallType int callType) {
        if (callType != mImsCallType)
            updateGuardingTimerConditionOnCallState(mImsCallType, callType);

        mImsCallType = callType;

        Log.d(TAG, "setQnsCallType: " + QnsConstants.callTypeToString(callType));
        if (callType == QnsConstants.CALL_TYPE_IDLE) {
            Log.d(TAG, "Call end. init mCounterForIwlanRestrictionInCall");
            mCounterForIwlanRestrictionInCall = 0;

            processReleaseEvent(AccessNetworkConstants.TRANSPORT_TYPE_WLAN, RELEASE_EVENT_CALL_END);
            processReleaseEvent(AccessNetworkConstants.TRANSPORT_TYPE_WWAN, RELEASE_EVENT_CALL_END);
            unregisterLowRtpQualityEvent();
        } else {
            registerLowRtpQualityEvent();
        }
    }

    private void updateGuardingTimerConditionOnCallState(int prevCallType, int newCallType) {
        int currGuardingTransport = getOtherTransport(mTransportType);
        if (mRestrictInfos.get(currGuardingTransport) == null) return;

        HashMap<Integer, Restriction> restrictionMap =
                mRestrictInfos.get(currGuardingTransport).getRestrictionMap();
        Restriction restriction = restrictionMap.get(RESTRICT_TYPE_GUARDING);

        if (restriction != null) {
            int prevCallTypeMillis = getGuardingTimeMillis(currGuardingTransport, prevCallType);
            int newCallTypeMillis =
                    getGuardingTimeMillis(
                            currGuardingTransport, newCallType); // new Call type timer
            if (newCallTypeMillis == prevCallTypeMillis) return;

            if (newCallTypeMillis != 0) {
                // remaining time on current call type
                long prevCallTypeRemainingMillis =
                        restriction.mReleaseTime - SystemClock.elapsedRealtime();
                int guardTimerElapsed = prevCallTypeMillis - (int) prevCallTypeRemainingMillis;
                int newGuardTimer = newCallTypeMillis - guardTimerElapsed;

                if (DBG) {
                    Log.d(
                            TAG,
                            "Prev Call Type Guarding millis:"
                                    + prevCallTypeMillis
                                    + "Prev Call type remaining millis:"
                                    + prevCallTypeRemainingMillis
                                    + "New Call type Guarding millis:"
                                    + newCallTypeMillis
                                    + "Guard timer Elapsed:"
                                    + guardTimerElapsed
                                    + "New Guard timer to set:"
                                    + newGuardTimer);
                }
                if (newGuardTimer > 0) {
                    startGuarding(newGuardTimer, currGuardingTransport);
                    return;
                }
            }
            cancelGuarding(currGuardingTransport);
        }
    }

    @VisibleForTesting
    void setCellularAccessNetwork(int accessNetwork) {
        mCellularAccessNetwork = accessNetwork;
        if (mApnType == ApnSetting.TYPE_IMS
                && !mQnsCarrierConfigManager.isAccessNetworkAllowed(accessNetwork, mApnType)) {
            processReleaseEvent(
                    AccessNetworkConstants.TRANSPORT_TYPE_WLAN, RELEASE_EVENT_IMS_NOT_SUPPORT_RAT);
        }
    }

    void addRestriction(int transport, int type, int[] releaseEvents, int timeMillis) {
        boolean needNotify = false;
        HashMap<Integer, Restriction> restrictionMap =
                mRestrictInfos.get(transport).getRestrictionMap();
        Restriction restriction = restrictionMap.get(type);
        Log.d(
                TAG,
                "addRestriction["
                        + AccessNetworkConstants.transportTypeToString(transport)
                        + "] "
                        + restrictTypeToString(type)
                        + " was restrict:"
                        + (restriction != null));
        if (restriction == null) {
            restriction = new Restriction(type, releaseEvents, timeMillis);
            restrictionMap.put(type, restriction);
            Log.d(
                    TAG,
                    "addRestriction["
                            + AccessNetworkConstants.transportTypeToString(transport)
                            + "] "
                            + restriction);
            needNotify = true;
        } else {
            if (timeMillis > 0) {
                restriction.updateRestrictTime(timeMillis);
                removeReleaseRestrictionMessage(restriction);
            }
            Log.d(
                    TAG,
                    "updateRestriction["
                            + AccessNetworkConstants.transportTypeToString(transport)
                            + "] "
                            + restriction);
        }
        if (timeMillis > 0) {
            sendReleaseRestrictionMessage(transport, restriction);
        }
        if (needNotify) {
            notifyRestrictInfoChanged();
        }
    }

    void releaseRestriction(int transport, int type) {
        releaseRestriction(transport, type, false);
    }

    void releaseRestriction(int transport, int type, boolean skipNotify) {
        boolean needNotify = false;
        HashMap<Integer, Restriction> restrictionMap =
                mRestrictInfos.get(transport).getRestrictionMap();
        Restriction restriction = restrictionMap.get(type);
        Log.d(
                TAG,
                "releaseRestriction["
                        + AccessNetworkConstants.transportTypeToString(transport)
                        + "] "
                        + restrictTypeToString(type)
                        + " was restrict:"
                        + (restriction != null));
        if (restriction == null) {
            Log.d(TAG, "no restriction to release " + restrictTypeToString(type) + " " + type);
        } else {
            if (restriction.mReleaseTime > 0) {
                removeReleaseRestrictionMessage(restriction);
            }
            restrictionMap.remove(restriction.mRestrictType);
            needNotify = true;
        }
        if (needNotify && !skipNotify) {
            notifyRestrictInfoChanged();
        }
    }

    void processReleaseEvent(int transportType, int event) {
        ArrayList<Integer> releaseList = new ArrayList<>();
        HashMap<Integer, Restriction> restrictMap =
                mRestrictInfos.get(transportType).getRestrictionMap();
        Log.d(
                TAG,
                "processReleaseEvent["
                        + AccessNetworkConstants.transportTypeToString(transportType)
                        + "] "
                        + event);

        for (Integer restrictType : restrictMap.keySet()) {
            if (restrictMap.get(restrictType).needRelease(event)) {
                releaseList.add(restrictType);
            }
        }
        for (Integer restrictType : releaseList) {
            releaseRestriction(transportType, restrictType);
        }
    }

    private void sendReleaseRestrictionMessage(int transportType, Restriction restriction) {
        if (restriction == null) {
            Log.e(TAG, "sendReleaseRestrictionMessage restriction is null");
            return;
        }
        Message msg =
                mHandler.obtainMessage(EVENT_RELEASE_RESTRICTION, transportType, 0, restriction);
        long delayInMillis = restriction.mReleaseTime - SystemClock.elapsedRealtime();
        mHandler.sendMessageDelayed(msg, delayInMillis);
        Log.d(
                TAG,
                restrictTypeToString(restriction.mRestrictType)
                        + " will be released after "
                        + delayInMillis
                        + " millisecs");
    }

    private void removeReleaseRestrictionMessage(Restriction restriction) {
        if (restriction == null) {
            Log.e(TAG, "removeReleaseRestrictionMessage restriction is null");
            return;
        }
        mHandler.removeMessages(EVENT_RELEASE_RESTRICTION, restriction);
    }

    private int getOtherTransport(int transportType) {
        if (transportType == AccessNetworkConstants.TRANSPORT_TYPE_WLAN) {
            return AccessNetworkConstants.TRANSPORT_TYPE_WWAN;
        } else {
            return AccessNetworkConstants.TRANSPORT_TYPE_WLAN;
        }
    }

    public void registerRestrictInfoChanged(Handler h, int what) {
        mRestrictInfoRegistrant = new Registrant(h, what, null);
    }

    public void unRegisterRestrictInfoChanged(Handler h) {
        mRestrictInfoRegistrant = null;
    }

    @VisibleForTesting
    public boolean isRestricted(int transportType) {
        if (mRestrictInfos.isEmpty()) return false;

        if (mRestrictInfos.get(transportType) != null) {
            return mRestrictInfos.get(transportType).isRestricted();
        }

        return false;
    }

    public boolean isRestrictedExceptGuarding(int transportType) {
        try {
            RestrictInfo info = mRestrictInfos.get(transportType);
            int size = info.getRestrictionMap().size();
            if (info.hasRestrictonType(RESTRICT_TYPE_GUARDING)) {
                size--;
            }
            return size > 0;
        } catch (Exception e) {
        }
        return false;
    }

    @VisibleForTesting
    public boolean hasRestrictionType(int transportType, int restrictType) {
        return mRestrictInfos.get(transportType).hasRestrictonType(restrictType);
    }

    /** This method is only for Testing */
    @VisibleForTesting
    protected long getRemainingGuardTimer(int transportType) {
        return mRestrictInfos
                        .get(transportType)
                        .getRestrictionMap()
                        .get(RESTRICT_TYPE_GUARDING)
                        .mReleaseTime
                - SystemClock.elapsedRealtime();
    }

    @VisibleForTesting
    public boolean isAllowedOnSingleTransport(int transportType) {
        if (mRestrictInfos.isEmpty()) return false;
        Log.d(
                TAG,
                "isAllowedOnSingleTransport ("
                        + AccessNetworkConstants.transportTypeToString(transportType)
                        + ")  restriction :"
                        + mRestrictInfos.get(transportType).toString());
        int countIgnorableRestriction = 0;
        for (int restrictType : ignorableRestrictionsOnSingleRat) {
            if (mRestrictInfos.get(transportType).hasRestrictonType(restrictType)) {
                countIgnorableRestriction++;
            }
        }
        if (mRestrictInfos.get(transportType).getRestrictionMap().size()
                == countIgnorableRestriction) {
            return true;
        }
        return false;
    }

    void increaseCounterToRestrictIwlanInCall() {
        mCounterForIwlanRestrictionInCall += 1;
        int maxAllowedRoveOutByLowRtpQuality =
                mQnsCarrierConfigManager.getQnsMaxIwlanHoCountDuringCall();
        if (maxAllowedRoveOutByLowRtpQuality > 0
                && mCounterForIwlanRestrictionInCall == maxAllowedRoveOutByLowRtpQuality) {
            Log.d(TAG, "reached maxAllowedRoveOutByLowRtpQuality");
            addRestriction(
                    AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                    RESTRICT_TYPE_RESTRICT_IWLAN_IN_CALL,
                    sReleaseEventMap.get(RESTRICT_TYPE_RESTRICT_IWLAN_IN_CALL),
                    0);
        }
    }

    private void notifyRestrictInfoChanged() {
        Log.d(TAG, "notifyRestrictInfoChanged");
        if (mRestrictInfoRegistrant != null) {
            mRestrictInfoRegistrant.notifyResult(mRestrictInfos);
        } else {
            Log.d(TAG, "notifyRestrictInfoChanged. no Registrant.");
        }
    }

    private void registerLowRtpQualityEvent() {
        if ((mImsCallType == QnsConstants.CALL_TYPE_VOICE
                        || mImsCallType == QnsConstants.CALL_TYPE_EMERGENCY)
                && (mTransportType == AccessNetworkConstants.TRANSPORT_TYPE_WLAN
                        || mTransportType == AccessNetworkConstants.TRANSPORT_TYPE_WWAN)
                && mAltEventListener != null) {
            int hoRestrictTimeOnLowRtpQuality =
                    mQnsCarrierConfigManager.getHoRestrictedTimeOnLowRTPQuality(mTransportType);
            if (hoRestrictTimeOnLowRtpQuality > 0) {
                Log.d(TAG, "registerLowRtpQualityEvent");
                mAltEventListener.registerLowRtpQualityEvent(
                        mApnType,
                        mHandler,
                        EVENT_LOW_RTP_QUALITY_REPORTED,
                        null,
                        mQnsCarrierConfigManager.getRTPMetricsData());
            }
        }
    }

    private void unregisterLowRtpQualityEvent() {
        if (mApnType == ApnSetting.TYPE_IMS || mApnType == ApnSetting.TYPE_EMERGENCY) {
            mAltEventListener.unregisterLowRtpQualityEvent(mApnType, mHandler);
        }
    }

    private int getGuardingTimeMillis(int transportType, int callType) {
        int delayMillis;
        switch (mApnType) {
            case ApnSetting.TYPE_IMS:
            case ApnSetting.TYPE_EMERGENCY:
                if (!mQnsCarrierConfigManager.isHysteresisTimerEnabled(mCellularCoverage)) {
                    Log.d(
                            TAG,
                            "getGuardingTimeMillis: handover guarding timer is not enabled at "
                                    + QnsConstants.coverageToString(mCellularCoverage));
                    return 0;
                }
                if (transportType == AccessNetworkConstants.TRANSPORT_TYPE_WWAN) {
                    delayMillis =
                            mQnsCarrierConfigManager.getWwanHysteresisTimer(mApnType, callType);
                } else {
                    delayMillis =
                            mQnsCarrierConfigManager.getWlanHysteresisTimer(mApnType, callType);
                }
                if (delayMillis > 0
                        && mQnsCarrierConfigManager.isGuardTimerHystersisOnPrefSupported()) {
                    int preference = mWfcPreference;
                    if (mCellularCoverage == QnsConstants.COVERAGE_ROAM) {
                        preference = mWfcRoamingPreference;
                    }
                    if (preference == QnsConstants.CELL_PREF
                            && transportType == AccessNetworkConstants.TRANSPORT_TYPE_WLAN) {
                        Log.d(
                                TAG,
                                "getGuardingTimeMillis: cellular preferred case, don't guard"
                                        + " handover to WLAN");
                        delayMillis = 0;
                    } else if (preference == QnsConstants.WIFI_PREF
                            && transportType == AccessNetworkConstants.TRANSPORT_TYPE_WWAN) {
                        Log.d(
                                TAG,
                                "getGuardingTimeMillis: wifi preferred case, don't guard handover"
                                        + " to WWAN");
                        delayMillis = 0;
                    }
                }
                break;
            case ApnSetting.TYPE_MMS:
            case ApnSetting.TYPE_XCAP:
            case ApnSetting.TYPE_CBS:
                callType =
                        mAltEventListener.isIdleState()
                                ? QnsConstants.CALL_TYPE_IDLE
                                : QnsConstants.CALL_TYPE_VOICE;
                if (transportType == AccessNetworkConstants.TRANSPORT_TYPE_WWAN) {
                    delayMillis =
                            mQnsCarrierConfigManager.getWwanHysteresisTimer(mApnType, callType);
                } else if (transportType == AccessNetworkConstants.TRANSPORT_TYPE_WLAN) {
                    delayMillis =
                            mQnsCarrierConfigManager.getWlanHysteresisTimer(mApnType, callType);
                } else {
                    delayMillis = 0;
                }
                break;
            default:
                delayMillis = 0;
                break;
        }
        Log.d(
                TAG,
                "getGuardingTimeMillis: timer = "
                        + delayMillis
                        + " for transport type = "
                        + AccessNetworkConstants.transportTypeToString(transportType)
                        + " in "
                        + QnsConstants.callTypeToString(callType)
                        + " state.");

        return delayMillis;
    }

    private void startGuarding(int delay, int transportType) {
        // It is invalid to run to RESTRICT_TYPE_GUARDING for both Transport at same time
        // Make sure to release source TransportType Guarding before starting guarding for New
        // Transport
        // Type
        if (transportType != AccessNetworkConstants.TRANSPORT_TYPE_INVALID
                && hasRestrictionType(
                        getOtherTransport(transportType), RestrictManager.RESTRICT_TYPE_GUARDING)) {
            Log.d(
                    TAG,
                    "RESTRICT_TYPE_GUARDING cleared from Guarding for:"
                            + AccessNetworkConstants.transportTypeToString(mTransportType));
            // addRestriction() will take care to notify the ANE of Restrict Info status
            releaseRestriction(getOtherTransport(transportType), RESTRICT_TYPE_GUARDING, true);
        }

        addRestriction(
                transportType,
                RESTRICT_TYPE_GUARDING,
                sReleaseEventMap.get(RESTRICT_TYPE_GUARDING),
                delay);
    }

    private void cancelGuarding(int transportType) {
        releaseRestriction(transportType, RESTRICT_TYPE_GUARDING);
    }

    protected void notifyThrottling(boolean throttle, long throttleTime, int transportType) {
        Log.d(
                TAG,
                "notifyThrottling throttle:"
                        + throttle
                        + "  throttleTime:"
                        + throttleTime
                        + "  transportType:"
                        + AccessNetworkConstants.transportTypeToString(transportType));
        if (throttle) {
            long delayMillis = throttleTime - SystemClock.elapsedRealtime();
            if (delayMillis > 0) {
                addRestriction(
                        transportType,
                        RESTRICT_TYPE_THROTTLING,
                        sReleaseEventMap.get(RESTRICT_TYPE_THROTTLING),
                        (int) delayMillis);
            }
        } else {
            releaseRestriction(transportType, RESTRICT_TYPE_THROTTLING);
        }
    }

    public static String restrictTypeToString(int restrictType) {
        switch (restrictType) {
            case RESTRICT_TYPE_GUARDING:
                return "RESTRICT_TYPE_GUARDING";
            case RESTRICT_TYPE_THROTTLING:
                return "RESTRICT_TYPE_THROTTLING";
            case RESTRICT_TYPE_HO_NOT_ALLOWED:
                return "RESTRICT_TYPE_HO_NOT_ALLOWED";
            case RESTRICT_TYPE_NON_PREFERRED_TRANSPORT:
                return "RESTRICT_TYPE_NON_PREFERRED_TRANSPORT";
            case RESTRICT_TYPE_RTP_LOW_QUALITY:
                return "RESTRICT_TYPE_RTP_LOW_QUALITY";
            case RESTRICT_TYPE_RESTRICT_IWLAN_IN_CALL:
                return "RESTRICT_TYPE_RESTRICT_IWLAN_IN_CALL";
            case RESTRICT_TYPE_RESTRICT_IWLAN_CS_CALL:
                return "RESTRICT_TYPE_RESTRICT_IWLAN_CS_CALL";
            case RESTRICT_TYPE_FALLBACK_TO_WWAN_IMS_REGI_FAIL:
                return "RESTRICT_TYPE_FALLBACK_TO_WWAN_IMS_REGI_FAIL";
        }
        return "";
    }
}
