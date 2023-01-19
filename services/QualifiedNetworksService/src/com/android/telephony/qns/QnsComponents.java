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

import android.content.Context;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;

/**
 * QnsComponents stores all the components used in QNS. It maintains the lifecycle of QNS classes.
 * All QNS components are initialed at construction of this class.
 */
class QnsComponents {

    private final String mLogTag = QnsComponents.class.getSimpleName();
    private final Context mContext;
    private final SparseArray<AlternativeEventListener> mAlternativeEventListeners;
    private final SparseArray<CellularNetworkStatusTracker> mCellularNetworkStatusTrackers;
    private final SparseArray<CellularQualityMonitor> mCellularQualityMonitors;
    private final SparseArray<QnsImsManager> mQnsImsManagers;
    private final SparseArray<QnsCarrierConfigManager> mQnsCarrierConfigManagers;
    private final SparseArray<QnsEventDispatcher> mQnsEventDispatchers;
    private final SparseArray<QnsProvisioningListener> mQnsProvisioningListeners;
    private final SparseArray<QnsTelephonyListener> mQnsTelephonyListeners;
    private final SparseArray<QnsCallStatusTracker> mQnsCallStatusTracker;
    private final SparseArray<WifiBackhaulMonitor> mWifiBackhaulMonitors;
    private final List<Integer> mSlotIds;
    private IwlanNetworkStatusTracker mIwlanNetworkStatusTracker;
    private WifiQualityMonitor mWifiQualityMonitor;

    /** Constructor to instantiate QnsComponents class. */
    QnsComponents(Context context) {
        mContext = context;
        mAlternativeEventListeners = new SparseArray<>();
        mCellularNetworkStatusTrackers = new SparseArray<>();
        mCellularQualityMonitors = new SparseArray<>();
        mQnsImsManagers = new SparseArray<>();
        mQnsCarrierConfigManagers = new SparseArray<>();
        mQnsEventDispatchers = new SparseArray<>();
        mQnsProvisioningListeners = new SparseArray<>();
        mQnsTelephonyListeners = new SparseArray<>();
        mQnsCallStatusTracker = new SparseArray<>();
        mWifiBackhaulMonitors = new SparseArray<>();
        mSlotIds = new ArrayList<>();
    }

    /** It initialises all the QNS components for given slotId */
    synchronized void createQnsComponents(int slotId) {
        mSlotIds.add(slotId);
        mQnsTelephonyListeners.put(slotId, new QnsTelephonyListener(mContext, slotId));
        mQnsImsManagers.put(slotId, new QnsImsManager(mContext, slotId));
        mAlternativeEventListeners.put(
                slotId,
                new AlternativeEventListener(mContext, mQnsTelephonyListeners.get(slotId), slotId));
        mCellularNetworkStatusTrackers.put(
                slotId,
                new CellularNetworkStatusTracker(mQnsTelephonyListeners.get(slotId), slotId));
        mCellularQualityMonitors.put(
                slotId,
                new CellularQualityMonitor(mContext, mQnsTelephonyListeners.get(slotId), slotId));
        mQnsProvisioningListeners.put(
                slotId, new QnsProvisioningListener(mContext, mQnsImsManagers.get(slotId), slotId));
        mQnsEventDispatchers.put(
                slotId,
                new QnsEventDispatcher(
                        mContext,
                        mQnsProvisioningListeners.get(slotId),
                        mQnsImsManagers.get(slotId),
                        slotId));
        mQnsCarrierConfigManagers.put(
                slotId,
                new QnsCarrierConfigManager(mContext, mQnsEventDispatchers.get(slotId), slotId));
        mQnsCallStatusTracker.put(
                slotId,
                new QnsCallStatusTracker(mQnsTelephonyListeners.get(slotId),
                        mQnsCarrierConfigManagers.get(slotId), slotId));
        mWifiBackhaulMonitors.put(
                slotId,
                new WifiBackhaulMonitor(
                        mContext,
                        mQnsCarrierConfigManagers.get(slotId),
                        mQnsImsManagers.get(slotId),
                        slotId));

        if (mWifiQualityMonitor == null) {
            mWifiQualityMonitor = new WifiQualityMonitor(mContext);
        }
        if (mIwlanNetworkStatusTracker == null) {
            mIwlanNetworkStatusTracker = new IwlanNetworkStatusTracker(mContext);
        }
        mIwlanNetworkStatusTracker.initBySlotIndex(
                mQnsCarrierConfigManagers.get(slotId),
                mQnsEventDispatchers.get(slotId),
                mQnsImsManagers.get(slotId),
                mQnsTelephonyListeners.get(slotId),
                slotId);

        Log.d(mLogTag, "QnsComponents created for slot " + slotId);
    }

