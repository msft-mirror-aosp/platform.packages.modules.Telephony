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

import android.annotation.NonNull;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkSpecifier;
import android.net.TelephonyNetworkSpecifier;
import android.net.TransportInfo;
import android.net.vcn.VcnTransportInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IwlanNetworkStatusTracker monitors if there is a network available for IWLAN and informs it to
 * registrants.
 */
class IwlanNetworkStatusTracker {
    private static final Boolean DBG = true;
    private static final String sLogTag = IwlanNetworkStatusTracker.class.getSimpleName();
    private static final int EVENT_BASE = 1000;
    private static final int EVENT_IWLAN_SERVICE_STATE_CHANGED = EVENT_BASE;
    private final Map<Integer, QnsRegistrantList> mIwlanNetworkListenersArray =
            new ConcurrentHashMap<>();
    private static final String LAST_KNOWN_COUNTRY_CODE_KEY = "last_known_country_code";
    private static IwlanNetworkStatusTracker sIwlanNetworkStatusTracker = null;
    private static final int INVALID_SUB_ID = -1;
    private final Context mContext;
    private DefaultNetworkCallback mDefaultNetworkCallback;
    private final HandlerThread mHandlerThread;
    private final ConnectivityManager mConnectivityManager;
    private final WifiManager mWifiManager;
    private WifiCountryCodeCallback mWifiCountryCodeCallback;
    private Handler mNetCbHandler = null;
    private boolean mWifiAvailable = false;
    private boolean mWifiToggleOn = false;
    private boolean mWifiCountryCodeRegistered = false;
    private Map<Integer, Boolean> mIwlanRegistered = new ConcurrentHashMap<>();
    private int mConnectedDds = INVALID_SUB_ID;
    private SparseArray<IwlanEventHandler> mHandlerSparseArray = new SparseArray<>();
    private SparseArray<IwlanAvailabilityInfo> mLastIwlanAvailabilityInfo = new SparseArray<>();
    private String mWifiCountryCode = null;

    enum LinkProtocolType {
        UNKNOWN,
        IPV4,
        IPV6,
        IPV4V6;
    }

    private static LinkProtocolType sLinkProtocolType = LinkProtocolType.UNKNOWN;

    class IwlanEventHandler extends Handler {
        private final int mSlotIndex;

        IwlanEventHandler(int slotId, Looper l) {
            super(l);
            mSlotIndex = slotId;
            List<Integer> events = new ArrayList<Integer>();
            events.add(QnsEventDispatcher.QNS_EVENT_CROSS_SIM_CALLING_ENABLED);
            events.add(QnsEventDispatcher.QNS_EVENT_CROSS_SIM_CALLING_DISABLED);
            events.add(QnsEventDispatcher.QNS_EVENT_WIFI_DISABLING);
            events.add(QnsEventDispatcher.QNS_EVENT_WIFI_ENABLED);
            QnsEventDispatcher.getInstance(mContext, mSlotIndex).registerEvent(events, this);
            QnsTelephonyListener.getInstance(mContext, mSlotIndex)
                    .registerIwlanServiceStateListener(
                            this, EVENT_IWLAN_SERVICE_STATE_CHANGED, null);
        }

        @Override
        public void handleMessage(Message message) {
            Log.d(sLogTag, "handleMessage msg=" + message.what);
            switch (message.what) {
                case QnsEventDispatcher.QNS_EVENT_CROSS_SIM_CALLING_ENABLED:
                    onCrossSimEnabledEvent(true, mSlotIndex);
                    break;
                case QnsEventDispatcher.QNS_EVENT_CROSS_SIM_CALLING_DISABLED:
                    onCrossSimEnabledEvent(false, mSlotIndex);
                    break;
                case QnsEventDispatcher.QNS_EVENT_WIFI_ENABLED:
                    onWifiEnabled();
                    break;
                case QnsEventDispatcher.QNS_EVENT_WIFI_DISABLING:
                    onWifiDisabling();
                    break;
                case EVENT_IWLAN_SERVICE_STATE_CHANGED:
                    QnsAsyncResult ar = (QnsAsyncResult) message.obj;
                    boolean isRegistered = (boolean) ar.mResult;
                    onIwlanServiceStateChanged(mSlotIndex, isRegistered);
                    break;
                default:
                    Log.d(sLogTag, "Unknown message received!");
                    break;
            }
        }
    }

