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

import static android.telephony.data.ThrottleStatus.THROTTLE_TYPE_ELAPSED_TIME;

import android.annotation.NonNull;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.telephony.data.QualifiedNetworksService;
import android.telephony.data.ThrottleStatus;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementation of the qualified networks service, which is a service providing up-to-date
 * qualified network information to the frameworks for data handover control. A qualified network is
 * defined as an access network that is ready for bringing up data connection for given APN types.
 */
public class QualifiedNetworksServiceImpl extends QualifiedNetworksService {

    static final String LOG_TAG = QualifiedNetworksServiceImpl.class.getSimpleName();
    private static final boolean DBG = true;
    private static final int QNS_CONFIGURATION_LOADED = 1;
    private static final int QUALIFIED_NETWORKS_CHANGED = 2;
    private static final int QNS_CONFIGURATION_CHANGED = 3;
    protected HashMap<Integer, NetworkAvailabilityProviderImpl> mProviderMap = new HashMap<>();
    protected Context mContext;

    /** Default constructor. */
    public QualifiedNetworksServiceImpl() {
        super();
        log("created QualifiedNetworksServiceImpl.");
    }

    /**
     * Create the instance of {@link NetworkAvailabilityProvider}. Vendor qualified network service
     * must override this method to facilitate the creation of {@link NetworkAvailabilityProvider}
     * instances. The system will call this method after binding the qualified networks service for
     * each active SIM slot index.
     *
     * @param slotIndex SIM slot index the qualified networks service associated with.
     * @return Qualified networks service instance
     */
    @Override
    public NetworkAvailabilityProvider onCreateNetworkAvailabilityProvider(int slotIndex) {
        log("Qualified Networks Service created for slot " + slotIndex);
        if (!QnsUtils.isValidSlotIndex(mContext, slotIndex)) {
            log("Invalid slotIndex " + slotIndex + ". fail to create NetworkAvailabilityProvider");
            return null;
        }
        NetworkAvailabilityProviderImpl provider = new NetworkAvailabilityProviderImpl(slotIndex);
        mProviderMap.put(slotIndex, provider);
        return provider;
    }

    @Override
    public void onCreate() {
        log("onCreate");
        mContext = getApplicationContext();
    }

    @Override
    public void onDestroy() {
        log("onDestroy");
        for (NetworkAvailabilityProviderImpl provider : mProviderMap.values()) {
            provider.close();
        }
        mProviderMap.clear();
        super.onDestroy();
        IwlanNetworkStatusTracker.getInstance(mContext).close();
        WifiQualityMonitor.getInstance(mContext).dispose();
    }

    protected void log(String s) {
        if (DBG) Log.d(LOG_TAG, s);
    }

    static class QualifiedNetworksInfo {
        int mNetCapability;
        List<Integer> mAccessNetworkTypes;

        QualifiedNetworksInfo(int netCapability, List<Integer> accessNetworkTypes) {
            mNetCapability = netCapability;
            mAccessNetworkTypes = accessNetworkTypes;
        }

        int getNetCapability() {
            return mNetCapability;
        }

        void setNetCapability(int netCapability) {
            mNetCapability = netCapability;
        }

        List<Integer> getAccessNetworkTypes() {
            return mAccessNetworkTypes;
        }

        void setAccessNetworkTypes(List<Integer> accessNetworkTypes) {
            mAccessNetworkTypes = accessNetworkTypes;
        }
    }

    /**
     * The network availability provider implementation class. The qualified network service must
     * extend this class to report the available networks for data connection setup. Note that each
     * instance of network availability provider is associated with slot.
     */
    public class NetworkAvailabilityProviderImpl extends NetworkAvailabilityProvider {
        private final String mLogTag;
        private final int mSlotIndex;
        @VisibleForTesting Handler mHandler;
        @VisibleForTesting Handler mConfigHandler;
        private final HandlerThread mHandlerThread;
        private final HandlerThread mConfigHandlerThread;
        private boolean mIsQnsConfigChangeRegistered = false;