    @VisibleForTesting
    QnsComponents(
            Context context,
            AlternativeEventListener alternativeEventListener,
            CellularNetworkStatusTracker cellularNetworkStatusTracker,
            CellularQualityMonitor cellularQualityMonitor,
            IwlanNetworkStatusTracker iwlanNetworkStatusTracker,
            QnsImsManager qnsImsManager,
            QnsCarrierConfigManager qnsCarrierConfigManager,
            QnsEventDispatcher qnsEventDispatcher,
            QnsProvisioningListener qnsProvisioningListener,
            QnsTelephonyListener qnsTelephonyListener,
            QnsCallStatusTracker qnsCallStatusTracker,
            WifiBackhaulMonitor wifiBackhaulMonitor,
            WifiQualityMonitor wifiQualityMonitor,
            int slotId) {
        this(context);
        mSlotIds.add(slotId);
        mQnsTelephonyListeners.put(slotId, qnsTelephonyListener);
        mQnsImsManagers.put(slotId, qnsImsManager);
        mAlternativeEventListeners.put(slotId, alternativeEventListener);
        mCellularNetworkStatusTrackers.put(slotId, cellularNetworkStatusTracker);
        mCellularQualityMonitors.put(slotId, cellularQualityMonitor);
        mQnsCallStatusTracker.put(slotId, qnsCallStatusTracker);

        mQnsProvisioningListeners.put(slotId, qnsProvisioningListener);
        mQnsEventDispatchers.put(slotId, qnsEventDispatcher);
        mQnsCarrierConfigManagers.put(slotId, qnsCarrierConfigManager);
        mWifiBackhaulMonitors.put(slotId, wifiBackhaulMonitor);

        mWifiQualityMonitor = wifiQualityMonitor;
        mIwlanNetworkStatusTracker = iwlanNetworkStatusTracker;
        mIwlanNetworkStatusTracker.initBySlotIndex(
                qnsCarrierConfigManager,
                qnsEventDispatcher,
                qnsImsManager,
                qnsTelephonyListener,
                slotId);
    }

    /** Returns instance of AlternativeEventListener for given slotId. */
    AlternativeEventListener getAlternativeEventListener(int slotId) {
        return mAlternativeEventListeners.get(slotId);
    }

    /** Returns instance of CellularNetworkStatusTracker for given slotId. */
    CellularNetworkStatusTracker getCellularNetworkStatusTracker(int slotId) {
        return mCellularNetworkStatusTrackers.get(slotId);
    }

    /** Returns instance of CellularQualityMonitor for given slotId. */
    CellularQualityMonitor getCellularQualityMonitor(int slotId) {
        return mCellularQualityMonitors.get(slotId);
    }

    /** Returns instance of QnsCallStatusTracker for given slotId. */
    QnsCallStatusTracker getQnsCallStatusTracker(int slotId) {
        return mQnsCallStatusTracker.get(slotId);
    }

    /** Returns instance of QnsImsManager for given slotId. */
    QnsImsManager getQnsImsManager(int slotId) {
        return mQnsImsManagers.get(slotId);
    }

    /** Returns instance of QnsCarrierConfigManager for given slotId. */
    QnsCarrierConfigManager getQnsCarrierConfigManager(int slotId) {
        return mQnsCarrierConfigManagers.get(slotId);
    }

    /** Returns instance of QnsEventDispatcher for given slotId. */
    QnsEventDispatcher getQnsEventDispatcher(int slotId) {
        return mQnsEventDispatchers.get(slotId);
    }

