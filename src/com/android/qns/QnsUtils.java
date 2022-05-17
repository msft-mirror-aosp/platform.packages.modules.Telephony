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

import static android.telephony.ims.ImsMmTelManager.WIFI_MODE_CELLULAR_PREFERRED;
import static android.telephony.ims.ImsMmTelManager.WIFI_MODE_WIFI_PREFERRED;

import android.content.Context;
import android.net.NetworkCapabilities;
import android.os.PersistableBundle;
import android.telephony.AccessNetworkConstants;
import android.telephony.Annotation.NetCapability;
import android.telephony.CarrierConfigManager;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.data.ApnSetting;
import android.text.TextUtils;
import android.util.Xml;

import com.android.ims.ImsManager;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** This class contains QualifiedNetworksService specific utility functions */
public class QnsUtils {

    private static final String CARRIER_ID_PREFIX = "carrier_config_carrierid_";

    public static String getStringApnTypesFromBitmask(int apnTypesMask) {
        StringBuilder sb = new StringBuilder();
        final String[] types = ApnSetting.getApnTypesStringFromBitmask(apnTypesMask).split(",");
        sb.append(TextUtils.join("|", types));
        return sb.toString();
    }

    public static String getStringApnTypes(int apnType) {
        return ApnSetting.getApnTypeString(apnType);
    }

    public static String getStringAccessNetworkTypes(List<Integer> accessNetworkTypes) {
        if (accessNetworkTypes != null && accessNetworkTypes.size() == 0) {
            return "[empty]";
        }
        List<String> types = new ArrayList<>();
        for (Integer net : accessNetworkTypes) {
            types.add(AccessNetworkConstants.AccessNetworkType.toString(net));
        }
        return TextUtils.join("|", types);
    }

    public static int getSubId(Context context, int slotId) {
        int subid = SubscriptionManager.INVALID_SUBSCRIPTION_ID;

        try {
            subid = getSubscriptionInfo(context, slotId).getSubscriptionId();
        } catch (IllegalStateException e) {
            subid = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        }
        return subid;
    }

    public static boolean isDefaultDataSubs(int slotId) {
        int ddsSlotId =
                SubscriptionManager.getSlotIndex(
                        SubscriptionManager.getDefaultDataSubscriptionId());
        if (ddsSlotId != SubscriptionManager.INVALID_SIM_SLOT_INDEX) {
            return ddsSlotId == slotId;
        }
        return false;
    }

    private static SubscriptionInfo getSubscriptionInfo(Context context, int slotId)
            throws IllegalStateException {
        SubscriptionManager sm = context.getSystemService(SubscriptionManager.class);
        SubscriptionInfo info = sm.getActiveSubscriptionInfoForSimSlotIndex(slotId);

        if (info == null) {
            throw new IllegalStateException("Subscription info is null.");
        }

        return info;
    }

    protected static ImsManager getImsManager(Context context, int slotIndex) {
        return com.android.ims.ImsManager.getInstance(context, slotIndex);
    }

    public static boolean isCrossSimCallingEnabled(Context context, int slotIndex) {
        try {
            ImsManager imsManager = getImsManager(context, slotIndex);
            return imsManager.isCrossSimCallingEnabled();
        } catch (Exception e) {
            // Fail to query Cross-SIM calling setting, just return false to avoid an exception.
        }
        return false;
    }

    public static boolean isWfcEnabled(Context context, int slotIndex, boolean roaming) {
        try {
            ImsManager imsManager = getImsManager(context, slotIndex);
            boolean bWfcEnabledByUser;
            boolean bWfcEnabledByPlatform = imsManager.isWfcEnabledByPlatform();
            boolean bWfcProvisionedOnDevice = imsManager.isWfcProvisionedOnDevice();
            if (roaming) {
                bWfcEnabledByUser = imsManager.isWfcRoamingEnabledByUser();
                try {
                    QnsProvisioningListener listener =
                            QnsProvisioningListener.getInstance(context, slotIndex);
                    boolean bWfcRoamingEnabled =
                            listener.getLastProvisioningWfcRoamingEnagledInfo();
                    bWfcEnabledByUser = bWfcEnabledByUser && (bWfcRoamingEnabled);
                } catch (Exception e) {
                    slog("got exception e:" + e);
                }
            } else {
                bWfcEnabledByUser = imsManager.isWfcEnabledByUser();
            }
            slog(
                    "isWfcEnabled slot:"
                            + slotIndex
                            + " byUser:"
                            + bWfcEnabledByUser
                            + " byPlatform:"
                            + bWfcEnabledByPlatform
                            + " ProvisionedOnDevice:"
                            + bWfcProvisionedOnDevice
                            + " roam:"
                            + roaming);
            return bWfcEnabledByUser && bWfcEnabledByPlatform && bWfcProvisionedOnDevice;
        } catch (Exception e) {
            sloge("isWfcEnabled exception:" + e);
            // Fail to query, just return false to avoid an exception.
        }
        return false;
    }