    private IwlanNetworkStatusTracker(@NonNull Context context) {
        mContext = context;
        mHandlerThread = new HandlerThread(IwlanNetworkStatusTracker.class.getSimpleName());
        mHandlerThread.start();
        Looper looper = mHandlerThread.getLooper();
        mNetCbHandler = new Handler(looper);
        mConnectivityManager = mContext.getSystemService(ConnectivityManager.class);
        mWifiManager = mContext.getSystemService(WifiManager.class);
        mWifiCountryCodeCallback = new WifiCountryCodeCallback();
        mLastIwlanAvailabilityInfo.clear();
        registerDefaultNetworkCb();
        Log.d(sLogTag, "Registered with Connectivity Service");
    }

    private class WifiCountryCodeCallback implements WifiManager.ActiveCountryCodeChangedCallback {
        public void onActiveCountryCodeChanged(@NonNull String countryCode) {
            Log.d(sLogTag, "Received OnActive Wifi country code :" + countryCode);
            setWifiCountryCode(countryCode);
        }

        public void onCountryCodeInactive() {
            Log.d(sLogTag, "Received Inactive Wifi country code ");
            setWifiCountryCode("");
        }
    }

    private void setWifiCountryCode(String countryCode) {
        // Empty country code.
        if (countryCode == null || TextUtils.isEmpty(countryCode)) {
            Log.d(sLogTag, "Received empty wifi country code");
            mWifiCountryCode = null;
        } else {
            Log.d(sLogTag, "Before Upper case: " + countryCode);
            mWifiCountryCode = countryCode.toUpperCase(Locale.US);
        }
    }

    /** Register for Active country code changed event for Wifi connection */
    synchronized void registerWifiCountryCode() {
        if (mWifiManager != null && !mWifiCountryCodeRegistered) {
            Log.d(sLogTag, "Register Wifi Country code");
            mWifiCountryCodeRegistered = true;
            mWifiManager.registerActiveCountryCodeChangedCallback(
                    new QnsUtils.QnsExecutor(mNetCbHandler), mWifiCountryCodeCallback);
        }
    }

    /** unregister for Active country code changed event on Wifi connection */
    synchronized void unregisterWifiCountryCode() {
        if (mWifiCountryCodeRegistered) {
            Log.d(sLogTag, "Unregister Wifi Country code");
            mWifiCountryCodeRegistered = false;
            mWifiManager.unregisterActiveCountryCodeChangedCallback(mWifiCountryCodeCallback);
        }
    }

    static IwlanNetworkStatusTracker getInstance(@NonNull Context context) {
        if (sIwlanNetworkStatusTracker == null) {
            sIwlanNetworkStatusTracker = new IwlanNetworkStatusTracker(context);
        }
        return sIwlanNetworkStatusTracker;
    }

