package com.android.qns.ext;

import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.telephony.AccessNetworkConstants;
import android.util.Log;
import android.util.SparseArray;

import com.android.qns.AlternativeEventProvider;

/** To test information(call info,RTP Metric,emergency PDN preference) */
public class TestManager {
    private static final SparseArray<TestManager> sTestRadioManager = new SparseArray<>();
    private final String LOG_TAG = "TestManager";
    private Context mContext;
    private int mSlotId;
    AltEvProvider mEventProvider;
    private AlternativeEventProvider.EventCallback myCb;

    private TestManager(Context context, int slotId) {
        log("Test Manager created");
        mContext = context;
        mSlotId = slotId;
        mEventProvider = new AltEvProvider(mContext, mSlotId);
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("android.intent.action.QNS_ALT_EVENT_CALL_TYPE");
        intentFilter.addAction("android.intent.action.QNS_ALT_EVENT_EMERGENCY_PREF");
        intentFilter.addAction("android.intent.action.QNS_ALT_EVENT_LOW_RTP_QUALITY");
        mContext.registerReceiver(mIntentReceiver, intentFilter);
    }

    private final BroadcastReceiver mIntentReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    int event = -1;
                    log("onReceive: " + action);
                    switch (action) {
                        case "android.intent.action.QNS_ALT_EVENT_CALL_TYPE":
                            int callId = intent.getIntExtra("id", 0);
                            int callType = intent.getIntExtra("type", 0);
                            int state = intent.getIntExtra("state", 0);
                            mEventProvider.notifyCallInfo(callId, callType, state);
                            break;

                        case "android.intent.action.QNS_ALT_EVENT_EMERGENCY_PREF":
                            int pref =
                                    intent.getIntExtra(
                                            "pref", AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
                            mEventProvider.notifyEmergencyPreferredTransportType(pref);
                            break;

                        case "android.intent.action.QNS_ALT_EVENT_LOW_RTP_QUALITY":
                            mEventProvider.notifyRtpLowQuality(1);
                            break;
                    }
                }
            };

    public static TestManager getInstance(Context context, int slotId) {
        if (sTestRadioManager.get(slotId) == null) {
            slog("getInstance sTestRadioManager slotid" + slotId + " null");
            sTestRadioManager.put(slotId, new TestManager(context, slotId));
        }
        slog("getInstance sTestRadioManager return " + sTestRadioManager.get(slotId));
        return sTestRadioManager.get(slotId);
    }

    class AltEvProvider extends AlternativeEventProvider {
        public AltEvProvider(Context context, int slotId) {
            super(context, slotId);
        }

        @Override
        public void requestRtpThreshold(
                int id, int jitter, int packetLossRate, int packetLossTime, int noRtpTime) {
            // TBD
            log("requestRtpThreshold: id:" + id + " jitter" + jitter);
            return;
        }

        @Override
        public void setEcnoSignalThreshold(@Nullable int[] threshold) {
            // TBD
        }
    }

    protected void log(String s) {
        Log.d(LOG_TAG, s);
    }

    protected static void slog(String s) {
        Log.d("TestManager", s);
    }
}