    public static boolean isWfcEnabledByPlatform(Context context, int slotIndex) {
        try {
            ImsManager imsManager = getImsManager(context, slotIndex);
            boolean bWfcEnabledByPlatform = imsManager.isWfcEnabledByPlatform();
            slog("isWfcEnabledByPlatform:" + bWfcEnabledByPlatform + " slot:" + slotIndex);
            return bWfcEnabledByPlatform;
        } catch (Exception e) {
            sloge("isWfcEnabledByPlatform exception:" + e);
            // Fail to query, just return false to avoid an exception.
        }
        return false;
    }

    public static int getWfcMode(Context context, int slotIndex, boolean roaming) {
        try {
            ImsManager imsManager = getImsManager(context, slotIndex);
            int wfcMode = imsManager.getWfcMode(roaming);
            slog("getWfcMode slot:" + slotIndex + " wfcMode:" + wfcMode + " roaming:" + roaming);
            return wfcMode;
        } catch (Exception e) {
            // Fail to query, just return false to avoid an exception.
        }
        return roaming ? WIFI_MODE_WIFI_PREFERRED : WIFI_MODE_CELLULAR_PREFERRED;
    }

    public static int getCellularAccessNetworkType(int dataRegState, int dataTech) {
        if (dataRegState == ServiceState.STATE_IN_SERVICE) {
            return ServiceState.rilRadioTechnologyToAccessNetworkType(dataTech);
        }
        return AccessNetworkConstants.AccessNetworkType.UNKNOWN;
    }

    public static boolean isWifiCallingAvailable(Context context, int slotId) {
        try {
            int subId = QnsUtils.getSubId(context, slotId);
            TelephonyManager telephonyManager =
                    context.getSystemService(TelephonyManager.class).createForSubscriptionId(subId);
            return telephonyManager.isWifiCallingAvailable();
        } catch (Exception e) {
            sloge("isWifiCallingAvailable has exception : " + e);
        }
        return false;
    }

    public static PersistableBundle readQnsDefaultConfigFromAssets(
            Context context, int qnsCarrierID) {

        if (qnsCarrierID == TelephonyManager.UNKNOWN_CARRIER_ID) {
            return null;
        }

        PersistableBundle defaultConfigBundle =
                readConfigFromAssets(context, CARRIER_ID_PREFIX + qnsCarrierID + "_");

        return readConfigFromAssets(context, CARRIER_ID_PREFIX + qnsCarrierID + "_");
    }

    public static synchronized <T> T getConfig(
            PersistableBundle carrierConfigBundle,
            PersistableBundle assetConfigBundle,
            String key) {

        if (carrierConfigBundle == null || carrierConfigBundle.get(key) == null) {
            slog("key not set in pb file: " + key);

            if (assetConfigBundle == null || assetConfigBundle.get(key) == null)
                return (T) getDefaultValueForKey(key);
            else return (T) assetConfigBundle.get(key);
        }
        return (T) carrierConfigBundle.get(key);
    }