    @VisibleForTesting
    void onCrossSimEnabledEvent(boolean enabled, int slotId) {
        Log.d(sLogTag, "onCrossSimEnabledEvent enabled:" + enabled + " slotIndex:" + slotId);
        if (enabled) {
            int dds = INVALID_SUB_ID;
            NetworkSpecifier specifier;
            final Network activeNetwork = mConnectivityManager.getActiveNetwork();
            if (activeNetwork != null) {
                final NetworkCapabilities nc =
                        mConnectivityManager.getNetworkCapabilities(activeNetwork);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    specifier = nc.getNetworkSpecifier();
                    TransportInfo transportInfo = nc.getTransportInfo();
                    if (transportInfo != null && transportInfo instanceof VcnTransportInfo) {
                        dds = ((VcnTransportInfo) transportInfo).getSubId();
                    } else if (specifier != null
                            && specifier instanceof TelephonyNetworkSpecifier) {
                        dds = ((TelephonyNetworkSpecifier) specifier).getSubscriptionId();
                    }
                    if (dds != INVALID_SUB_ID && dds != mConnectedDds) {
                        mConnectedDds = dds;
                    }
                }
            }
            notifyIwlanNetworkStatus();
        } else {
            notifyIwlanNetworkStatus(true);
        }
    }

    @VisibleForTesting
    void onWifiEnabled() {
        mWifiToggleOn = true;
        registerWifiCountryCode();
        if (!mWifiAvailable) {
            for (Integer slotId : mIwlanNetworkListenersArray.keySet()) {
                if (!isCrossSimCallingCondition(slotId)
                        && mIwlanRegistered.containsKey(slotId)
                        && mIwlanRegistered.get(slotId)) {
                    mWifiAvailable = true;
                    notifyIwlanNetworkStatus(slotId, false);
                }
            }
        }
    }

    @VisibleForTesting
    void onWifiDisabling() {
        mWifiToggleOn = false;
        if (mWifiAvailable) {
            mWifiAvailable = false;
            notifyIwlanNetworkStatus(true);
        }
    }

    @VisibleForTesting
    void onIwlanServiceStateChanged(int slotId, boolean isRegistered) {
        mIwlanRegistered.put(slotId, isRegistered);
        notifyIwlanNetworkStatus(slotId, false);
    }

    private void notifyIwlanNetworkStatusToRegister(int slotId, QnsRegistrant r) {
        if (DBG) {
            Log.d(sLogTag, "notifyIwlanNetworkStatusToRegister");
        }
        IwlanAvailabilityInfo info = mLastIwlanAvailabilityInfo.get(slotId);
        if (info == null) {
            info = makeIwlanAvailabilityInfo(slotId);
            mLastIwlanAvailabilityInfo.put(slotId, info);
        }
        r.notifyResult(info);
    }

    private void registerDefaultNetworkCb() {
        if (mDefaultNetworkCallback == null) {
            mDefaultNetworkCallback = new DefaultNetworkCallback();
            mConnectivityManager.registerDefaultNetworkCallback(
                    mDefaultNetworkCallback, mNetCbHandler);
        }
    }

    private void unregisterDefaultNetworkCb() {
        if (mDefaultNetworkCallback != null) {
            mConnectivityManager.unregisterNetworkCallback(mDefaultNetworkCallback);
            mDefaultNetworkCallback = null;
        }
    }

    protected void close() {
        if (mDefaultNetworkCallback != null) {
            unregisterDefaultNetworkCb();
        }
        unregisterWifiCountryCode();

        for (int i = 0; i < mHandlerSparseArray.size(); i++) {
            int slotIndex = mHandlerSparseArray.keyAt(i);
            QnsEventDispatcher.getInstance(mContext, slotIndex)
                    .unregisterEvent(mHandlerSparseArray.get(slotIndex));
        }
        mHandlerThread.quit();
        mHandlerSparseArray.clear();
        mLastIwlanAvailabilityInfo.clear();
        mIwlanNetworkListenersArray.clear();
        mIwlanRegistered.clear();
        sIwlanNetworkStatusTracker = null;
    }

    void registerIwlanNetworksChanged(int slotId, Handler h, int what) {
        if (h != null) {
            QnsRegistrant r = new QnsRegistrant(h, what, null);
            if (mIwlanNetworkListenersArray.get(slotId) == null) {
                mIwlanNetworkListenersArray.put(slotId, new QnsRegistrantList());
            }
            mIwlanNetworkListenersArray.get(slotId).add(r);
            IwlanEventHandler iwlanEventHandler = mHandlerSparseArray.get(slotId);
            if (iwlanEventHandler == null) {
                iwlanEventHandler = new IwlanEventHandler(slotId, mHandlerThread.getLooper());
                mHandlerSparseArray.put(slotId, iwlanEventHandler);
                Log.d(sLogTag, "make IwlanEventHandler for slot:" + slotId);
            }
            iwlanEventHandler.post(() -> notifyIwlanNetworkStatusToRegister(slotId, r));
        }
    }

    void unregisterIwlanNetworksChanged(int slotId, Handler h) {
        if (mIwlanNetworkListenersArray.get(slotId) != null) {
            mIwlanNetworkListenersArray.get(slotId).remove(h);
        }
    }

    private IwlanAvailabilityInfo makeIwlanAvailabilityInfo(int slotId) {
        boolean iwlanEnable = false;
        boolean isCrossWfc = false;
        boolean isRegistered = false;
        boolean isBlockIpv6OnlyWifi =
                QnsCarrierConfigManager.getInstance(mContext, slotId).blockIpv6OnlyWifi();
        LinkProtocolType linkProtocolType = sLinkProtocolType;

        if (mIwlanRegistered.containsKey(slotId)) {
            isRegistered = mIwlanRegistered.get(slotId);
        }

        if (mWifiAvailable) {
            boolean blockWifi = false;
            if (isBlockIpv6OnlyWifi
                    && ((linkProtocolType == LinkProtocolType.UNKNOWN)
                            || (linkProtocolType == LinkProtocolType.IPV6))) {
                blockWifi = true;
            }
            iwlanEnable = mWifiAvailable && !blockWifi && isRegistered;
            isCrossWfc = false;
        } else if (isCrossSimCallingCondition(slotId) && isRegistered) {
            iwlanEnable = true;
            isCrossWfc = true;
        }
        if (DBG) {
            if (QnsUtils.isCrossSimCallingEnabled(mContext, slotId)) {
                Log.d(
                        sLogTag,
                        "makeIwlanAvailabilityInfo(slot:"
                                + slotId
                                + ") "
                                + "mWifiAvailable:"
                                + mWifiAvailable
                                + " mConnectedDds:"
                                + mConnectedDds
                                + " isRegistered:"
                                + isRegistered
                                + " subId:"
                                + QnsUtils.getSubId(mContext, slotId)
                                + " isDDS:"
                                + QnsUtils.isDefaultDataSubs(slotId)
                                + " iwlanEnable:"
                                + iwlanEnable
                                + " isCrossWfc:"
                                + isCrossWfc);
            } else {
                Log.d(
                        sLogTag,
                        "makeIwlanAvailabilityInfo(slot:"
                                + slotId
                                + ")"
                                + " mWifiAvailable:"
                                + mWifiAvailable
                                + " isRegistered:"
                                + isRegistered
                                + " iwlanEnable:"
                                + iwlanEnable
                                + "  isCrossWfc:"
                                + isCrossWfc
                                + " isBlockIpv6OnlyWifi:"
                                + isBlockIpv6OnlyWifi
                                + " linkProtocolType:"
                                + linkProtocolType);
            }
        }
        return new IwlanAvailabilityInfo(iwlanEnable, isCrossWfc);
    }

    private boolean isCrossSimCallingCondition(int slotId) {
        return QnsUtils.isCrossSimCallingEnabled(mContext, slotId)
                && !QnsUtils.isDefaultDataSubs(slotId)
                && QnsUtils.getSubId(mContext, slotId) != mConnectedDds
                && mConnectedDds != INVALID_SUB_ID;
    }

    private void notifyIwlanNetworkStatus() {
        notifyIwlanNetworkStatus(false);
    }

    private void notifyIwlanNetworkStatus(boolean notifyIwlanDisabled) {
        for (Integer slotId : mIwlanNetworkListenersArray.keySet()) {
            notifyIwlanNetworkStatus(slotId, notifyIwlanDisabled);
        }
    }

    private void notifyIwlanNetworkStatus(int slotId, boolean notifyIwlanDisabled) {
        Log.d(sLogTag, "notifyIwlanNetworkStatus for slot: " + slotId);
        IwlanAvailabilityInfo info = makeIwlanAvailabilityInfo(slotId);
        if (!info.getIwlanAvailable() && notifyIwlanDisabled) {
            Log.d(sLogTag, "setNotifyIwlanDisabled");
            info.setNotifyIwlanDisabled();
        }
        if (!info.equals(mLastIwlanAvailabilityInfo.get(slotId))) {
            Log.d(sLogTag, "notify updated info");
            if (mIwlanNetworkListenersArray.get(slotId) != null) {
                mIwlanNetworkListenersArray.get(slotId).notifyResult(info);
            }
            mLastIwlanAvailabilityInfo.put(slotId, info);
        }
    }

    class IwlanAvailabilityInfo {
        private boolean mIwlanAvailable = false;
        private boolean mIsCrossWfc = false;
        private boolean mNotifyIwlanDisabled = false;

        IwlanAvailabilityInfo(boolean iwlanAvailable, boolean crossWfc) {
            mIwlanAvailable = iwlanAvailable;
            mIsCrossWfc = crossWfc;
        }

        @VisibleForTesting
        void setNotifyIwlanDisabled() {
            mNotifyIwlanDisabled = true;
        }

        boolean getIwlanAvailable() {
            return mIwlanAvailable;
        }

        boolean isCrossWfc() {
            return mIsCrossWfc;
        }

        @VisibleForTesting
        boolean getNotifyIwlanDisabled() {
            return mNotifyIwlanDisabled;
        }

        boolean equals(IwlanAvailabilityInfo info) {
            if (info == null) {
                Log.d(sLogTag, " equals info is null");
                return false;
            }
            Log.d(
                    sLogTag,
                    "equals() IwlanAvailable: "
                            + mIwlanAvailable
                            + "/"
                            + info.mIwlanAvailable
                            + " IsCrossWfc: "
                            + mIsCrossWfc
                            + "/"
                            + info.mIsCrossWfc
                            + " NotifyIwlanDisabled: "
                            + mNotifyIwlanDisabled
                            + "/"
                            + info.mNotifyIwlanDisabled);
            return (mIwlanAvailable == info.mIwlanAvailable)
                    && (mIsCrossWfc == info.mIsCrossWfc)
                    && (mNotifyIwlanDisabled == info.mNotifyIwlanDisabled);
        }
    }

    final class DefaultNetworkCallback extends ConnectivityManager.NetworkCallback {
        /** Called when the framework connects and has declared a new network ready for use. */
        @Override
        public void onAvailable(Network network) {
            Log.d(sLogTag, "onAvailable: " + network);
            if (mConnectivityManager != null) {
                NetworkCapabilities nc = mConnectivityManager.getNetworkCapabilities(network);
                if (nc != null) {
                    if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        mWifiToggleOn = true;
                        mWifiAvailable = true;
                        mConnectedDds = INVALID_SUB_ID;
                        notifyIwlanNetworkStatus();
                    } else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        NetworkSpecifier specifier = nc.getNetworkSpecifier();
                        TransportInfo transportInfo = nc.getTransportInfo();
                        if (transportInfo != null && transportInfo instanceof VcnTransportInfo) {
                            mConnectedDds = ((VcnTransportInfo) transportInfo).getSubId();
                        } else if (specifier != null
                                && specifier instanceof TelephonyNetworkSpecifier) {
                            mConnectedDds =
                                    ((TelephonyNetworkSpecifier) specifier).getSubscriptionId();
                        }
                        mWifiAvailable = false;
                        notifyIwlanNetworkStatus();
                    }
                }
            }
        }

        /**
         * Called when the network is about to be lost, typically because there are no outstanding
         * requests left for it. This may be paired with a {@link NetworkCallback#onAvailable} call
         * with the new replacement network for graceful handover. This method is not guaranteed to
         * be called before {@link NetworkCallback#onLost} is called, for example in case a network
         * is suddenly disconnected.
         */
        @Override
        public void onLosing(Network network, int maxMsToLive) {
            Log.d(sLogTag, "onLosing: maxMsToLive: " + maxMsToLive + " network: " + network);
        }

        /**
         * Called when a network disconnects or otherwise no longer satisfies this request or *
         * callback.
         */
        @Override
        public void onLost(Network network) {
            Log.d(sLogTag, "onLost: " + network);
            if (mWifiAvailable) {
                mWifiAvailable = false;
            }
            if (mConnectedDds != INVALID_SUB_ID) {
                mConnectedDds = INVALID_SUB_ID;
            }
            sLinkProtocolType = LinkProtocolType.UNKNOWN;
            notifyIwlanNetworkStatus();
        }

        /** Called when the network corresponding to this request changes {@link LinkProperties}. */
        @Override
        public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
            Log.d(sLogTag, "onLinkPropertiesChanged: " + linkProperties);
            if (mWifiAvailable) {
                LinkProtocolType prevType = sLinkProtocolType;

                checkWifiLinkProtocolType(linkProperties);
                if (prevType != LinkProtocolType.IPV6
                        && sLinkProtocolType == LinkProtocolType.IPV6) {
                    notifyIwlanNetworkStatus(true);
                } else if (prevType != sLinkProtocolType) {
                    notifyIwlanNetworkStatus();
                }
            }
        }

        /** Called when access to the specified network is blocked or unblocked. */
        @Override
        public void onBlockedStatusChanged(Network network, boolean blocked) {
            Log.d(sLogTag, "onBlockedStatusChanged: " + " BLOCKED:" + blocked);
        }

        @Override
        public void onCapabilitiesChanged(
                Network network, NetworkCapabilities networkCapabilities) {
            // onCapabilitiesChanged is guaranteed to be called immediately after onAvailable per
            // API
            Log.d(sLogTag, "onCapabilitiesChanged: " + network);
            NetworkCapabilities nc = networkCapabilities;
            if (nc != null) {
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    if (!mWifiAvailable && mWifiToggleOn) {
                        mWifiAvailable = true;
                        mConnectedDds = INVALID_SUB_ID;
                        notifyIwlanNetworkStatus();
                    } else {
                        Log.d(sLogTag, "OnCapability : Wifi Available already true");
                    }
                } else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    int dds = INVALID_SUB_ID;
                    mWifiAvailable = false;
                    NetworkSpecifier specifier = nc.getNetworkSpecifier();
                    TransportInfo transportInfo = nc.getTransportInfo();
                    if (transportInfo != null && transportInfo instanceof VcnTransportInfo) {
                        dds = ((VcnTransportInfo) transportInfo).getSubId();
                    } else if (specifier != null
                            && specifier instanceof TelephonyNetworkSpecifier) {
                        dds = ((TelephonyNetworkSpecifier) specifier).getSubscriptionId();
                    }
                    if (dds != INVALID_SUB_ID && dds != mConnectedDds) {
                        mConnectedDds = dds;
                        notifyIwlanNetworkStatus();
                    }
                }
            }
        }
    }

    private void checkWifiLinkProtocolType(@NonNull LinkProperties linkProperties) {
        boolean hasIpv4 = false;
        boolean hasIpv6 = false;
        for (LinkAddress linkAddress : linkProperties.getLinkAddresses()) {
            InetAddress inetaddr = linkAddress.getAddress();
            if (inetaddr instanceof Inet4Address) {
                hasIpv4 = true;
            } else if (inetaddr instanceof Inet6Address) {
                hasIpv6 = true;
            }
        }
        if (hasIpv4 && hasIpv6) {
            sLinkProtocolType = LinkProtocolType.IPV4V6;
        } else if (hasIpv4) {
            sLinkProtocolType = LinkProtocolType.IPV4;
        } else if (hasIpv6) {
            sLinkProtocolType = LinkProtocolType.IPV6;
        }
    }

    /**
     * This method returns if current country code is outside the home country.
     *
     * @return True if it is international roaming, otherwise false.
     */
    boolean isInternationalRoaming(Context context, int slotId) {
        boolean isInternationalRoaming = false;
        TelephonyManager telephonyManager = mContext.getSystemService(TelephonyManager.class);

        if (telephonyManager != null) {
            telephonyManager =
                    telephonyManager.createForSubscriptionId(QnsUtils.getSubId(context, slotId));

            if (telephonyManager != null) {
                String simCountry = telephonyManager.getSimCountryIso();
                Log.d(
                        sLogTag,
                        "SIM country = " + simCountry + ", current country = " + mWifiCountryCode);
                if (mWifiCountryCode != null
                        && !TextUtils.isEmpty(simCountry)
                        && !TextUtils.isEmpty(mWifiCountryCode)) {
                    isInternationalRoaming = !simCountry.equalsIgnoreCase(mWifiCountryCode);
                }
            }
        }
        return isInternationalRoaming;
    }

    /** This method is only for Testing */
    @VisibleForTesting
    String getLastUpdatedWifiCountryCode() {
        return mWifiCountryCode;
    }

    /**
     * Dumps the state of {@link QualityMonitor}
     *
     * @param pw {@link PrintWriter} to write the state of the object.
     * @param prefix String to append at start of dumped log.
     */
    void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "------------------------------");
        pw.println(prefix + "IwlanNetworkStatusTracker:");
        pw.println(
                prefix
                        + "mWifiAvailable="
                        + mWifiAvailable
                        + ", mWifiToggleOn="
                        + mWifiToggleOn
                        + ", mConnectedDds="
                        + mConnectedDds
                        + ", mIwlanRegistered="
                        + mIwlanRegistered);
        pw.println(prefix + "sLinkProtocolType=" + sLinkProtocolType);
        pw.println(prefix + "getLastKnownCountryCode=" + mWifiCountryCode);
    }
}
