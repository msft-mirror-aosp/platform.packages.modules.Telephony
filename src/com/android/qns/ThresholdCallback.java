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

import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.concurrent.Executor;

public class ThresholdCallback {
    public static final String TAG = ThresholdCallback.class.getSimpleName();
    public IThresholdListener callback;

    public void init(Executor executor) {
        if (executor == null) throw new IllegalArgumentException("executor cannot be null");
        callback = new ThresholdListener(executor, this);
    }

    public interface WifiThresholdListener {
        void onWifiThresholdChanged(Threshold[] thresholds);
    }

    public interface CellularThresholdListener {
        void onCellularThresholdChanged(Threshold[] thresholds);
    }

    private static class ThresholdListener implements IThresholdListener {
        private WeakReference<ThresholdCallback> mThresholdCallbackWeakRef;
        private Executor mExecutor;

        ThresholdListener(Executor executor, ThresholdCallback callback) {
            mExecutor = executor;
            mThresholdCallbackWeakRef = new WeakReference<>(callback);
        }

        @Override
        public void onWifiThresholdChanged(Threshold[] thresholds) {
            WifiThresholdListener listener =
                    (WifiThresholdListener) mThresholdCallbackWeakRef.get();
            if (listener == null) {
                Log.w(TAG, "Listener is null for wifi threshold notification");
                return;
            }
            mExecutor.execute(() -> listener.onWifiThresholdChanged(thresholds));
        }

        @Override
        public void onCellularThresholdChanged(Threshold[] thresholds) {
            CellularThresholdListener listener =
                    (CellularThresholdListener) mThresholdCallbackWeakRef.get();
            if (listener == null) {
                Log.w(TAG, "Listener is null for cellular threshold notification");
                return;
            }
            mExecutor.execute(() -> listener.onCellularThresholdChanged(thresholds));
        }
    }
}