    public static synchronized <T> T getDefaultValueForKey(String key) {
        switch (key) {
            case QnsCarrierConfigManager.KEY_QNS_SUPPORT_WFC_DURING_AIRPLANE_MODE_BOOL:
            case QnsCarrierConfigManager.KEY_BLOCK_IPV6_ONLY_WIFI_BOOL:
            case CarrierConfigManager.ImsVoice.KEY_CARRIER_VOLTE_ROAMING_AVAILABLE_BOOL:
                return (T) Boolean.valueOf(true);
            case QnsCarrierConfigManager
                    .KEY_QNS_ALLOW_VIDEO_OVER_IWLAN_WITH_CELLULAR_LIMITED_CASE_BOOL:
            case QnsCarrierConfigManager.KEY_QNS_HO_GUARDING_BY_PREFERENCE_BOOL:
            case QnsCarrierConfigManager.KEY_QNS_SUPPORT_SERVICE_BARRING_CHECK_BOOL:
            case QnsCarrierConfigManager
                    .KEY_ROAM_TRANSPORT_TYPE_SELECTION_WITHOUT_SIGNAL_STRENGTH_BOOL:
            case QnsCarrierConfigManager.KEY_PREFER_CURRENT_TRANSPORT_TYPE_IN_VOICE_CALL_BOOL:
            case QnsCarrierConfigManager.KEY_POLICY_OVERRIDE_CELL_PREF_TO_IMS_PREF_HOME_BOOL:
            case QnsCarrierConfigManager
                    .KEY_QNS_ROVE_OUT_POLICY_WITH_WIFI_BAD_GUARDTIMER_CONDITIONS_BOOL:
            case QnsCarrierConfigManager.KEY_QNS_ALLOW_IMS_OVER_IWLAN_CELLULAR_LIMITED_CASE_BOOL:
            case QnsCarrierConfigManager.KEY_BLOCK_IWLAN_IN_INTERNATIONAL_ROAMING_WITHOUT_WWAN_BOOL:
            case QnsCarrierConfigManager
                    .KEY_IN_CALL_HO_DECISION_WLAN_TO_WWAN_WITHOUT_VOPS_CONDITION_BOOL:
                return (T) Boolean.valueOf(false);
            case QnsCarrierConfigManager.KEY_QNS_CELLULAR_SS_THRESHOLDBACKHAUL_TIMER_MS_INT:
                return (T) Integer.valueOf(QnsConstants.KEY_DEFAULT_VALUE);
            case QnsCarrierConfigManager.KEY_QNS_WIFI_RSSI_THRESHOLDBACKHAUL_TIMER_MS_INT:
                return (T) Integer.valueOf(QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER);
            case QnsCarrierConfigManager.KEY_QNS_IMS_TRANSPORT_TYPE_INT:
                return (T) Integer.valueOf(QnsConstants.TRANSPORT_TYPE_ALLOWED_BOTH);
            case QnsCarrierConfigManager.KEY_QNS_MMS_TRANSPORT_TYPE_INT:
            case QnsCarrierConfigManager.KEY_QNS_XCAP_TRANSPORT_TYPE_INT:
            case QnsCarrierConfigManager.KEY_QNS_CBS_TRANSPORT_TYPE_INT:
            case QnsCarrierConfigManager.KEY_QNS_SOS_TRANSPORT_TYPE_INT:
                return (T) Integer.valueOf(QnsConstants.TRANSPORT_TYPE_ALLOWED_WWAN);
            case QnsCarrierConfigManager.KEY_QNS_XCAP_RAT_PREFERENCE_INT:
            case QnsCarrierConfigManager.KEY_QNS_SOS_RAT_PREFERENCE_INT:
            case QnsCarrierConfigManager.KEY_QNS_MMS_RAT_PREFERENCE_INT:
            case QnsCarrierConfigManager.KEY_QNS_CBS_RAT_PREFERENCE_INT:
                return (T) Integer.valueOf(QnsConstants.RAT_PREFERENCE_DEFAULT);
            case QnsCarrierConfigManager
                    .KEY_QNS_IN_CALL_ROVEIN_ALLOWED_COUNT_AND_FALLBACK_REASON_INT_ARRAY:
                return (T)
                        new int[] {
                            QnsConstants.MAX_COUNT_INVALID, QnsConstants.FALLBACK_REASON_INVALID
                        };
            case QnsCarrierConfigManager
                    .KEY_WAITING_TIME_FOR_PREFERRED_TRANSPORT_WHEN_POWER_ON_INT_ARRAY:
            case QnsCarrierConfigManager.KEY_NON_IMS_WWAN_HYSTERESIS_TIMER_MS_INT_ARRAY:
            case QnsCarrierConfigManager.KEY_NON_IMS_WLAN_HYSTERESIS_TIMER_MS_INT_ARRAY:
                return (T)
                        new int[] {QnsConstants.KEY_DEFAULT_VALUE, QnsConstants.KEY_DEFAULT_VALUE};
            case QnsCarrierConfigManager.KEY_QNS_IMS_NETWORK_ENABLE_HO_HYSTERESIS_TIMER_INT:
                return (T) Integer.valueOf(QnsConstants.COVERAGE_BOTH);
            case QnsCarrierConfigManager
                    .KEY_QNS_HO_RESTRICT_TIME_WITH_LOW_RTP_QUALITY_MILLIS_INT_ARRAY:
                return (T)
                        new int[] {
                            QnsConstants.KEY_DEFAULT_IWLAN_AVOID_TIME_LOW_RTP_QUALITY_MILLIS,
                            QnsConstants.KEY_DEFAULT_VALUE
                        };
            case QnsCarrierConfigManager.KEY_IMS_WWAN_HYSTERESIS_TIMER_MS_INT_ARRAY:
            case QnsCarrierConfigManager.KEY_IMS_WLAN_HYSTERESIS_TIMER_MS_INT_ARRAY:
                return (T)
                        new int[] {
                            QnsConstants.KEY_DEFAULT_HYST_TIMER,
                            QnsConstants.KEY_DEFAULT_HYST_TIMER,
                            QnsConstants.KEY_DEFAULT_HYST_TIMER
                        };
            case QnsCarrierConfigManager.KEY_QNS_RTP_METRICS_INT_ARRAY:
                return (T)
                        new int[] {
                            QnsConstants.KEY_DEFAULT_JITTER,
                            QnsConstants.KEY_DEFAULT_PACKET_LOSS_RATE,
                            QnsConstants.KEY_DEFAULT_PACKET_LOSS_TIME_MILLIS,
                            QnsConstants.KEY_DEFAULT_NO_RTP_INTERVAL_MILLIS
                        };
            case QnsCarrierConfigManager
                    .KEY_CHOOSE_WFC_PREFERRED_TRANSPORT_IN_BOTH_BAD_CONDITION_INT_ARRAY:
                return (T) new int[] {};
            case QnsCarrierConfigManager.KEY_IMS_CELLULAR_ALLOWED_RAT_STRING_ARRAY:
                return (T) new String[] {"LTE", "NR"};
            case QnsCarrierAnspSupportConfig.KEY_IDLE_NGRAN_SSRSRP_INT_ARRAY:
            case QnsCarrierAnspSupportConfig.KEY_VOICE_NGRAN_SSRSRP_INT_ARRAY:
                return (T)
                        new int[] {
                            QnsConstants.KEY_DEFAULT_THRESHOLD_SSRSRP_GOOD,
                            QnsConstants.KEY_DEFAULT_THRESHOLD_SSRSRP_BAD,
                            QnsCarrierConfigManager.QnsConfigArray.INVALID
                        };
            case QnsCarrierAnspSupportConfig.KEY_IDLE_EUTRAN_RSRP_INT_ARRAY:
            case QnsCarrierAnspSupportConfig.KEY_VOICE_EUTRAN_RSRP_INT_ARRAY:
                return (T)
                        new int[] {
                            QnsConstants.KEY_DEFAULT_THRESHOLD_RSRP_GOOD,
                            QnsConstants.KEY_DEFAULT_THRESHOLD_RSRP_BAD,
                            QnsCarrierConfigManager.QnsConfigArray.INVALID
                        };
            case QnsCarrierAnspSupportConfig.KEY_IDLE_UTRAN_RSCP_INT_ARRAY:
            case QnsCarrierAnspSupportConfig.KEY_VOICE_UTRAN_RSCP_INT_ARRAY:
                return (T)
                        new int[] {
                            QnsConstants.KEY_DEFAULT_THRESHOLD_RSCP_GOOD,
                            QnsConstants.KEY_DEFAULT_THRESHOLD_RSCP_BAD,
                            QnsCarrierConfigManager.QnsConfigArray.INVALID
                        };
            case QnsCarrierAnspSupportConfig.KEY_IDLE_GERAN_RSSI_INT_ARRAY:
            case QnsCarrierAnspSupportConfig.KEY_VOICE_GERAN_RSSI_INT_ARRAY:
                return (T)
                        new int[] {
                            QnsConstants.KEY_DEFAULT_THRESHOLD_GERAN_RSSI_GOOD,
                            QnsConstants.KEY_DEFAULT_THRESHOLD_GERAN_RSSI_BAD,
                            QnsCarrierConfigManager.QnsConfigArray.INVALID
                        };
            case QnsCarrierAnspSupportConfig.KEY_IDLE_WIFI_RSSI_INT_ARRAY:
            case QnsCarrierAnspSupportConfig.KEY_VOICE_WIFI_RSSI_INT_ARRAY:
                return (T)
                        new int[] {
                            QnsConstants.KEY_DEFAULT_THRESHOLD_WIFI_RSSI_GOOD,
                            QnsConstants.KEY_DEFAULT_THRESHOLD_WIFI_RSSI_BAD
                        };
            case QnsCarrierAnspSupportConfig.KEY_VIDEO_WIFI_RSSI_INT_ARRAY:
                return (T)
                        new int[] {
                            QnsConstants.KEY_DEFAULT_THRESHOLD_VIDEO_WIFI_RSSI_GOOD,
                            QnsConstants.KEY_DEFAULT_THRESHOLD_VIDEO_WIFI_RSSI_BAD
                        };
            case QnsCarrierAnspSupportConfig.KEY_OVERRIDE_WIFI_PREF_IDLE_WIFI_RSSI_INT_ARRAY:
            case QnsCarrierAnspSupportConfig.KEY_OVERRIDE_WIFI_PREF_VOICE_WIFI_RSSI_INT_ARRAY:
            case QnsCarrierAnspSupportConfig.KEY_OVERRIDE_WIFI_PREF_VIDEO_WIFI_RSSI_INT_ARRAY:
            case CarrierConfigManager.Ims.KEY_IMS_PDN_ENABLED_IN_NO_VOPS_SUPPORT_INT_ARRAY:
                return (T) new int[] {};
            case QnsCarrierConfigManager
                    .KEY_QNS_FALLBACK_ON_INITIAL_CONNECTION_FAILURE_STRING_ARRAY:
            case QnsCarrierConfigManager
                    .KEY_APN_TYPES_WITH_INTERNATIONAL_ROAMING_CONDITION_STRING_ARRAY:
            case QnsCarrierConfigManager
                    .KEY_PLMN_LIST_REGARDED_AS_INTERNATIONAL_ROAMING_STRING_ARRAY:
            case QnsCarrierConfigManager.KEY_PLMN_LIST_REGARDED_AS_DOMESTIC_ROAMING_STRING_ARRAY:
                return (T) new String[] {};
            default:
                break;
        }
        return (T) null;
    }