    /** Returns instance of QnsProvisioningListener for given slotId. */
    QnsProvisioningListener getQnsProvisioningListener(int slotId) {
        return mQnsProvisioningListeners.get(slotId);
    }

    /** Returns instance of QnsTelephonyListener for given slotId. */
    QnsTelephonyListener getQnsTelephonyListener(int slotId) {
        return mQnsTelephonyListeners.get(slotId);
    }

    /** Returns instance of WifiBackhaulMonitor for given slotId. */
    WifiBackhaulMonitor getWifiBackhaulMonitor(int slotId) {
        return mWifiBackhaulMonitors.get(slotId);
    }

    /** Returns instance of IwlanNetworkStatusTracker. */
    IwlanNetworkStatusTracker getIwlanNetworkStatusTracker() {
        return mIwlanNetworkStatusTracker;
    }

    /** Returns instance of WifiQualityMonitor. */
    WifiQualityMonitor getWifiQualityMonitor() {
        return mWifiQualityMonitor;
    }

    /** Returns context. */
    Context getContext() {
        return mContext;
    }

    /** It closes all the components of QNS of given slotId. */
    synchronized void closeComponents(int slotId) {
        if (!mSlotIds.contains(slotId)) return;
        mIwlanNetworkStatusTracker.closeBySlotIndex(slotId);
        if (mSlotIds.size() == 1) {
            mIwlanNetworkStatusTracker.close();
            mWifiQualityMonitor.close();
            mIwlanNetworkStatusTracker = null;
            mWifiQualityMonitor = null;
        }

        WifiBackhaulMonitor wifiBackhaulMonitor = mWifiBackhaulMonitors.get(slotId);
        if (wifiBackhaulMonitor != null) {
            mWifiBackhaulMonitors.remove(slotId);
            wifiBackhaulMonitor.close();
        }
        QnsCallStatusTracker qnsCallStatusTracker = mQnsCallStatusTracker.get(slotId);
        if (qnsCallStatusTracker != null) {
            mQnsCallStatusTracker.remove(slotId);
            qnsCallStatusTracker.close();
        }
        QnsCarrierConfigManager qnsCarrierConfigManager = mQnsCarrierConfigManagers.get(slotId);
        if (qnsCarrierConfigManager != null) {
            mQnsCarrierConfigManagers.remove(slotId);
            qnsCarrierConfigManager.close();
        }
        QnsEventDispatcher qnsEventDispatcher = mQnsEventDispatchers.get(slotId);
        if (qnsEventDispatcher != null) {
            mQnsEventDispatchers.remove(slotId);
            qnsEventDispatcher.close();
        }
        QnsProvisioningListener qnsProvisioningListener = mQnsProvisioningListeners.get(slotId);
        if (qnsProvisioningListener != null) {
            mQnsProvisioningListeners.remove(slotId);
            qnsProvisioningListener.close();
        }
        CellularQualityMonitor cellularQualityMonitor = mCellularQualityMonitors.get(slotId);
        if (cellularQualityMonitor != null) {
            mCellularQualityMonitors.remove(slotId);
            cellularQualityMonitor.close();
        }
        CellularNetworkStatusTracker cellularTracker = mCellularNetworkStatusTrackers.get(slotId);
        if (cellularTracker != null) {
            mCellularNetworkStatusTrackers.remove(slotId);
            cellularTracker.close();
        }
        AlternativeEventListener alternativeEventListener = mAlternativeEventListeners.get(slotId);
        if (alternativeEventListener != null) {
            mAlternativeEventListeners.remove(slotId);
            alternativeEventListener.close();
        }
        QnsImsManager qnsImsManager = mQnsImsManagers.get(slotId);
        if (qnsImsManager != null) {
            mQnsImsManagers.remove(slotId);
            qnsImsManager.close();
        }
        QnsTelephonyListener qnsTelephonyListener = mQnsTelephonyListeners.get(slotId);
        if (qnsTelephonyListener != null) {
            mQnsTelephonyListeners.remove(slotId);
            qnsTelephonyListener.close();
        }

        mSlotIds.remove(Integer.valueOf(slotId));
        Log.d(mLogTag, "QnsComponents closed for slot " + slotId);
    }
}