        protected QnsCarrierConfigManager mConfigManager;
        protected QnsTelephonyListener mQnsTelephonyListener;
        protected HashMap<Integer, AccessNetworkEvaluator> mEvaluators = new HashMap<>();
        private boolean mIsClosed;
        protected QnsProvisioningListener mProvisioningManager;

        /**
         * Constructor
         *
         * @param slotIndex SIM slot index the network availability provider associated with.
         */
        public NetworkAvailabilityProviderImpl(int slotIndex) {
            super(slotIndex);
            mLogTag = NetworkAvailabilityProviderImpl.class.getSimpleName() + "_" + slotIndex;

            mIsClosed = false;
            mSlotIndex = slotIndex;
            mHandlerThread =
                    new HandlerThread(NetworkAvailabilityProviderImpl.class.getSimpleName());
            mHandlerThread.start();
            mHandler =
                    new Handler(mHandlerThread.getLooper()) {
                        @Override
                        public void handleMessage(Message message) {
                            QnsAsyncResult ar = (QnsAsyncResult) message.obj;
                            switch (message.what) {
                                case QUALIFIED_NETWORKS_CHANGED:
                                    onQualifiedNetworksChanged((QualifiedNetworksInfo) ar.mResult);
                                    break;
                                default:
                                    log("got event " + message.what + " never reached here.");
                                    break;
                            }
                        }
                    };
            mConfigHandlerThread =
                    new HandlerThread(
                            NetworkAvailabilityProviderImpl.class.getSimpleName() + "config");
            mConfigHandlerThread.start();
            mConfigHandler =
                    new Handler(mConfigHandlerThread.getLooper()) {
                        @Override
                        public void handleMessage(Message message) {
                            switch (message.what) {
                                case QNS_CONFIGURATION_LOADED:
                                    onConfigurationLoaded();
                                    break;
                                case QNS_CONFIGURATION_CHANGED:
                                    log("Qns Configuration changed received");
                                    onConfigurationChanged();
                                    break;
                                default:
                                    log("got event " + message.what + " never reached here.");
                                    break;
                            }
                        }
                    };

            mConfigManager = QnsCarrierConfigManager.getInstance(mContext, mSlotIndex);
            mConfigManager.registerForConfigurationLoaded(mConfigHandler, QNS_CONFIGURATION_LOADED);
            mQnsTelephonyListener = QnsTelephonyListener.getInstance(mContext, mSlotIndex);
            mProvisioningManager = QnsProvisioningListener.getInstance(mContext, mSlotIndex);
        }

        protected void onConfigurationLoaded() {
            log("onConfigurationLoaded");
            // Register for Upgradable Config items load case
            if (!mIsQnsConfigChangeRegistered) {
                mConfigManager.registerForConfigurationChanged(
                        mConfigHandler, QNS_CONFIGURATION_CHANGED);
                mIsQnsConfigChangeRegistered = true;
            }

            HashMap<Integer, AccessNetworkEvaluator> evaluators = new HashMap<>();
            List<Integer> netCapabilities = mConfigManager.getQnsSupportedNetCapabilities();

            for (int netCapability : netCapabilities) {
                int transportType = mConfigManager.getQnsSupportedTransportType(netCapability);
                if (transportType < 0
                        || transportType == QnsConstants.TRANSPORT_TYPE_ALLOWED_WWAN) {
                    continue;
                }
                if (mEvaluators.get(netCapability) != null) {
                    AccessNetworkEvaluator evaluator = mEvaluators.remove(netCapability);
                    evaluators.put(netCapability, evaluator);
                    // reuse evaluator
                    evaluator.rebuild();
                } else {
                    AccessNetworkEvaluator evaluator =
                            new AccessNetworkEvaluator(getSlotIndex(), netCapability, mContext);
                    evaluator.registerForQualifiedNetworksChanged(
                            mHandler, QUALIFIED_NETWORKS_CHANGED);
                    evaluators.put(netCapability, evaluator);
                }
            }
            for (Integer capability : mEvaluators.keySet()) {
                AccessNetworkEvaluator evaluator = mEvaluators.get(capability);
                evaluator.unregisterForQualifiedNetworksChanged(mHandler);
                evaluator.close();
            }
            mEvaluators.clear();
            mEvaluators = evaluators;
        }