    public static synchronized int getConfigCarrierId(Context context, int slotId) {
        TelephonyManager tm = context.getSystemService(TelephonyManager.class);
        tm = tm.createForSubscriptionId(QnsUtils.getSubId(context, slotId));
        return tm.getSimCarrierId();
    }

    private static PersistableBundle readConfigFromAssets(Context context, String carrierIDConfig) {
        PersistableBundle bundleFromAssets = new PersistableBundle();

        try {
            String[] configName = context.getAssets().list("");
            if (configName == null) {
                return bundleFromAssets;
            }

            for (String fileName : configName) {
                if (fileName.startsWith(carrierIDConfig)) {
                    slog("matched file: " + fileName);
                    XmlPullParser parser = Xml.newPullParser();
                    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);

                    InputStream input = context.getAssets().open(fileName);
                    parser.setInput(input, "utf-8");
                    bundleFromAssets = readQnsConfigFromXml(parser);
                    input.close();
                    break;
                }
            }
        } catch (Exception e) {
            sloge("Error: " + e);
        }
        return bundleFromAssets;
    }

    private static PersistableBundle readQnsConfigFromXml(XmlPullParser parser)
            throws IOException, XmlPullParserException {
        PersistableBundle bundleFromXml = new PersistableBundle();

        if (parser == null) {
            slog("parser is null");
            return bundleFromXml;
        }

        int event;
        while (((event = parser.next()) != XmlPullParser.END_DOCUMENT)) {
            if (event == XmlPullParser.START_TAG && "carrier_config".equals(parser.getName())) {
                PersistableBundle configFragment = PersistableBundle.restoreFromXml(parser);
                slog("bundle created : " + configFragment);
                bundleFromXml.putAll(configFragment);
            }
        }
        return bundleFromXml;
    }

    protected static void sloge(String log) {
        Rlog.e(QnsUtils.class.getSimpleName(), log);
    }

    protected static void slog(String log) {
        Rlog.d(QnsUtils.class.getSimpleName(), log);
    }

    protected static @NetCapability int apnTypeToNetworkCapability(int apnType) {
        switch (apnType) {
            case ApnSetting.TYPE_IMS:
                return NetworkCapabilities.NET_CAPABILITY_IMS;
            case ApnSetting.TYPE_MMS:
                return NetworkCapabilities.NET_CAPABILITY_MMS;
            case ApnSetting.TYPE_XCAP:
                return NetworkCapabilities.NET_CAPABILITY_XCAP;
            case ApnSetting.TYPE_EMERGENCY:
                return NetworkCapabilities.NET_CAPABILITY_EIMS;
            case ApnSetting.TYPE_CBS:
                return NetworkCapabilities.NET_CAPABILITY_CBS;
            default:
                throw new IllegalArgumentException("Unsupported apn type: " + apnType);
        }
    }
}
