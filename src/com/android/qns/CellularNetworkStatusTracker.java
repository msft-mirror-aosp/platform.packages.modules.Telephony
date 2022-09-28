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
import android.os.Handler;
import android.util.Log;
import android.util.SparseArray;

/**
 * monitor cellular network status like attach or detach. The CellularNetworkStatusTracker is used
 * as data to evaluate cellular availability and coverage. Availability : Whether the data
 * registration state is in-service. Coverage : Whether the data registration state is Home or Roam.
 */
public class CellularNetworkStatusTracker {

    private static final SparseArray<CellularNetworkStatusTracker>
            sCellularNetworkStatusTrackerMap = new SparseArray<>();
    private final String LOG_TAG;
    private final int mSlotIndex;
    private final QnsTelephonyListener mQnsTelephonyListener;

    private CellularNetworkStatusTracker(@NonNull Context context, int slotIndex) {
        LOG_TAG =
                QnsConstants.QNS_TAG
                        + "_"
                        + CellularNetworkStatusTracker.class.getSimpleName()
                        + "_"
                        + slotIndex;
        mSlotIndex = slotIndex;
        mQnsTelephonyListener = QnsTelephonyListener.getInstance(context, mSlotIndex);
    }

    public static CellularNetworkStatusTracker getInstance(
            @NonNull Context context, int slotIndex) {
        CellularNetworkStatusTracker cellStatusTracker =
                sCellularNetworkStatusTrackerMap.get(slotIndex);
        if (cellStatusTracker != null) {
            return cellStatusTracker;
        }
        cellStatusTracker = new CellularNetworkStatusTracker(context, slotIndex);
        sCellularNetworkStatusTrackerMap.put(slotIndex, cellStatusTracker);
        return cellStatusTracker;
    }

    protected void log(String s) {
        Log.d(LOG_TAG, s);
    }

    /**
     * Register for QnsTelephonyInfoChanged
     *
     * @param netCapability Network Capability
     * @param h Handler
     * @param what Event
     */
    public void registerQnsTelephonyInfoChanged(int netCapability, Handler h, int what) {
        mQnsTelephonyListener.registerQnsTelephonyInfoChanged(netCapability, h, what, null, true);
    }

    /**
     * Unregister for QnsTelephonyInfoChanged
     *
     * @param netCapability Network Capability
     * @param h Handler
     */
    public void unregisterQnsTelephonyInfoChanged(int netCapability, Handler h) {
        mQnsTelephonyListener.unregisterQnsTelephonyInfoChanged(netCapability, h);
    }

    public boolean isAirplaneModeEnabled() {
        return mQnsTelephonyListener.isAirplaneModeEnabled();
    }

    public boolean isSupportVoPS() {
        return mQnsTelephonyListener.isSupportVoPS();
    }

    public boolean isVoiceBarring() {
        return mQnsTelephonyListener.isVoiceBarring();
    }

    public void close() {
        sCellularNetworkStatusTrackerMap.remove(mSlotIndex);
    }
}
