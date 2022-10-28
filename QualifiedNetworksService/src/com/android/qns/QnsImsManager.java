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
package com.android.qns;

import static android.telephony.ims.feature.MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VOICE;
import static android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN;
import static android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_LTE;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.SystemProperties;
import android.telephony.AccessNetworkConstants;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.ims.ImsException;
import android.telephony.ims.ImsManager;
import android.telephony.ims.ImsMmTelManager;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.ImsRegistrationAttributes;
import android.telephony.ims.ImsStateCallback;
import android.telephony.ims.ProvisioningManager;
import android.telephony.ims.RegistrationManager;
import android.telephony.ims.compat.feature.ImsFeature;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * QnsImsManager is helper class to get wi-fi calling related items from ImsManager
 *
 * @hide
 */
class QnsImsManager {

    static final String PROP_DBG_WFC_AVAIL_OVERRIDE = "persist.dbg.wfc_avail_ovr";

    private static final int SYS_PROP_NOT_SET = -1;

    private static final SparseArray<QnsImsManager> sQnsImsManagerSparseArray = new SparseArray<>();

    /**
     * Gets a QnsImsManager instance
     *
     * @param context application context for creating the manager object
     * @param slotId subscription ID
     * @return the QnsImsManager instance corresponding to the subId
     */
    static QnsImsManager getInstance(Context context, int slotId) {
        synchronized (sQnsImsManagerSparseArray) {
            QnsImsManager qnsImsManager = sQnsImsManagerSparseArray.get(slotId);
            if (qnsImsManager == null) {
                qnsImsManager = new QnsImsManager(context, slotId);
                sQnsImsManagerSparseArray.put(slotId, qnsImsManager);
            }
            return qnsImsManager;
        }
    }

    private final String mLogTag;
    private final Context mContext;
    private final int mSlotId;
    private final Executor mExecutor;
    private boolean mQnsImsManagerInitialized;
    private CarrierConfigManager mConfigManager;
    private ImsManager mImsManager;
    private ImsMmTelManager mImsMmTelManager;
    private QnsImsStateCallback mQnsImsStateCallback;
    private final QnsRegistrantList mQnsImsStateListener;
    private final QnsImsManagerHandler mQnsImsManagerHandler;
    private QnsImsRegistrationCallback mQnsImsRegistrationCallback;
    private final QnsRegistrantList mImsRegistrationStatusListeners;
    private ImsRegistrationState mLastImsRegistrationState;

    /** QnsImsManager default constructor */
    QnsImsManager(Context context, int slotId) {
        mLogTag = QnsImsManager.class.getSimpleName() + "_" + slotId;
        mContext = context;
        mSlotId = slotId;
        mExecutor = new QnsImsManagerExecutor();

        HandlerThread handlerThread = new HandlerThread(mLogTag);
        handlerThread.start();
        mQnsImsManagerHandler = new QnsImsManagerHandler(handlerThread.getLooper());

        mQnsImsStateListener = new QnsRegistrantList();
        mImsRegistrationStatusListeners = new QnsRegistrantList();

        initQnsImsManager();
    }

