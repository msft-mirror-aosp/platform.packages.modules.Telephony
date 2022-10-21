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

import static android.net.NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED;

import static com.android.telephony.qns.QnsConstants.THRESHOLD_EQUAL_OR_LARGER;

import android.annotation.NonNull;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.telephony.SignalThresholdInfo;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * This class manages threshold information registered from AccessNetworkEvaluator It is intended to
 * monitor Wi-Fi qualities(Wi-Fi RSSI, WIFI PER) & report event if the network quality changes over
 * the threshold value.
 */
public class WifiQualityMonitor extends QualityMonitor {
    private final String mTag;
    private final Context mContext;
    private final WifiManager mWifiManager;
    private final ConnectivityManager mConnectivityManager;
    private final WiFiThresholdCallback mWiFiThresholdCallback;
    private final WiFiStatusCallback mWiFiStatusCallback;
    private final NetworkRequest.Builder mBuilder;

    private int mWifiRssi;
    private final Handler mHandler;
    private int mRegisteredThreshold = SIGNAL_STRENGTH_UNSPECIFIED;
    private boolean mIsWifiConnected = true;
    private boolean mIsRegistered = false;
    private boolean mWifiStateIntentEnabled = false;
    private boolean mIsBackhaulRunning;

    private class WiFiThresholdCallback extends ConnectivityManager.NetworkCallback {
        /** Callback Received based on meeting Wifi RSSI Threshold Registered or Wifi Lost */
        @Override
        public void onCapabilitiesChanged(
                @NonNull Network network, @NonNull NetworkCapabilities networkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities);
            mWifiRssi = networkCapabilities.getSignalStrength();
            Log.d(mTag, "onCapabilitiesChanged, wifi rssi = " + mWifiRssi);
            mHandler.sendEmptyMessage(EVENT_WIFI_RSSI_CHANGED);
        }
    }

    private class WiFiStatusCallback extends ConnectivityManager.NetworkCallback {
        /** Called when connected network is disconnected. */
        @Override
        public void onLost(@NonNull Network network) {
            super.onLost(network);
            Log.d(mTag, "onLost: " + network);
            mIsWifiConnected = false;
            if (mHandler.hasMessages(EVENT_WIFI_NOTIFY_TIMER_EXPIRED)) {
                Log.d(mTag, "Stop all active backhaul timers");
                mHandler.removeMessages(EVENT_WIFI_NOTIFY_TIMER_EXPIRED);
                mWaitingThresholds.clear();
            }
            mHandler.sendEmptyMessage(EVENT_WIFI_STATE_CHANGED);
        }

        /** Called when the framework connects network & is ready for use. */
        @Override
        public void onAvailable(@NonNull Network network) {
            super.onAvailable(network);
            Log.d(mTag, "onAvailable: " + network);
            NetworkCapabilities networkCapabilities =
                    mConnectivityManager.getNetworkCapabilities(network);
            if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                if (!mIsWifiConnected) {
                    mIsWifiConnected = true;
                    mHandler.sendEmptyMessage(EVENT_WIFI_STATE_CHANGED);
                } else {
                    Log.d(mTag, "mIsWifiConnected is already true");
                }
            }
        }
    }

    final BroadcastReceiver mWifiStateIntentReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    Log.d(mTag, "onReceive action = " + action);
                    if (WifiManager.RSSI_CHANGED_ACTION.equals(action)) {
                        mWifiRssi = intent.getIntExtra(WifiManager.EXTRA_NEW_RSSI, -200);
                        Log.d(mTag, "Threshold on RSSI change = " + mWifiRssi);
                        if (mRegisteredThreshold != SIGNAL_STRENGTH_UNSPECIFIED
                                && mWifiRssi < 0
                                && mWifiRssi > SIGNAL_STRENGTH_UNSPECIFIED) {
                            mHandler.sendEmptyMessage(EVENT_WIFI_RSSI_CHANGED);
                        } else {
                            Log.d(mTag, "Current Registered_Vs_RSSI= " + mRegisteredThreshold);
                        }
                    }
                }
            };

    /**
     * Create WifiQualityMonitor object for accessing WifiManager, ConnectivityManager to monitor
     * RSSI, build parameters for registering threshold & callback listening.
     */
    WifiQualityMonitor(Context context) {
        super(QualityMonitor.class.getSimpleName() + "-I");
        mTag = WifiQualityMonitor.class.getSimpleName() + "-I";
        mContext = context;
        HandlerThread handlerThread = new HandlerThread(mTag);
        handlerThread.start();
        mHandler = new WiFiEventsHandler(handlerThread.getLooper());
        mConnectivityManager = mContext.getSystemService(ConnectivityManager.class);
        mWifiManager = mContext.getSystemService(WifiManager.class);
        if (mConnectivityManager == null) {
            Log.e(mTag, "Failed to get Connectivity Service");
        }
        if (mWifiManager == null) {
            Log.e(mTag, "Failed to get WiFi Service");
        }
        /* Network Callback for Threshold Register. */
        mWiFiThresholdCallback = new WiFiThresholdCallback();
        mBuilder =
                new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI);

        /* Network Callback for Threshold Register. */
        mWiFiStatusCallback = new WiFiStatusCallback();
        Log.d(mTag, "created WifiQualityMonitor");
    }

    /** Returns current Wifi RSSI information */
    @Override
    synchronized int getCurrentQuality(int accessNetwork, int measurementType) {
        return getCurrentQuality(measurementType);
    }

    // To-do:  To be handled for more Measurement types(e.g. WiFi PER).
    private int getCurrentQuality(int measurementType) {
        // TODO getConnectionInfo is deprecated.
        return mWifiManager.getConnectionInfo().getRssi();
    }

    /**
     * Register for threshold to receive callback based on criteria met, using WiFiThresholdCallback
     */
    @Override
    synchronized void registerThresholdChange(
            ThresholdCallback thresholdCallback,
            int netCapability,
            Threshold[] ths,
            int slotIndex) {
        Log.d(
                mTag,
                "registerThresholds for netCapability="
                        + QnsUtils.getNameOfNetCapability(netCapability));
        super.registerThresholdChange(thresholdCallback, netCapability, ths, slotIndex);
        updateThresholdsForNetCapability(netCapability, slotIndex, ths);
    }

    // To-do:  To be handled for more Measurement types(e.g. WiFi PER)
    @Override
    synchronized void unregisterThresholdChange(int netCapability, int slotIndex) {
        super.unregisterThresholdChange(netCapability, slotIndex);
        checkForThresholdRegistration();
    }

    @Override
    synchronized void updateThresholdsForNetCapability(
            int netCapability, int slotIndex, Threshold[] ths) {
        super.updateThresholdsForNetCapability(netCapability, slotIndex, ths);
        checkForThresholdRegistration();
    }

    @Override
    protected void notifyThresholdChange(String key, Threshold[] ths) {
        IThresholdListener listener = mThresholdCallbackMap.get(key);
        Log.d(mTag, "Notify Threshold Change to listener = " + listener);
        if (listener != null) {
            listener.onWifiThresholdChanged(ths);
        }
    }

    private void checkForThresholdRegistration() {
        // Current check is on measurement type as RSSI
        // Future to be enhanced for WiFi PER.
        mWifiRssi = getCurrentQuality(SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSI);
        Log.d(mTag, "Current Wifi Threshold = " + mWifiRssi);
        int newThreshold = SIGNAL_STRENGTH_UNSPECIFIED;
        for (Map.Entry<String, List<Threshold>> entry : mThresholdsList.entrySet()) {
            for (Threshold t : entry.getValue()) {
                if (t.getMeasurementType() == SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSI) {
                    // check ROVE IN cases:
                    if (t.getMatchType() == THRESHOLD_EQUAL_OR_LARGER) {
                        if (newThreshold > t.getThreshold()
                                || newThreshold == SIGNAL_STRENGTH_UNSPECIFIED) {
                            newThreshold = t.getThreshold();
                        }
                        // other ROVE OUT cases:
                    } else if (newThreshold < t.getThreshold()) {
                        newThreshold = t.getThreshold();
                    }
                }
            }
        }

        Log.d(
                mTag,
                "Registered threshold = "
                        + mRegisteredThreshold
                        + ", new threshold = "
                        + newThreshold);
        if (newThreshold != mRegisteredThreshold) {
            mRegisteredThreshold = newThreshold;
            updateRequest(mRegisteredThreshold != SIGNAL_STRENGTH_UNSPECIFIED);
        }
    }

    private void validateForWifiBackhaul() {
        mIsBackhaulRunning = false;
        for (Map.Entry<String, List<Threshold>> entry : mThresholdsList.entrySet()) {
            if (mWaitingThresholds.getOrDefault(entry.getKey(), false)) {
                continue;
            }
            for (Threshold th : entry.getValue()) {
                if (th.isMatching(mWifiRssi)) {
                    Log.d(mTag, "RSSI matched for threshold = " + th);
                    handleMatchingThreshold(entry.getKey(), th);
                }
            }
        }
    }

    private void handleMatchingThreshold(String key, Threshold th) {
        int backhaul = th.getWaitTime();
        if (backhaul < 0 && th.getMatchType() != QnsConstants.THRESHOLD_EQUAL_OR_SMALLER) {
            backhaul = BACKHAUL_TIMER_DEFAULT;
        }
        if (backhaul > 0) {
            mWaitingThresholds.put(key, true);
            if (!mIsBackhaulRunning) {
                Log.d(mTag, "Starting backhaul timer = " + backhaul);
                mHandler.sendEmptyMessageDelayed(EVENT_WIFI_NOTIFY_TIMER_EXPIRED, backhaul);
                mIsBackhaulRunning = true;
            }
        } else {
            checkAndNotifySignalStrength(key);
        }
    }

    private void validateThresholdsAfterBackHaul() {
        mWaitingThresholds.clear();
        for (Map.Entry<String, List<Threshold>> entry : mThresholdsList.entrySet()) {
            checkAndNotifySignalStrength(entry.getKey());
        }
    }

    private void checkAndNotifySignalStrength(String key) {
        List<Threshold> thresholdsList = mThresholdsList.get(key);
        if (thresholdsList == null) return;
        Log.d(mTag, "checkAndNotifySignalStrength for " + thresholdsList);
        List<Threshold> matchedThresholds = new ArrayList<>();
        Threshold threshold;
        for (Threshold th : thresholdsList) {
            if (th.isMatching(mWifiRssi)) {
                threshold = th.copy();
                threshold.setThreshold(mWifiRssi);
                matchedThresholds.add(threshold);
            }
        }
        if (matchedThresholds.size() > 0) {
            notifyThresholdChange(key, matchedThresholds.toArray(new Threshold[0]));
        }
    }

    private void updateRequest(boolean register) {
        if (!register) {
            unregisterCallback();
            if (mThresholdsList.isEmpty()) {
                registerWiFiReceiver(false);
                if (mHandler.hasMessages(EVENT_WIFI_NOTIFY_TIMER_EXPIRED)) {
                    Log.d(mTag, "Stop all active backhaul timers");
                    mHandler.removeMessages(EVENT_WIFI_NOTIFY_TIMER_EXPIRED);
                    mWaitingThresholds.clear();
                }
            }
        } else {
            Log.d(mTag, "Listening to threshold = " + mRegisteredThreshold);
            mBuilder.setSignalStrength(mRegisteredThreshold);
            registerCallback();
            registerWiFiReceiver(true);
        }
    }

    private void unregisterCallback() {
        if (mIsRegistered) {
            Log.d(mTag, "Unregister CallBack");
            mConnectivityManager.unregisterNetworkCallback(mWiFiThresholdCallback);
            mConnectivityManager.unregisterNetworkCallback(mWiFiStatusCallback);
            mIsRegistered = false;
        }
    }

    private void registerCallback() {
        unregisterCallback();
        if (!mIsRegistered) {
            Log.d(mTag, "Register CallBacks");
            mConnectivityManager.registerNetworkCallback(mBuilder.build(), mWiFiThresholdCallback);
            mConnectivityManager.registerNetworkCallback(
                    new NetworkRequest.Builder()
                            .clearCapabilities()
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                            .build(),
                    mWiFiStatusCallback);
            mIsRegistered = true;
        }
    }

    private synchronized void registerWiFiReceiver(boolean register) {
        if (register) {
            if (!mWifiStateIntentEnabled) {
                // Register for RSSI Changed Action intent.
                Log.d(mTag, "registerReceiver for RSSI_CHANGED");
                IntentFilter filter = new IntentFilter();
                filter.addAction(WifiManager.RSSI_CHANGED_ACTION);
                mContext.registerReceiver(mWifiStateIntentReceiver, filter);
                mWifiStateIntentEnabled = true;
            }
        } else {
            if (mWifiStateIntentEnabled) {
                Log.d(mTag, "unregisterReceiver RSSI_CHANGED");
                mContext.unregisterReceiver(mWifiStateIntentReceiver);
                mWifiStateIntentEnabled = false;
            }
        }
    }

    @VisibleForTesting
    int getRegisteredThreshold() {
        return mRegisteredThreshold;
    }

    private class WiFiEventsHandler extends Handler {
        WiFiEventsHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            Log.d(mTag, "handleMessage what = " + msg.what);
            switch (msg.what) {
                case EVENT_WIFI_STATE_CHANGED:
                    registerWiFiReceiver(mIsWifiConnected);
                    break;
                case EVENT_WIFI_RSSI_CHANGED:
                    validateForWifiBackhaul();
                    break;
                case EVENT_WIFI_NOTIFY_TIMER_EXPIRED:
                    mWifiRssi = getCurrentQuality(SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSI);
                    Log.d(mTag, "Backhaul timer expired, wifi rssi = " + mWifiRssi);
                    if (mRegisteredThreshold != SIGNAL_STRENGTH_UNSPECIFIED
                            && mWifiRssi < 0
                            && mWifiRssi > SIGNAL_STRENGTH_UNSPECIFIED) {
                        validateThresholdsAfterBackHaul();
                    }
                    break;
                default:
                    Log.d(mTag, "Not Handled !");
            }
        }
    }

    @VisibleForTesting
    @Override
    public void close() {
        registerWiFiReceiver(false);
        unregisterCallback();
        mWifiRssi = SIGNAL_STRENGTH_UNSPECIFIED;
        mIsRegistered = false;
        mRegisteredThreshold = SIGNAL_STRENGTH_UNSPECIFIED;
        Log.d(mTag, "closed WifiQualityMonitor");
    }

    @Override
    void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "------------------------------");
        pw.println(prefix + "WifiQualityMonitor:");
        super.dump(pw, prefix);
        pw.println(
                prefix
                        + "mIsWifiConnected="
                        + mIsWifiConnected
                        + ", mIsRegistered="
                        + mIsRegistered
                        + ", mWifiStateIntentEnabled="
                        + mWifiStateIntentEnabled
                        + ", mIsBackhaulRunning="
                        + mIsBackhaulRunning);
        pw.println(
                prefix
                        + "mWifiRssi="
                        + mWifiRssi
                        + ", mRegisteredThreshold="
                        + mRegisteredThreshold);
    }
}