        protected void onConfigurationChanged() {}

        private void onQualifiedNetworksChanged(QualifiedNetworksInfo info) {
            log(
                    "Calling updateQualifiedNetworkTypes for mNetCapability["
                            + QnsUtils.getNameOfNetCapability(info.getNetCapability())
                            + "], preferred networks "
                            + QnsUtils.getStringAccessNetworkTypes(info.getAccessNetworkTypes()));

            int apnType = QnsUtils.getApnTypeFromNetCapability(info.getNetCapability());
            updateQualifiedNetworkTypes(apnType, info.getAccessNetworkTypes());
        }

        @Override
        public void reportThrottleStatusChanged(@NonNull List<ThrottleStatus> statuses) {
            log("reportThrottleStatusChanged: statuses size=" + statuses.size());
            for (ThrottleStatus ts : statuses) {
                int netCapability;
                try {
                    netCapability = QnsUtils.getNetCapabilityFromApnType(ts.getApnType());
                } catch (IllegalArgumentException e) {
                    continue;
                }
                log("ThrottleStatus:" + ts + ", netCapability" + netCapability);
                if (ts.getSlotIndex() != getSlotIndex()) {
                    continue;
                }
                AccessNetworkEvaluator evaluator = mEvaluators.get(netCapability);
                if (evaluator == null) {
                    continue;
                }
                boolean isThrottle = ts.getThrottleType() == THROTTLE_TYPE_ELAPSED_TIME;
                evaluator.updateThrottleStatus(
                        isThrottle, ts.getThrottleExpiryTimeMillis(), ts.getTransportType());
            }
        }

        @Override
        public synchronized void close() {
            mConfigHandler.post(this::onClose);
        }

        private synchronized void onClose() {
            if (!mIsClosed) {
                mIsClosed = true;
                log("closing NetworkAvailabilityProviderImpl");
                mConfigManager.unregisterForConfigurationLoaded(mConfigHandler);
                mConfigManager.unregisterForConfigurationChanged(mConfigHandler);
                mIsQnsConfigChangeRegistered = false;
                for (Integer netCapability : mEvaluators.keySet()) {
                    AccessNetworkEvaluator evaluator = mEvaluators.get(netCapability);
                    evaluator.unregisterForQualifiedNetworksChanged(mHandler);
                    evaluator.close();
                }
                mConfigManager.close();
                CellularQualityMonitor.getInstance(mContext, mSlotIndex).dispose();
                CellularNetworkStatusTracker.getInstance(mContext, mSlotIndex).close();
                mQnsTelephonyListener.close();
                mEvaluators.clear();
            }
        }

        protected void log(String s) {
            if (DBG) Log.d(mLogTag, s);
        }

        /**
         * Dumps the state of {@link QualityMonitor}
         *
         * @param pw {@link PrintWriter} to write the state of the object.
         * @param prefix String to append at start of dumped log.
         */
        void dump(PrintWriter pw, String prefix) {
            pw.println(prefix + "------------------------------");
            pw.println(prefix + "NetworkAvailabilityProviderImpl[" + mSlotIndex + "]:");
            for (Map.Entry<Integer, AccessNetworkEvaluator> aneMap : mEvaluators.entrySet()) {
                AccessNetworkEvaluator ane = aneMap.getValue();
                ane.dump(pw, prefix + "  ");
            }
            mQnsTelephonyListener.dump(pw, prefix + "  ");
            CellularQualityMonitor.getInstance(mContext, mSlotIndex).dump(pw, prefix + "  ");
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        super.dump(fd, pw, args);
        pw.println("QualifiedNetworksServiceImpl:");
        pw.println("==============================");
        for (Map.Entry<Integer, NetworkAvailabilityProviderImpl> providerMap :
                mProviderMap.entrySet()) {
            NetworkAvailabilityProviderImpl provider = providerMap.getValue();
            provider.dump(pw, "  ");
        }
        IwlanNetworkStatusTracker.getInstance(mContext).dump(pw, "  ");
        WifiQualityMonitor.getInstance(mContext).dump(pw, "  ");
        pw.println("==============================");
    }
}