    @VisibleForTesting
    protected synchronized void initQnsImsManager() {
        int subId = getSubId();
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            return;
        }
        try {
            mConfigManager = mContext.getSystemService(CarrierConfigManager.class);
            ImsManager imsManager = mContext.getSystemService(ImsManager.class);
            if (imsManager == null) {
                return;
            }
            ImsMmTelManager mmTelManager = imsManager.getImsMmTelManager(subId);
            QnsImsStateCallback stateCallback = new QnsImsStateCallback();
            QnsImsRegistrationCallback registrationCallback = new QnsImsRegistrationCallback();
            mmTelManager.registerImsStateCallback(mExecutor, stateCallback);
            mmTelManager.registerImsRegistrationCallback(mExecutor, registrationCallback);
            log("initQnsImsManager: initialized and registered capability callback");
            mImsManager = imsManager;
            mImsMmTelManager = mmTelManager;
            mQnsImsStateCallback = stateCallback;
            mQnsImsRegistrationCallback = registrationCallback;
            mQnsImsManagerInitialized = true;
        } catch (IllegalArgumentException | ImsException | NullPointerException e) {
            loge("initQnsImsManager: couldn't initialize or register ims callbacks, " + e);
            clearQnsImsManager();
        }
    }

    @VisibleForTesting
    protected synchronized void clearQnsImsManager() {
        log("clearQnsImsManager");
        if (mImsMmTelManager != null) {
            mImsMmTelManager.unregisterImsStateCallback(mQnsImsStateCallback);
            mImsMmTelManager.unregisterImsRegistrationCallback(mQnsImsRegistrationCallback);
        }
        mImsManager = null;
        mImsMmTelManager = null;
        mQnsImsStateCallback = null;
        mQnsImsRegistrationCallback = null;
        mQnsImsManagerInitialized = false;
    }

    @VisibleForTesting
    protected synchronized void dispose() {
        clearQnsImsManager();
        mImsRegistrationStatusListeners.removeAll();
        mQnsImsManagerHandler.dispose();
        sQnsImsManagerSparseArray.remove(mSlotId);
    }

    private class QnsImsManagerHandler extends Handler {
        QnsImsManagerHandler(Looper looper) {
            super(looper);
            List<Integer> events = new ArrayList<>();
            events.add(QnsEventDispatcher.QNS_EVENT_SIM_LOADED);
            events.add(QnsEventDispatcher.QNS_EVENT_SIM_ABSENT);
            QnsEventDispatcher.getInstance(mContext, mSlotId).registerEvent(events, this);
        }

        void dispose() {
            QnsEventDispatcher.getInstance(mContext, mSlotId).unregisterEvent(this);
        }

        @Override
        public void handleMessage(Message message) {
            log("handleMessage msg=" + message.what);
            switch (message.what) {
                case QnsEventDispatcher.QNS_EVENT_SIM_LOADED:
                case QnsEventDispatcher.QNS_EVENT_SIM_ABSENT:
                    clearQnsImsManager();
                    break;
                default:
                    break;
            }
        }
    }

    private synchronized ImsMmTelManager getImsMmTelManagerOrThrowExceptionIfNotReady()
            throws ImsException {
        if (!mQnsImsManagerInitialized) {
            initQnsImsManager();
        }
        if (mImsManager == null || mImsMmTelManager == null || mQnsImsStateCallback == null) {
            throw new ImsException(
                    "IMS service is down.", ImsReasonInfo.CODE_LOCAL_IMS_SERVICE_DOWN);
        }
        return mImsMmTelManager;
    }

    /**
     * Get the boolean config from carrier config manager.
     *
     * @param key config key defined in CarrierConfigManager
     * @return boolean value of corresponding key.
     */
    private boolean getBooleanCarrierConfig(String key) {
        PersistableBundle b = null;
        if (mConfigManager != null) {
            // If an invalid subId is used, this bundle will contain default values.
            b = mConfigManager.getConfigForSubId(getSubId());
        }
        if (b != null) {
            return b.getBoolean(key);
        } else {
            // Return static default defined in CarrierConfigManager.
            return CarrierConfigManager.getDefaultConfig().getBoolean(key);
        }
    }

    /**
     * Get the int config from carrier config manager.
     *
     * @param key config key defined in CarrierConfigManager
     * @return integer value of corresponding key.
     */
    private int getIntCarrierConfig(String key) {
        PersistableBundle b = null;
        if (mConfigManager != null) {
            // If an invalid subId is used, this bundle will contain default values.
            b = mConfigManager.getConfigForSubId(getSubId());
        }
        if (b != null) {
            return b.getInt(key);
        } else {
            // Return static default defined in CarrierConfigManager.
            return CarrierConfigManager.getDefaultConfig().getInt(key);
        }
    }

    private boolean isCrossSimCallingEnabledByUser() {
        boolean crossSimCallingEnabled;
        try {
            ImsMmTelManager mmTelManager = getImsMmTelManagerOrThrowExceptionIfNotReady();
            crossSimCallingEnabled = mmTelManager.isCrossSimCallingEnabled();
        } catch (Exception e) {
            crossSimCallingEnabled = false;
        }
        log("isCrossSimCallingEnabledByUser:" + crossSimCallingEnabled);
        return crossSimCallingEnabled;
    }

    private boolean isGbaValid() {
        if (getBooleanCarrierConfig(CarrierConfigManager.KEY_CARRIER_IMS_GBA_REQUIRED_BOOL)) {
            TelephonyManager tm = mContext.getSystemService(TelephonyManager.class);
            if (tm == null) {
                loge("isGbaValid: TelephonyManager is null, returning false.");
                return false;
            }
            tm = tm.createForSubscriptionId(getSubId());
            String efIst = tm.getIsimIst();
            if (efIst == null) {
                loge("isGbaValid - ISF is NULL");
                return true;
            }
            boolean result = efIst.length() > 1 && (0x02 & (byte) efIst.charAt(1)) != 0;
            log("isGbaValid - GBA capable=" + result + ", ISF=" + efIst);
            return result;
        }
        return true;
    }

    private boolean isCrossSimEnabledByPlatform() {
        if (isWfcEnabledByPlatform()) {
            return getBooleanCarrierConfig(
                    CarrierConfigManager.KEY_CARRIER_CROSS_SIM_IMS_AVAILABLE_BOOL);
        }
        return false;
    }

    private boolean isVolteProvisionedOnDevice() {
        if (isMmTelProvisioningRequired(REGISTRATION_TECH_LTE)) {
            return isVolteProvisioned();
        }

        return true;
    }

    private boolean isVolteProvisioned() {
        return getImsProvisionedBoolNoException(REGISTRATION_TECH_LTE);
    }

    private boolean isWfcProvisioned() {
        return getImsProvisionedBoolNoException(REGISTRATION_TECH_IWLAN);
    }

    private boolean isMmTelProvisioningRequired(int tech) {
        if (!SubscriptionManager.isValidSubscriptionId(getSubId())) {
            return false;
        }

        boolean required = false;
        try {
            ProvisioningManager p = ProvisioningManager.createForSubscriptionId(getSubId());
            required = p.isProvisioningRequiredForCapability(CAPABILITY_TYPE_VOICE, tech);
        } catch (RuntimeException e) {
            loge("isMmTelProvisioningRequired, tech:" + tech + ". e:" + e);
        }

        log("isMmTelProvisioningRequired " + required + " for tech:" + tech);
        return required;
    }

    private boolean getImsProvisionedBoolNoException(int tech) {
        if (!SubscriptionManager.isValidSubscriptionId(getSubId())) {
            return false;
        }

        boolean status = false;
        try {
            ProvisioningManager p = ProvisioningManager.createForSubscriptionId(getSubId());
            status = p.getProvisioningStatusForCapability(CAPABILITY_TYPE_VOICE, tech);
        } catch (RuntimeException e) {
            loge("getImsProvisionedBoolNoException, tech:" + tech + ". e:" + e);
        }

        log("getImsProvisionedBoolNoException " + status + " for tech:" + tech);
        return status;
    }

    private int getSubId() {
        return QnsUtils.getSubId(mContext, mSlotId);
    }

    private static class QnsImsManagerExecutor implements Executor {
        private Executor mExecutor;

        @Override
        public void execute(Runnable runnable) {
            startExecutorIfNeeded();
            mExecutor.execute(runnable);
        }

        private synchronized void startExecutorIfNeeded() {
            if (mExecutor != null) return;
            mExecutor = Executors.newSingleThreadExecutor();
        }
    }

    private class QnsImsStateCallback extends ImsStateCallback {
        boolean mImsAvailable;

        @Override
        public void onUnavailable(int reason) {
            changeImsState(false);
        }

        @Override
        public void onAvailable() {
            changeImsState(true);
        }

        @Override
        public void onError() {
            changeImsState(false);
        }

        private void changeImsState(boolean imsAvailable) {
            if (mImsAvailable != imsAvailable) {
                mImsAvailable = imsAvailable;
                onImsStateChanged(imsAvailable);
            }
        }
    }

    /** class for the IMS State. */
    static class ImsState {
        private final boolean mImsAvailable;

        ImsState(boolean imsAvailable) {
            mImsAvailable = imsAvailable;
        }

        boolean isImsAvailable() {
            return mImsAvailable;
        }
    }

    private void onImsStateChanged(boolean imsAvailable) {
        log("onImsStateChanged " + imsAvailable);
        ImsState imsState = new ImsState(imsAvailable);
        notifyImsStateChanged(imsState);
    }

    /**
     * Registers to monitor Ims State
     *
     * @param h Handler to get an event
     * @param what message id.
     */
    void registerImsStateChanged(Handler h, int what) {
        QnsRegistrant r = new QnsRegistrant(h, what, null);
        mQnsImsStateListener.add(r);
    }

    /**
     * Unregisters ims state for given handler.
     *
     * @param h Handler
     */
    void unregisterImsStateChanged(Handler h) {
        mQnsImsStateListener.remove(h);
    }

    protected void notifyImsStateChanged(ImsState imsState) {
        mQnsImsStateListener.notifyResult(imsState);
    }

    private static class StateConsumer extends Semaphore implements Consumer<Integer> {
        private static final long TIMEOUT_MILLIS = 2000;

        StateConsumer() {
            super(0);
            mValue = new AtomicInteger();
        }

        private final AtomicInteger mValue;

        int getOrTimeOut() throws InterruptedException {
            if (tryAcquire(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                return mValue.get();
            }
            return ImsFeature.STATE_NOT_AVAILABLE;
        }

        public void accept(Integer value) {
            if (value != null) {
                mValue.set(value);
            }
            release();
        }
    }

    private class QnsImsRegistrationCallback extends RegistrationManager.RegistrationCallback {

        @Override
        public void onRegistered(ImsRegistrationAttributes attribute) {
            int transportType = attribute.getTransportType();
            log("on IMS registered on :" + QnsConstants.transportTypeToString(transportType));
            notifyImsRegistrationChangedEvent(
                    QnsConstants.IMS_REGISTRATION_CHANGED_REGISTERED, transportType, null);
        }

        @Override
        public void onTechnologyChangeFailed(int transportType, ImsReasonInfo reason) {
            log(
                    "onTechnologyChangeFailed["
                            + QnsConstants.transportTypeToString(transportType)
                            + "] "
                            + reason.toString());
            notifyImsRegistrationChangedEvent(
                    QnsConstants.IMS_REGISTRATION_CHANGED_ACCESS_NETWORK_CHANGE_FAILED,
                    transportType,
                    reason);
        }

        @Override
        public void onUnregistered(ImsReasonInfo reason) {
            log("onUnregistered " + reason.toString());
            notifyImsRegistrationChangedEvent(
                    QnsConstants.IMS_REGISTRATION_CHANGED_UNREGISTERED,
                    AccessNetworkConstants.TRANSPORT_TYPE_INVALID,
                    reason);
        }
    }

    /** State class for the IMS Registration. */
    static class ImsRegistrationState {
        @QnsConstants.QnsImsRegiEvent private final int mEvent;
        private final int mTransportType;
        private final ImsReasonInfo mReasonInfo;

        ImsRegistrationState(int event, int transportType, ImsReasonInfo reason) {
            mEvent = event;
            mTransportType = transportType;
            mReasonInfo = reason;
        }

        int getEvent() {
            return mEvent;
        }

        int getTransportType() {
            return mTransportType;
        }

        ImsReasonInfo getReasonInfo() {
            return mReasonInfo;
        }

        @Override
        public String toString() {
            String reason = getReasonInfo() == null ? "null" : mReasonInfo.toString();
            String event = Integer.toString(mEvent);
            switch (mEvent) {
                case QnsConstants.IMS_REGISTRATION_CHANGED_REGISTERED:
                    event = "IMS_REGISTERED";
                    break;
                case QnsConstants.IMS_REGISTRATION_CHANGED_UNREGISTERED:
                    event = "IMS_UNREGISTERED";
                    break;
                case QnsConstants.IMS_REGISTRATION_CHANGED_ACCESS_NETWORK_CHANGE_FAILED:
                    event = "IMS_ACCESS_NETWORK_CHANGE_FAILED";
                    break;
            }
            return "ImsRegistrationState["
                    + QnsConstants.transportTypeToString(mTransportType)
                    + "] "
                    + "Event:"
                    + event
                    + " reason:"
                    + reason;
        }
    }

    /**
     * Get the status of whether the IMS is registered or not for given transport type
     *
     * @param transportType Transport Type
     * @return true when ims is registered.
     */
    boolean isImsRegistered(int transportType) {
        return mLastImsRegistrationState != null
                && mLastImsRegistrationState.getTransportType() == transportType
                && mLastImsRegistrationState.getEvent()
                        == QnsConstants.IMS_REGISTRATION_CHANGED_REGISTERED;
    }

    /**
     * Registers to monitor Ims registration status
     *
     * @param h Handler to get an event
     * @param what message id.
     */
    void registerImsRegistrationStatusChanged(Handler h, int what) {
        QnsRegistrant r = new QnsRegistrant(h, what, null);
        mImsRegistrationStatusListeners.add(r);
    }

    /**
     * Unregisters ims registration status for given handler.
     *
     * @param h Handler
     */
    void unregisterImsRegistrationStatusChanged(Handler h) {
        mImsRegistrationStatusListeners.remove(h);
    }

    @VisibleForTesting
    protected void notifyImsRegistrationChangedEvent(
            int event, int transportType, ImsReasonInfo reason) {
        mLastImsRegistrationState = new ImsRegistrationState(event, transportType, reason);
        mImsRegistrationStatusListeners.notifyResult(mLastImsRegistrationState);
    }

    private static boolean isImsSupportedOnDevice(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY_IMS);
    }

    /**
     * Get the status of the MmTel Feature corresponding to this subscription.
     *
     * <p>This function is a blocking function and there may be a timeout of up to 2 seconds.
     *
     * @return MmTel Feature Status. Returns one of the following: {@link
     *     ImsFeature#STATE_NOT_AVAILABLE}, {@link ImsFeature#STATE_INITIALIZING}, {@link
     *     ImsFeature#STATE_READY}.
     * @throws ImsException if the IMS service associated with this subscription is not available or
     *     the IMS service is not available.
     * @throws InterruptedException if the thread to get value is timed out. (max 2000ms)
     */
    int getImsServiceState() throws ImsException, InterruptedException {
        if (!isImsSupportedOnDevice(mContext)) {
            throw new ImsException(
                    "IMS not supported on device.",
                    ImsReasonInfo.CODE_LOCAL_IMS_NOT_SUPPORTED_ON_DEVICE);
        }
        ImsMmTelManager mmTelManager = getImsMmTelManagerOrThrowExceptionIfNotReady();
        final StateConsumer stateConsumer = new StateConsumer();
        mmTelManager.getFeatureState(mExecutor, stateConsumer);
        int state = stateConsumer.getOrTimeOut(); // ImsFeature.STATE_READY
        log("getImsServiceState state:" + state);
        return state;
    }

    /**
     * Returns whether wi-fi calling feature is enabled by platform.
     *
     * <p>This function is a blocking function and there may be a timeout of up to 2 seconds.
     *
     * @return true, if wi-fi calling feature is enabled by platform.
     */
    boolean isWfcEnabledByPlatform() {
        // We first read the per slot value. If it doesn't exist, we read the general value.
        // If still doesn't exist, we use the hardcoded default value.
        if (SystemProperties.getInt(PROP_DBG_WFC_AVAIL_OVERRIDE + mSlotId, SYS_PROP_NOT_SET) == 1
                || SystemProperties.getInt(PROP_DBG_WFC_AVAIL_OVERRIDE, SYS_PROP_NOT_SET) == 1) {
            return true;
        }

        return mContext.getResources()
                        .getBoolean(com.android.internal.R.bool.config_device_wfc_ims_available)
                && getBooleanCarrierConfig(CarrierConfigManager.KEY_CARRIER_WFC_IMS_AVAILABLE_BOOL)
                && isGbaValid();
    }

    /**
     * Returns whether wi-fi calling setting is enabled by user.
     *
     * @return true, if wi-fi calling setting is enabled by user.
     */
    boolean isWfcEnabledByUser() {
        boolean wfcEnabled;
        try {
            ImsMmTelManager mmTelManager = getImsMmTelManagerOrThrowExceptionIfNotReady();
            wfcEnabled = mmTelManager.isVoWiFiSettingEnabled();
        } catch (Exception e) {
            wfcEnabled =
                    getBooleanCarrierConfig(
                            CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_ENABLED_BOOL);
        }
        log("isWfcEnabledByUser:" + wfcEnabled);
        return wfcEnabled;
    }

    /**
     * Returns whether wi-fi calling roaming setting is enabled by user.
     *
     * @return true, if wi-fi calling roaming setting is enabled by user.
     */
    boolean isWfcRoamingEnabledByUser() {
        boolean wfcRoamingEnabled;
        try {
            ImsMmTelManager mmTelManager = getImsMmTelManagerOrThrowExceptionIfNotReady();
            wfcRoamingEnabled = mmTelManager.isVoWiFiRoamingSettingEnabled();
        } catch (Exception e) {
            wfcRoamingEnabled =
                    getBooleanCarrierConfig(
                            CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_ROAMING_ENABLED_BOOL);
        }
        log("isWfcRoamingEnabledByUser:" + wfcRoamingEnabled);
        return wfcRoamingEnabled;
    }

    /**
     * Returns whether cross sim wi-fi calling is enabled.
     *
     * @return true, if cross sim wi-fi calling is enabled.
     */
    boolean isCrossSimCallingEnabled() {
        boolean userEnabled = isCrossSimCallingEnabledByUser();
        boolean platformEnabled = isCrossSimEnabledByPlatform();
        boolean isProvisioned = isWfcProvisionedOnDevice();

        log(
                "isCrossSimCallingEnabled: platformEnabled = "
                        + platformEnabled
                        + ", provisioned = "
                        + isProvisioned
                        + ", userEnabled = "
                        + userEnabled);
        return userEnabled && platformEnabled && isProvisioned;
    }

    /**
     * Returns Voice over Wi-Fi mode preference
     *
     * @param roaming false:mode pref for home, true:mode pref for roaming
     * @return voice over Wi-Fi mode preference, which can be one of the following: {@link
     *     ImsMmTelManager#WIFI_MODE_WIFI_ONLY}, {@link
     *     ImsMmTelManager#WIFI_MODE_CELLULAR_PREFERRED}, {@link
     *     ImsMmTelManager#WIFI_MODE_WIFI_PREFERRED}
     */
    int getWfcMode(boolean roaming) {
        if (!roaming) {
            int wfcMode;
            try {
                ImsMmTelManager mmTelManager = getImsMmTelManagerOrThrowExceptionIfNotReady();
                wfcMode = mmTelManager.getVoWiFiModeSetting();
            } catch (Exception e) {
                wfcMode =
                        getIntCarrierConfig(
                                CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_MODE_INT);
            }
            log("getWfcMode:" + wfcMode);
            return wfcMode;
        } else {
            int wfcRoamingMode;
            try {
                ImsMmTelManager mmTelManager = getImsMmTelManagerOrThrowExceptionIfNotReady();
                wfcRoamingMode = mmTelManager.getVoWiFiRoamingModeSetting();
            } catch (Exception e) {
                wfcRoamingMode =
                        getIntCarrierConfig(
                                CarrierConfigManager.KEY_CARRIER_DEFAULT_WFC_IMS_ROAMING_MODE_INT);
            }
            log("getWfcMode(roaming):" + wfcRoamingMode);
            return wfcRoamingMode;
        }
    }

    /**
     * Indicates whether VoWifi is provisioned on slot.
     *
     * <p>When CarrierConfig KEY_CARRIER_VOLTE_OVERRIDE_WFC_PROVISIONING_BOOL is true, and VoLTE is
     * not provisioned on device, this method returns false.
     */
    boolean isWfcProvisionedOnDevice() {
        if (getBooleanCarrierConfig(
                CarrierConfigManager.KEY_CARRIER_VOLTE_OVERRIDE_WFC_PROVISIONING_BOOL)) {
            if (!isVolteProvisionedOnDevice()) {
                return false;
            }
        }

        if (isMmTelProvisioningRequired(REGISTRATION_TECH_IWLAN)) {
            return isWfcProvisioned();
        }

        return true;
    }

    protected void log(String s) {
        Log.d(mLogTag, s);
    }

    protected void loge(String s) {
        Log.e(mLogTag, s);
    }
}
