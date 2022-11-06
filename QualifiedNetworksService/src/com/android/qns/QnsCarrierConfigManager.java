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

import static android.telephony.AccessNetworkConstants.TRANSPORT_TYPE_WLAN;
import static android.telephony.AccessNetworkConstants.TRANSPORT_TYPE_WWAN;
import static android.telephony.SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_ECNO;
import static android.telephony.SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSCP;
import static android.telephony.SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRP;
import static android.telephony.SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRQ;
import static android.telephony.SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSI;
import static android.telephony.SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSNR;
import static android.telephony.SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_SSRSRP;
import static android.telephony.SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_SSRSRQ;
import static android.telephony.SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_SSSINR;
import static android.telephony.SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_UNKNOWN;
import static android.telephony.TelephonyManager.UNKNOWN_CARRIER_ID;

import static com.android.qns.QnsConstants.FALLBACK_REASON_INVALID;
import static com.android.qns.QnsConstants.MAX_COUNT_INVALID;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.content.Context;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.PersistableBundle;
import android.telephony.AccessNetworkConstants;
import android.telephony.Annotation.NetCapability;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.ims.ProvisioningManager;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * This class supports loading Ansp(Access Network Selection Policy , Thresholds , Handover Polices
 * & Other Supporting Carrier configurations , to support ANE to decide on the HO decision
 * management & listing related Access Network to pass to Telephony
 */
class QnsCarrierConfigManager {
    /**
     * Boolean indicating the WFC services in QNS Side is enabled, when airplane mode is On
     *
     * <p>{@code true}: QNS is enabled for WFC services in airplane mode on. {@code false}: QNS is
     * disabled for WFC services in airplane mode on. The default value for this key is {@code true}
     */
    static final String KEY_QNS_SUPPORT_WFC_DURING_AIRPLANE_MODE_BOOL =
            "qns.support_wfc_during_airplane_mode_bool";

    /**
     * Boolean indicating if in-call handover decision from WLAN to WWAN should consider VoPS
     * status.
     *
     * <p>{@code true}: In-call handover decision from WLAN to WWAN won't consider VoPS status, for
     * example, UE can perform handover from WLAN to LTE even if LTE network does not support VoPS.
     * {@code false}: In-call handover decision from WLAN to WWAN will consider VoPS status, for
     * example, UE should not perform handover from WLAN to LTE if LTE network does not support
     * VoPS.
     * The default value for this key is {@code false}
     */
    static final String KEY_IN_CALL_HO_DECISION_WLAN_TO_WWAN_WITHOUT_VOPS_CONDITION_BOOL =
            "qns.in_call_ho_decision_wlan_to_wwan_without_vops_condition_bool";

    /**
     * Boolean indicating Iwlan TransportType priority is enabled , when VOPS(Voice Over PS Session)
     * flag from NW is false .
     *
     * <p>{@code true}: Iwlan TransportType selection priority is enabled, when VOPS(Voice Over PS
     * Session) is false. {@code false}: Iwlan TransportType selection priority is disabled, when
     * VOPS (Voice Over PS Session) is false. The default value for this key is {@code true}
     */
    static final String KEY_QNS_VOPS_NOTAVAILABLE_PRIORITY_IWLAN_BOOL =
            "qns.support_vops_notavailable_priority_iwlan_bool";

    /**
     * Boolean indicating when disabled , supporting of Guard Timer applied to both TransportType
     * WWAN (Cellular) & WLAN ( Wifi)
     *
     * <p>{@code false}: Whe Disabled , Guard timer (To avoid Ping Pong) is executed for both the
     * direction ( ie Cellular to Wifi & Wifi to Cellular) {@code true}: when enabled , Guard timer
     * (To avoid Ping Pong) is executed only based on the preference set. The default value for this
     * key is {@code false}
     */
    static final String KEY_QNS_HO_GUARDING_BY_PREFERENCE_BOOL =
            "qns.ho_guarding_by_preference_bool";
    /**
     * Boolean indicating the Service Barring check is disabled, when making HO decision from
     * transport type WWAN (Cellular) to Transport type WLAN Wifi
     *
     * <p>{@code false}: Service Barring check is disabled , when making HO decision from transport
     * type WWAN (Cellular) to Transport type WLAN Wifi {@code true}: Service Barring check is
     * enabled , when making HO decision from transport type WWAN (Cellular) to Transport type WLAN
     * Wifi The default value for this key is {@code false}
     */
    static final String KEY_QNS_SUPPORT_SERVICE_BARRING_CHECK_BOOL =
            "qns.support_service_barring_check_bool";

    /**
     * Boolean indicating the transport type selection without Signal Strength is disabled, during
     * roaming condition
     *
     * <p>{@code false}: when disabled , transport type selection is based on RAT existence & signal
     * quality during roaming.. {@code true}: when enabled , transport type selection is based on
     * RAT availability during roaming. (not depends on Signal Strength) The default value for this
     * key is {@code false}
     */
    static final String KEY_ROAM_TRANSPORT_TYPE_SELECTION_WITHOUT_SIGNAL_STRENGTH_BOOL =
            "qns.roam_transport_type_selection_without_signal_strength_bool";

    /**
     * Boolean indicating the preference to select/continue call in current Transport Type is
     * disabled.
     *
     * <p>{@code false}: When disabled , preference to select/continue call in current Transport
     * Type is not allowed {@code true}: When enabled , preference to select/continue call in
     * current Transport Type is allowed The default value for this key is {@code false}
     */
    static final String KEY_PREFER_CURRENT_TRANSPORT_TYPE_IN_VOICE_CALL_BOOL =
            "qns.prefer_current_transport_type_in_voice_call_bool";

    /**
     * Boolean to override IMS Mode Preference from cellular preference.
     *
     * <p>{@code false}: When disabled , no ims override preference. {@code true}: When enabled ,
     * load ims mode preference instead of cellular mode preference at home network. The default
     * value for this key is {@code false}
     */
    static final String KEY_POLICY_OVERRIDE_CELL_PREF_TO_IMS_PREF_HOME_BOOL =
            "qns.override_cell_pref_to_ims_pref_home";

    /**
     * Boolean indicating allowing video call over wifi is disabled , when cellular limited case
     * meets.(ie no LTE home network is available, or if an LTE home network is available but VoPS
     * is disabled or has 100% SSAC voice barring)
     *
     * <p>{@code false}: When disabled , preference to allow video call on meeting cellular limited
     * case conditions over Wifi is not allowed. {@code true}: When enabled , preference to move
     * video call on meeting cellular limited case conditions over Wifi is allowed. The default
     * value for this key is {@code false}
     */
    static final String KEY_QNS_ALLOW_VIDEO_OVER_IWLAN_WITH_CELLULAR_LIMITED_CASE_BOOL =
            "qns.allow_video_over_iwlan_with_cellular_limited_case_bool";

    /**
     * Boolean indicating cellular2WiFi-hysteresis Scenario rove out policies of WIth WIfi Bad
     * criteria check with Guard TImer conditions is disabled.
     *
     * <p>{@code false}: When disabled , cellular2WiFi-hysteresis Scenario rove out policies during
     * guard timer conditions(Running/Expired state) is not available {@code true}: When enabled ,
     * cellular2WiFi-hysteresis Scenario rove out policies during guard timer
     * conditions(Running/Expired state) is available The default value for this key is {@code
     * false}
     */
    static final String KEY_QNS_ROVE_OUT_POLICY_WITH_WIFI_BAD_GUARDTIMER_CONDITIONS_BOOL =
            "qns.rove_out_policy_with_wifi_bad_guardtimer_conditions_bool";

    /**
     * Boolean indicating enabling of Wi-Fi call when in a call state idle with a cellular network
     * that does not support ims pdn.
     *
     * <p>{@code false}: When disabled , There is no action to enable Wi-Fi Calling. {@code true}:
     * When enabled , Enable Wi-Fi calling, if the call state is idle and the cellular network the
     * UE is staying on does not allow ims pdn. The default value for this key is {@code false}
     */
    static final String KEY_QNS_ALLOW_IMS_OVER_IWLAN_CELLULAR_LIMITED_CASE_BOOL =
            "qns.allow_ims_over_iwlan_cellular_limited_case_bool";

    /**
     * Boolean indicating if to block IWLAN when UE is in no WWAN coverage and the last stored
     * country code is outside the home country.
     * By default this value is {@code false}.
     *
     */
    static final String KEY_BLOCK_IWLAN_IN_INTERNATIONAL_ROAMING_WITHOUT_WWAN_BOOL =
            "qns.block_iwlan_in_international_roaming_without_wwan_bool";

    /**
     * Boolean indicating if to block IWLAN when UE is connected to IPv6 only WiFi AP. The setting
     * may only apply on Android T. For Android U onwards, we may support a carrier config at IWLAN
     * if we still encounter any issues for IPv6 WFC. By default this value is {@code true}.
     */
    static final String KEY_BLOCK_IPV6_ONLY_WIFI_BOOL = "qns.block_ipv6_only_wifi_bool";

    /**
     * Specifies the Rat Preference for the XCAP network capability. Boolean indicating adding the
     * IMS Registration condition to the Wi-Fi Rove in condition.
     *
     * <ul>
     *   <li>{@code QnsConstants#RAT_PREFERENCE_DEFAULT}: Default, Follow the system preference.
     *   <li>{@code QnsConstants#RAT_PREFERENCE_WIFI_ONLY}: If set , choose Wi-Fi always
     *   <li>{@code QnsConstants#RAT_PREFERENCE_WIFI_WHEN_WFC_AVAILABLE}: If set , choose Wi-Fi when
     *       the Wi-Fi Calling is available.(when IMS is registered through the Wi-Fi)
     *   <li>{@code QnsConstants#RAT_PREFERENCE_WIFI_WHEN_NO_CELLULAR}: If set , choose Wi-Fi when
     *       no cellular
     *   <li>{@code QnsConstants#RAT_PREFERENCE_WIFI_WHEN_HOME_IS_NOT_AVAILABLE}: If set , choose
     *       Wi-Fi when cellular is available at home network.
     * </ul>
     */
    static final String KEY_QNS_XCAP_RAT_PREFERENCE_INT = "qns.xcap_rat_preference_int";

    /**
     * Specifies the Rat Preference for the SOS network capability. Boolean indicating adding the
     * IMS Registration condition to the Wi-Fi Rove in condition.
     *
     * <ul>
     *   <li>{@code QnsConstants#RAT_PREFERENCE_DEFAULT}: Default, Follow the system preference.
     *   <li>{@code QnsConstants#RAT_PREFERENCE_WIFI_ONLY}: If set , choose Wi-Fi always
     *   <li>{@code QnsConstants#RAT_PREFERENCE_WIFI_WHEN_WFC_AVAILABLE}: If set , choose Wi-Fi when
     *       the Wi-Fi Calling is available.(when IMS is registered through the Wi-Fi)
     *   <li>{@code QnsConstants#RAT_PREFERENCE_WIFI_WHEN_NO_CELLULAR}: If set , choose Wi-Fi when
     *       no cellular
     *   <li>{@code QnsConstants#RAT_PREFERENCE_WIFI_WHEN_HOME_IS_NOT_AVAILABLE}: If set , choose
     *       Wi-Fi when cellular is available at home network.
     * </ul>
     */
    static final String KEY_QNS_SOS_RAT_PREFERENCE_INT = "qns.sos_rat_preference_int";

    /**
     * Specifies the Rat Preference for the MMS network capability. Boolean indicating adding the
     * IMS Registration condition to the Wi-Fi Rove in condition.
     *
     * <p>{@code QnsConstants#RAT_PREFERENCE_DEFAULT}: Default value , Follow the system preference.
     * {@code QnsConstants#RAT_PREFERENCE_WIFI_ONLY}: If set , choose Wi-Fi always {@code
     * QnsConstants#RAT_PREFERENCE_WIFI_WHEN_WFC_AVAILABLE}: If set , choose Wi-Fi when the Wi-Fi
     * Calling is available.(when IMS is registered through the Wi-Fi) {@code
     * QnsConstants#RAT_PREFERENCE_WIFI_WHEN_NO_CELLULAR}: If set , choose Wi-Fi when no cellular
     * {@code QnsConstants#RAT_PREFERENCE_WIFI_WHEN_HOME_IS_NOT_AVAILABLE}: If set , choose Wi-Fi
     * when cellular is available at home network. The default value for this key is {@code
     * QnsConstants#RAT_PREFERENCE_DEFAULT}
     */
    static final String KEY_QNS_MMS_RAT_PREFERENCE_INT = "qns.mms_rat_preference_int";

    /**
     * Specifies the Rat Preference for the CBS network capability. Boolean indicating adding the
     * IMS Registration condition to the Wi-Fi Rove in condition.
     *
     * <p>{@code QnsConstants#RAT_PREFERENCE_DEFAULT}: Default value , Follow the system preference.
     * {@code QnsConstants#RAT_PREFERENCE_WIFI_ONLY}: If set , choose Wi-Fi always {@code
     * QnsConstants#RAT_PREFERENCE_WIFI_WHEN_WFC_AVAILABLE}: If set , choose Wi-Fi when the Wi-Fi
     * Calling is available.(when IMS is registered through the Wi-Fi) {@code
     * QnsConstants#RAT_PREFERENCE_WIFI_WHEN_NO_CELLULAR}: If set , choose Wi-Fi when no cellular
     * {@code QnsConstants#RAT_PREFERENCE_WIFI_WHEN_HOME_IS_NOT_AVAILABLE}: If set , choose Wi-Fi
     * when cellular is available at home network. The default value for this key is {@code
     * QnsConstants#RAT_PREFERENCE_DEFAULT}
     */
    static final String KEY_QNS_CBS_RAT_PREFERENCE_INT = "qns.cbs_rat_preference_int";

    /**
     * Specifies the interval at which the Wifi Backhaul timer in milli seconds, for threshold Wifi
     * rssi signal strength fluctuation in case, on meeting the criteria in Rove In Scenario (Moving
     * to Cellular to Wifi) {@link QnsConstants}. The values are set as below:
     *
     * <ul>
     *   <li>0: {@link QnsConstants#DEFAULT_WIFI_BACKHAUL_TIMER}
     *   <li>1: {@link QnsConstants#KEY_DEFAULT_VALUE}
     * </ul>
     *
     * &As per operator Requirements.
     *
     * <p>{@code QnsConstants#DEFAULT_WIFI_BACKHAUL_TIMER}: If set , specifies interval of 3secs
     * running the backhaul check(To avoid Wifi Fluctuation) on meeting the criteria in Rove in case
     * {@code QnsConstants#KEY_DEFAULT_VALUE}: If set , this feature to be disabled <As per Operator
     * requirement configurable>: If this value set , specifies interval in milli seconds running
     * the backhaul check. The default value for this key is {@link
     * QnsConstants#DEFAULT_WIFI_BACKHAUL_TIMER}
     */
    static final String KEY_QNS_WIFI_RSSI_THRESHOLDBACKHAUL_TIMER_MS_INT =
            "qns.wifi_rssi_thresholdbackhaul_timer_int";

    /**
     * Specifies the interval at which the Cellular Backhaul timer in milli seconds for cellular
     * signal strengths fluctuation in case, on meeting the criteria in Rove out Scenario (Moving to
     * Wifi from Cellular) The values are set as below:
     *
     * <ul>
     *   <li>0: {@link QnsConstants#KEY_DEFAULT_VALUE}
     * </ul>
     *
     * &As per operator Requirements.
     *
     * <p>{@code QnsConstants#KEY_DEFAULT_VALUE}: If set , this feature to be disabled <As per
     * Operator requirement configurable>: If this value set , specifies interval in milli seconds
     * running the backhaul check over Cellular in Rove Out The default value for this key is {@link
     * QnsConstants#KEY_DEFAULT_VALUE}
     */
    static final String KEY_QNS_CELLULAR_SS_THRESHOLDBACKHAUL_TIMER_MS_INT =
            "qns.cellular_ss_thresholdbackhaul_timer_int";

    /**
     * Specifies the Transport type UE supports with QNS services for IMS network capability. {@link
     * QnsConstants}. The values are set as below:
     *
     * <ul>
     *   <li>0: {@link QnsConstants#TRANSPORT_TYPE_ALLOWED_WWAN}
     *   <li>1: {@link QnsConstants#TRANSPORT_TYPE_ALLOWED_IWLAN}
     *   <li>2: {@link QnsConstants#TRANSPORT_TYPE_ALLOWED_BOTH}
     * </ul>
     *
     * {@code QnsConstants#TRANSPORT_TYPE_ALLOWED_WWAN}: If set , Transport type UE supports is
     * cellular for IMS network capability. {@code QnsConstants#TRANSPORT_TYPE_ALLOWED_IWLAN}: If
     * this value set , Transport type UE supports is Wifi for IMS network capability. {@code
     * QnsConstants#TRANSPORT_TYPE_ALLOWED_BOTH}: If this value set , Transport type UE supports is
     * both Cellular & Wifi for IMS network capability The default value for this key is {@link
     * QnsConstants#TRANSPORT_TYPE_ALLOWED_BOTH}
     */
    static final String KEY_QNS_IMS_TRANSPORT_TYPE_INT = "qns.ims_transport_type_int";

    /**
     * Specifies the Transport type UE supports with QNS services for SOS network capability. {@link
     * QnsConstants}. The values are set as below:
     *
     * <ul>
     *   <li>0: {@link QnsConstants#TRANSPORT_TYPE_ALLOWED_WWAN}
     *   <li>1: {@link QnsConstants#TRANSPORT_TYPE_ALLOWED_IWLAN}
     *   <li>2: {@link QnsConstants#TRANSPORT_TYPE_ALLOWED_BOTH}
     * </ul>
     *
     * {@code QnsConstants#TRANSPORT_TYPE_ALLOWED_WWAN}: If set , Transport type UE supports is
     * cellular for SOS network capability. {@code QnsConstants#TRANSPORT_TYPE_ALLOWED_IWLAN}: If
     * this value set , Transport type UE supports is Wifi for SOS network capability. {@code
     * QnsConstants#TRANSPORT_TYPE_ALLOWED_BOTH}: If this value set , Transport type UE supports is
     * both Cellular & Wifi for SOS network capability. The default value for this key is {@link
     * QnsConstants#TRANSPORT_TYPE_ALLOWED_WWAN}
     */
    static final String KEY_QNS_SOS_TRANSPORT_TYPE_INT = "qns.sos_transport_type_int";

    /**
     * Specifies the Transport type UE supports with QNS services for MMS network capability. {@link
     * QnsConstants}. The values are set as below:
     *
     * <ul>
     *   <li>0: {@link QnsConstants#TRANSPORT_TYPE_ALLOWED_WWAN}
     *   <li>1: {@link QnsConstants#TRANSPORT_TYPE_ALLOWED_IWLAN}
     *   <li>2: {@link QnsConstants#TRANSPORT_TYPE_ALLOWED_BOTH}
     * </ul>
     *
     * {@code QnsConstants#TRANSPORT_TYPE_ALLOWED_WWAN}: If set , Transport type UE supports is
     * cellular for MMS network capability. {@code QnsConstants#TRANSPORT_TYPE_ALLOWED_IWLAN}: If
     * this value set , Transport type UE supports is Wifi for MMS network capability. {@code
     * QnsConstants#TRANSPORT_TYPE_ALLOWED_BOTH}: If this value set , Transport type UE supports is
     * both Cellular & Wifi for MMS network capability. The default value for this key is {@link
     * QnsConstants#TRANSPORT_TYPE_ALLOWED_WWAN}
     */
    static final String KEY_QNS_MMS_TRANSPORT_TYPE_INT = "qns.mms_transport_type_int";

    /**
     * Specifies the Transport type UE supports with QNS services for XCAP network capability.
     * {@link QnsConstants}. The values are set as below:
     *
     * <ul>
     *   <li>0: {@link QnsConstants#TRANSPORT_TYPE_ALLOWED_WWAN}
     *   <li>1: {@link QnsConstants#TRANSPORT_TYPE_ALLOWED_IWLAN}
     *   <li>2: {@link QnsConstants#TRANSPORT_TYPE_ALLOWED_BOTH}
     * </ul>
     *
     * <p>{@code QnsConstants#TRANSPORT_TYPE_ALLOWED_WWAN}: If set , Transport type UE supports is
     * cellular for XCAP network capability. {@code QnsConstants#TRANSPORT_TYPE_ALLOWED_IWLAN}: If
     * this value set , Transport type UE supports is Wifi for XCAP network capability. {@code
     * QnsConstants#TRANSPORT_TYPE_ALLOWED_BOTH}: If this value set , Transport type UE supports is
     * both Cellular & Wifi for XCAP network capability. The default value for this key is {@link
     * QnsConstants#TRANSPORT_TYPE_ALLOWED_WWAN}
     */
    static final String KEY_QNS_XCAP_TRANSPORT_TYPE_INT = "qns.xcap_transport_type_int";

    /**
     * Specifies the Transport type UE supports with QNS services for CBS network capability. {@link
     * QnsConstants}. The values are set as below:
     *
     * <ul>
     *   <li>0: {@link QnsConstants#TRANSPORT_TYPE_ALLOWED_WWAN}
     *   <li>1: {@link QnsConstants#TRANSPORT_TYPE_ALLOWED_IWLAN}
     *   <li>2: {@link QnsConstants#TRANSPORT_TYPE_ALLOWED_BOTH}
     * </ul>
     *
     * {@code QnsConstants#TRANSPORT_TYPE_ALLOWED_WWAN}: If set , Transport type UE supports is
     * cellular for CBS network capability. {@code QnsConstants#TRANSPORT_TYPE_ALLOWED_IWLAN}: If
     * this value set , Transport type UE supports is Wifi for CBS network capability. {@code
     * QnsConstants#TRANSPORT_TYPE_ALLOWED_BOTH}: If this value set , Transport type UE supports is
     * both Cellular & Wifi for CBS network capability. The default value for this key is {@link
     * QnsConstants#TRANSPORT_TYPE_ALLOWED_WWAN}
     */
    static final String KEY_QNS_CBS_TRANSPORT_TYPE_INT = "qns.cbs_transport_type_int";

    /**
     * For IMS PDN, specify a list of the hysteresis timer(millisecond) for handover from WLAN and
     * WWAN to avoid ping-pong effect.
     *
     * <ul>
     *   <li>Index 0: The hysteresis timer for handover from WLAN and WWAN in idle state.
     *   <li>Index 1: The hysteresis timer for handover from WLAN and WWAN in voice call state.
     *   <li>Index 2: The hysteresis timer for handover from WLAN and WWAN in video call state.
     * </ul>
     *
     * <p>The default values are {@link QnsConstants#KEY_DEFAULT_HYST_TIMER}
     */
    static final String KEY_IMS_WWAN_HYSTERESIS_TIMER_MS_INT_ARRAY =
            "qns.ims_wwan_hysteresis_timer_ms_int_array";

    /**
     * For IMS PDN, specify a list of the hysteresis timer(millisecond) for handover from WWAN and
     * WLAN to avoid ping-pong effect.
     *
     * <ul>
     *   <li>Index 0: The hysteresis timer for handover from WWAN and WLAN in idle state.
     *   <li>Index 1: The hysteresis timer for handover from WWAN and WLAN in voice call state.
     *   <li>Index 2: The hysteresis timer for handover from WWAN and WLAN in video call state.
     * </ul>
     *
     * <p>The default values are {@link QnsConstants#KEY_DEFAULT_HYST_TIMER}
     */
    static final String KEY_IMS_WLAN_HYSTERESIS_TIMER_MS_INT_ARRAY =
            "qns.ims_wlan_hysteresis_timer_ms_int_array";

    /**
     * Location(HOME/ROAM) of using handover hysteresis timer
     * <li>0: {@link QnsConstants#COVERAGE_HOME}
     * <li>1: {@link QnsConstants#COVERAGE_ROAM}
     * <li>2: {@link QnsConstants#COVERAGE_BOTH} The default value for this key is {@link
     *     QnsConstants#COVERAGE_BOTH}
     */
    static final String KEY_QNS_IMS_NETWORK_ENABLE_HO_HYSTERESIS_TIMER_INT =
            "qns.ims_network_enable_hysteresis_timer_int";

    /**
     * For MMS, XCAP and CBS PDNs, specify a list of the hysteresis timer(millisecond) for handover
     * from WLAN and WWAN to avoid ping-pong effect.
     *
     * <ul>
     *   <li>Index 0: The hysteresis timer for handover from WLAN to WWAN in idle state.
     *   <li>Index 1: The hysteresis timer for handover from WLAN to WWAN in call state.
     * </ul>
     *
     * <p>The default values are {@link QnsConstants#KEY_DEFAULT_VALUE}
     */
    static final String KEY_NON_IMS_WWAN_HYSTERESIS_TIMER_MS_INT_ARRAY =
            "qns.non_ims_wwan_hysteresis_timer_ms_int_array";

    /**
     * For MMS, XCAP and CBS PDNs, specify a list of the hysteresis timer(millisecond) for handover
     * from WWAN and WLAN to avoid ping-pong effect.
     *
     * <ul>
     *   <li>Index 0: The hysteresis timer for handover from WWAN and WLAN in idle state.
     *   <li>Index 1: The hysteresis timer for handover from WWAN and WLAN in call state.
     * </ul>
     *
     * <p>The default values are {@link QnsConstants#KEY_DEFAULT_VALUE}
     */
    static final String KEY_NON_IMS_WLAN_HYSTERESIS_TIMER_MS_INT_ARRAY =
            "qns.non_ims_wlan_hysteresis_timer_ms_int_array";

    /**
     * This item is the minimum handover guarding timer value when there is no guarding time for
     * handover.
     *
     * <p>{@code QnsConstants#CONFIG_DEFAULT_MIN_HANDOVER_GUARDING_TIMER} : default value of timer.
     * {@code QnsConstants#CONFIg_DEFAULT_MIN_HANDOVER_GUARDING_TIMER_LIMIT} : maximum allowable
     * value.
     */
    static final String KEY_MINIMUM_HANDOVER_GUARDING_TIMER_MS_INT =
            "qns.minimum_handover_guarding_timer_ms_int";

    /**
     * List of 4 items indicating the RTP media metrics criteria to be set ,to make HO decision
     * during Call. The three items part of integer array is jitter , Packet loss rate & RTP
     * interval.
     *
     * <p>{@link QnsConstants}. The values are set as below:
     *
     * <ul>
     *   <li>0: {@link QnsConstants#KEY_DEFAULT_JITTER}
     *   <li>1: {@link QnsConstants#KEY_DEFAULT_PACKET_LOSS_RATE}
     *   <li>2: {@link QnsConstants#KEY_DEFAULT_PACKET_LOSS_TIME_MILLIS}
     *   <li>3: {@link QnsConstants#KEY_DEFAULT_NO_RTP_INTERVAL_MILLIS}
     * </ul>
     *
     * {@code QnsConstants#KEY_DEFAULT_JITTER , QnsConstants#KEY_DEFAULT_PACKET_LOSS_RATE ,
     * QnsConstants#KEY_DEFAULT_NO_RTP_INTERVAL }: If set , indicates the jitter : Indicating the
     * latentcy , Packet loss rate : Indicating the percentage loss & RTP interval indicating the
     * interval in secs criteria to determine the HO decision . The default value for this key is
     * {@code QnsConstants#KEY_DEFAULT_JITTER, QnsConstants#KEY_DEFAULT_PACKET_LOSS_RATE,
     * QnsConstants#KEY_DEFAULT_PACKET_LOSS_TIME_MILLIS,
     * QnsConstants#KEY_DEFAULT_NO_RTP_INTERVAL_MILLIS}
     */
    static final String KEY_QNS_RTP_METRICS_INT_ARRAY = "qns.rtp_metrics_int_array";

    /**
     * Specify a list of the waiting time(millisecond) for the preferred transport type when power
     * up.
     *
     * <ul>
     *   <li>Index 0: The waiting time for WWAN in cellular preferred mode.
     *   <li>Index 1: The waiting time for WLAN in WiFi preferred mode.
     * </ul>
     *
     * <p>The default values are all {@link QnsConstants#KEY_DEFAULT_VALUE}
     *
     * <p>For example, if set 45000ms in the index 0 of this list, WLAN will be restricted 45000ms
     * in cellular preferred mode when power up, and the timer will be canceled if IMS PDN is
     * connected on WWAN within 45000ms.
     */
    static final String KEY_WAITING_TIME_FOR_PREFERRED_TRANSPORT_WHEN_POWER_ON_INT_ARRAY =
            "qns.waiting_time_for_preferred_transport_when_power_on_int_array";

    /**
     * Specifies the number of count allowed IWLAN on HO to cellular during call due to fallback
     * reason such as Wifi bad or RTP Low Quality Criteria
     *
     * <p>The Possible values are set as below: <rovein_count_allowed,rove_outfallback_reason
     *
     * <ul>
     *   <li><-1,-1></-1,-1>:{@link QnsConstants#MAX_COUNT_INVALID,QnsConstants#MAX_COUNT_INVALID}
     * </ul>
     *
     * & As per operator Requirements (Ex: 3,1 or 1,2)
     *
     * <p>The default value for this key is {@link QnsConstants#MAX_COUNT_INVALID,
     * QnsConstants#FALLBACK_REASON_INVALID}
     */
    static final String KEY_QNS_IN_CALL_ROVEIN_ALLOWED_COUNT_AND_FALLBACK_REASON_INT_ARRAY =
            "qns.in_call_rovein_allowed_and_fallback_reason_int_array";

    /**
     * Specifies the number of count allowed IWLAN on HO to cellular during call due to fallback
     * reason such as Wifi bad or RTP Low Quality Criteria
     *
     * <p>The Possible values are set as below:
     *
     * <ul>
     *   <li>Index 0: The waiting time for WLAN //If set to 0 , feature is disabled for WLAN-WWAN
     *   <li>Index 1: The waiting time for WWAN //If set to 0 , feature is disabled for WWAN-WLAN
     * </ul>
     *
     * The default value for this key is {@link
     * QnsConstants#KEY_DEFAULT_IWLAN_AVOID_TIME_LOW_RTP_QUALITY_MILLIS,
     * QnsConstants#KEY_DEFAULT_VALUE}
     */
    static final String KEY_QNS_HO_RESTRICT_TIME_WITH_LOW_RTP_QUALITY_MILLIS_INT_ARRAY =
            "qns.ho_restrict_time_with_low_rtp_quality_int_array";

    /**
     * Specify if choosing the transport type based on WFC preference mode when both WWAN and WLAN
     * are not able to meet service requirements.
     *
     * <p>The possible values are set as below:
     *
     * <ul>
     *   <li>1: {@link ImsMmTelManager#WIFI_MODE_CELLULAR_PREFERRED}
     *   <li>2: {@link ImsMmTelManager#WIFI_MODE_WIFI_PREFERRED}
     * </ul>
     *
     * {@code ImsMmTelManager#WIFI_MODE_CELLULAR_PREFERRED}: Only apply the design when WFC
     * preference mode is cellular preferred. Choose WWAN when cellular preferred and both WWAN and
     * WLAN are in bad condition. {@code ImsMmTelManager#WIFI_MODE_WIFI_PREFERRED}: Only apply the
     * design when WFC preference mode is WiFi preferred. Choose WLAN when WiFi preferred and both
     * WWAN and WLAN are in bad condition.
     *
     * <p>If set to {ImsMmTelManager#WIFI_MODE_CELLULAR_PREFERRED,
     * ImsMmTelManager#WIFI_MODE_WIFI_PREFERRED}, the design will apply on both cellular and WiFi
     * preference mode.
     *
     * <p>The default value for this key is empty. An empty array indicates staying on the current
     * transport when both WWAN and WLAN are not able to meet service requirements.
     */
    static final String KEY_CHOOSE_WFC_PREFERRED_TRANSPORT_IN_BOTH_BAD_CONDITION_INT_ARRAY =
            "qns.choose_wfc_preferred_transport_in_both_bad_condition_int_array";

    /**
     * String indicating parameters for RTT(round trip time) check using ICMP PING on IWLAN.
     *
     * <p>We recommend to use a server on IWLAN path for RTT check. A server which is not reached
     * via IWLAN connection may give inadequate result.
     *
     * <p>format:“<server_address>,<ping_count>,<intra_ping_interval>,<packet_size>,<rtt_criteria>,
     * <rtt_check_Interval>,<hyst_fallback_timer>” For Ex:
     * "epdg.epc.mnc001.mcc001.pub.3gppnetwork.org,5,100,32,100,1800000,600000"
     *
     * <p>The default value for this key is null indicating not enabled by default for round trip
     * time check.
     */
    static final String KEY_QNS_WLAN_RTT_BACKHAUL_CHECK_ON_ICMP_PING_STRING =
            "qns.wlan_rtt_backhaul_check_on_icmp_ping_string";

    /**
     * List of Array items indicating network capabilities with fallback support based on retry
     * count or retry timer or either of them with fallback guard timer to be set
     *
     * <p><string-array name="qns.fallback_on_initial_connection_failure_string_array" num="2" <item
     * value="<network_capability>:<retry_count>:<retry_timer>:<fallback_guard_timer>
     * :<max_fallback_count>"/> Note: All Timer Values to be in millis Example: <item
     * value="ims:3:60000:10000:2"/> <item value="mms:1:10000:60000:2"/>
     *
     * <p>The default value for this key is null indicating not enabled by default for fallback in
     * case of initial connection failure
     */
    static final String KEY_QNS_FALLBACK_ON_INITIAL_CONNECTION_FAILURE_STRING_ARRAY =
            "qns.fallback_on_initial_connection_failure_string_array";

    /**
     * List of Array items indicating the Access Network Allowed For IMS network capability. The
     * values are set as below: "LTE" "NR" "3G" "2G" The default value for this key is {@Code
     * "LTE","NR"}
     */
    static final String KEY_IMS_CELLULAR_ALLOWED_RAT_STRING_ARRAY =
            "qns.ims_cellular_allowed_rat_string_array";

    /**
     * List of Array items indicating the Access Network Allowed For IMS network capability. The
     * values are set as below: Format "<accessNetwork>:<meas_type>:<gap>" "eutran:rsrp:-2"
     * "ngran:ssrsrp:2" Note: Similar format followed across different accessNetwork & Measurement
     * TYpes The default value for this key is {@Code ""}
     */
    static final String KEY_QNS_ROVEIN_THRESHOLD_GAP_WITH_GUARD_TIMER_STRING_ARRAY =
            "qns.rove_in_threshold_gap_with_guard_timer_string_array";

    /**
     * List of Array items indicating IMS unregistered cause & time(millis) for fallback (to WWAN).
     *
     * <p><string-array name="qns.fallback_wwan_ims_unregistration_reason_string_array" num="2">
     * <!-- fallback WWAN with ImsReason 321~378,1503 during 60sec at cellular prefer mode -->
     * <item value="cause=321~378|1503, time=60000, preference=cell"/>
     * <!-- fallback WWAN with ImsReason 240,243,323~350 during 90sec -->
     * <item value="cause=240|243|323~350, time=90000"/> </string-array>
     *
     * <p>The default value for this key is {@Code ""}
     */
    static final String KEY_QNS_FALLBACK_WWAN_IMS_UNREGISTRATION_REASON_STRING_ARRAY =
            "qns.fallback_wwan_ims_unregistration_reason_string_array";

    /**
     * List of Array items indicating IMS HO registration fail cause & time(millis) for fallback (to
     * WWAN).
     *
     * <p><string-array name="qns.fallback_wwan_ims_ho_reigster_fail_reason_string_array" num="2">
     * <!-- fallback WWAN with ImsReason 321~378,1503 during 60sec at cellular prefer mode -->
     * <item value="cause=321~378|1503, time=60000, preference=cell"/>
     * <!-- fallback WWAN with ImsReason 240,243,323~350 during 90sec -->
     * <item value="cause=240|243|323~350, time=90000"/> </string-array>
     *
     * <p>The default value for this key is {@Code ""}
     */
    static final String KEY_QNS_FALLBACK_WWAN_IMS_HO_REGISTER_FAIL_REASON_STRING_ARRAY =
            "qns.fallback_wwan_ims_ho_reigster_fail_reason_string_array";

    /**
     * String array indicating network capabilities that they check international roaming condition.
     *
     * <p><string-array name="exclude_domestic_roaming_condition_string_array" num="3"> <item
     * value="ims"/> <item value="emergency"/> <item value="xcap"/> </string-array>
     */
    static final String KEY_APN_TYPES_WITH_INTERNATIONAL_ROAMING_CONDITION_STRING_ARRAY =
            "qns.apn_types_with_international_roaming_condition_string_array";

    /**
     * String array indicating PLMN list to be regarded as international roaming.
     *
     * <p> If only MCC is included without MNC, PLMNs that they have same MCC value will be
     * regarded as international roaming.
     * <string-array name="plmn_list_regarded_as_international_roaming_string_array" num="3">
     *     <item value="32070"/>
     *     <item value="320157"/>
     *     <item value="318"/>
     * </string-array>
     */
    static final String KEY_PLMN_LIST_REGARDED_AS_INTERNATIONAL_ROAMING_STRING_ARRAY =
            "plmn_list_regarded_as_international_roaming_string_array";

    /**
     * String array indicating PLMN list to be regarded as domestic roaming.
     *
     * <p> If only MCC is included without MNC, PLMNs that they have same MCC value will be
     * regarded as domestic roaming.
     * <string-array name="plmn_list_regarded_as_domestic_roaming_string_array" num="3">
     *     <item value="32070"/>
     *     <item value="320157"/>
     *     <item value="318"/>
     * </string-array>
     */
    static final String KEY_PLMN_LIST_REGARDED_AS_DOMESTIC_ROAMING_STRING_ARRAY =
            "plmn_list_regarded_as_domestic_roaming_string_array";

    private static final SparseArray<QnsCarrierConfigManager> mQnsCarrierConfigManagers =
            new SparseArray<>();

    static HashMap<Integer, String> sAccessNetworkMap =
            new HashMap<Integer, String>() {
                {
                    put(AccessNetworkConstants.AccessNetworkType.EUTRAN, "eutran");
                    put(AccessNetworkConstants.AccessNetworkType.UTRAN, "utran");
                    put(AccessNetworkConstants.AccessNetworkType.NGRAN, "ngran");
                    put(AccessNetworkConstants.AccessNetworkType.GERAN, "geran");
                    put(AccessNetworkConstants.AccessNetworkType.IWLAN, "wifi");
                }
            };

    static HashMap<Integer, String> sMeasTypeMap =
            new HashMap<Integer, String>() {
                {
                    put(SIGNAL_MEASUREMENT_TYPE_RSRP, "rsrp");
                    put(SIGNAL_MEASUREMENT_TYPE_RSRQ, "rsrq");
                    put(SIGNAL_MEASUREMENT_TYPE_RSSNR, "rssnr");
                    put(SIGNAL_MEASUREMENT_TYPE_SSRSRP, "ssrsrp");
                    put(SIGNAL_MEASUREMENT_TYPE_SSRSRQ, "ssrsrq");
                    put(SIGNAL_MEASUREMENT_TYPE_SSSINR, "sssinr");
                    put(SIGNAL_MEASUREMENT_TYPE_RSCP, "rscp");
                    put(SIGNAL_MEASUREMENT_TYPE_RSSI, "rssi");
                    put(SIGNAL_MEASUREMENT_TYPE_ECNO, "ecno");
                }
            };

    static HashMap<Integer, String> sCallTypeMap =
            new HashMap<Integer, String>() {
                {
                    put(QnsConstants.CALL_TYPE_IDLE, "idle");
                    put(QnsConstants.CALL_TYPE_VOICE, "voice");
                    put(QnsConstants.CALL_TYPE_VIDEO, "video");
                }
            };

    private final String mLogTag;
    private final int mSlotIndex;
    private final Context mContext;
    protected int mSubId;
    protected int mCurrCarrierId;
    private final QnsEventDispatcher mQnsEventDispatcher;
    private final QnsCarrierAnspSupportConfig mAnspConfigMgr;
    @VisibleForTesting final Handler mHandler;

    private boolean mIsWfcInAirplaneModeOnSupport;
    private boolean mIsInCallHoDecisionWlanToWwanWithoutVopsConditionSupported;
    private boolean mIsHoGuardOnPreferenceSupport;
    private boolean mIsServiceBarringCheckSupport;
    private boolean mIsVideoOverIWLANWithCellularCheckSupport;
    private boolean mIsRoveOutWifiBadGuardTimerConditionsSupported;
    private boolean mIsAllowImsOverIwlanCellularLimitedCase;
    private boolean mIsBlockIwlanInInternationalRoamWithoutWwan;
    private boolean mIsBlockIpv6OnlyWifi;
    private boolean mIsVolteRoamingSupported;
    private final boolean[] mAnspSupportConfigArray = new boolean[3];

    private int mWifiThresBackHaulTimer;
    private int mCellularThresBackHaulTimer;
    private int mQnsImsTransportType;
    private int mQnsSosTransportType;
    private int mQnsMmsTransportType;
    private int mQnsXcapTransportType;
    private int mQnsCbsTransportType;
    private int mXcapRatPreference;
    private int mSosRatPreference;
    private int mMmsRatPreference;
    private int mCbsRatPreference;
    private int mNetworkEnableHysteresisTimer;
    private int mMinimumHandoverGuardingTimer;

    private int[] mWwanHysteresisTimer;
    private int[] mWlanHysteresisTimer;
    private int[] mNonImsWwanHysteresisTimer;
    private int[] mNonImsWlanHysteresisTimer;
    private int[] mRTPMetricsData;
    private int[] mWaitingTimerForPreferredTransport;
    private int[] mAllowMaxIwlanHoCountOnReason;
    private int[] mHoRestrictTimeOnRtpQuality;
    private int[] mIsMmtelCapabilityRequired;
    private int[] mIsWfcPreferredTransportRequired;

    private String mWlanRttBackhaulCheckConfigsOnPing;
    private String[] mImsAllowedRats;
    private String[] mRoveInGuardTimerConditionThresholdGaps;
    private String[] mApnTypesInternationalRoamingCheck;
    private String[] mPlmnsRegardedAsDomesticRoaming;
    private String[] mPlmnsRegardedAsInternationalRoaming;
    private String[] mFallbackOnInitialConnectionFailure;

    private @NonNull final List<FallbackRule> mFallbackWwanRuleWithImsUnregistered =
            new ArrayList<>();
    private @NonNull final List<FallbackRule> mFallbackWwanRuleWithImsHoRegisterFail =
            new ArrayList<>();

    /** Rules for handover between IWLAN and cellular network. */
    private @NonNull List<HandoverRule> mHandoverRuleList = new ArrayList<>();

    protected QnsRegistrantList mQnsCarrierConfigLoadedRegistrants = new QnsRegistrantList();
    protected QnsRegistrantList mQnsCarrierConfigChangedRegistrants = new QnsRegistrantList();

    protected QnsProvisioningListener.QnsProvisioningInfo mQnsProvisioningInfo =
            new QnsProvisioningListener.QnsProvisioningInfo();

    void setQnsProvisioningInfo(QnsProvisioningListener.QnsProvisioningInfo info) {
        mQnsProvisioningInfo = info;
    }

    private QnsConfigArray applyProvisioningInfo(
            QnsConfigArray thresholds, int accessNetwork, int measurementType, int callType) {

        if (mQnsProvisioningInfo.hasItem(ProvisioningManager.KEY_LTE_THRESHOLD_1)
                && thresholds.mBad != QnsConfigArray.INVALID
                && accessNetwork == AccessNetworkConstants.AccessNetworkType.EUTRAN
                && measurementType == SIGNAL_MEASUREMENT_TYPE_RSRP) {
            int bad = mQnsProvisioningInfo.getIntegerItem(ProvisioningManager.KEY_LTE_THRESHOLD_1);
            Log.d(mLogTag, "provisioning bad THLTE1 old:" + thresholds.mBad + " new:" + bad);
            thresholds.mBad = bad;
        }
        if (mQnsProvisioningInfo.hasItem(ProvisioningManager.KEY_LTE_THRESHOLD_2)
                && thresholds.mWorst != QnsConfigArray.INVALID
                && accessNetwork == AccessNetworkConstants.AccessNetworkType.EUTRAN
                && measurementType == SIGNAL_MEASUREMENT_TYPE_RSRP) {
            int worst =
                    mQnsProvisioningInfo.getIntegerItem(ProvisioningManager.KEY_LTE_THRESHOLD_2);
            Log.d(mLogTag, "provisioning worst THLTE2 old:" + thresholds.mWorst + " new:" + worst);
            thresholds.mWorst = worst;
        }
        if (mQnsProvisioningInfo.hasItem(ProvisioningManager.KEY_LTE_THRESHOLD_3)
                && thresholds.mGood != QnsConfigArray.INVALID
                && accessNetwork == AccessNetworkConstants.AccessNetworkType.EUTRAN
                && measurementType == SIGNAL_MEASUREMENT_TYPE_RSRP) {
            int good = mQnsProvisioningInfo.getIntegerItem(ProvisioningManager.KEY_LTE_THRESHOLD_3);
            Log.d(mLogTag, "provisioning good THLTE3 old:" + thresholds.mGood + " new:" + good);
            thresholds.mGood = good;
        }
        if (mQnsProvisioningInfo.hasItem(ProvisioningManager.KEY_WIFI_THRESHOLD_A)
                && thresholds.mGood != QnsConfigArray.INVALID
                && accessNetwork == AccessNetworkConstants.AccessNetworkType.IWLAN
                && measurementType == SIGNAL_MEASUREMENT_TYPE_RSSI) {
            int good =
                    mQnsProvisioningInfo.getIntegerItem(ProvisioningManager.KEY_WIFI_THRESHOLD_A);
            Log.d(mLogTag, "provisioning good VOWT_A old:" + thresholds.mGood + " new:" + good);
            thresholds.mGood = good;
        }
        if (mQnsProvisioningInfo.hasItem(ProvisioningManager.KEY_WIFI_THRESHOLD_B)
                && thresholds.mBad != QnsConfigArray.INVALID
                && accessNetwork == AccessNetworkConstants.AccessNetworkType.IWLAN
                && measurementType == SIGNAL_MEASUREMENT_TYPE_RSSI) {
            int bad = mQnsProvisioningInfo.getIntegerItem(ProvisioningManager.KEY_WIFI_THRESHOLD_B);
            Log.d(mLogTag, "provisioning bad VOWT_B old:" + thresholds.mBad + " new:" + bad);
            thresholds.mBad = bad;
            // TODO : make video threshold gap config, and move in getThreshold...()
            if (getCarrierId() == 1839 && callType == QnsConstants.CALL_TYPE_VIDEO) {
                thresholds.mBad = bad + 5;
            }
        }

        return thresholds;
    }

    static class FallbackRule {
        /** Key : IMS registration fail reason, value : fallback time in millis */
        final Set<Integer> mReasons;

        final int mBackoffTimeMillis;
        final int mPreferenceMode;

        FallbackRule(Set<Integer> reasons, int backoffTimeMillis, int preferenceMode) {
            mReasons = reasons;
            mBackoffTimeMillis = backoffTimeMillis;
            mPreferenceMode = preferenceMode;
        }

        int getFallBackTime(int reason) {
            if (mReasons.contains(reason)) {
                return mBackoffTimeMillis;
            } else {
                return 0;
            }
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append("FallbackRule time:" + mBackoffTimeMillis);
            if (mPreferenceMode == -1) {
                builder.append(" ");
            } else if (mPreferenceMode == QnsConstants.CELL_PREF) {
                builder.append(" " + "CELL_PREF_MODE");
            } else if (mPreferenceMode == QnsConstants.WIFI_PREF) {
                builder.append(" " + "WIFI_PREF_MODE");
            }
            builder.append(" reasons:");
            for (Integer i : mReasons) {
                builder.append(i + " ");
            }
            return builder.toString();
        }
    }

    static class HandoverRule {
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(
                prefix = {"RULE_TYPE_"},
                value = {
                    RULE_TYPE_ALLOWED,
                    RULE_TYPE_DISALLOWED,
                })
        @interface HandoverRuleType {}

        /** Indicating this rule is for allowing handover. */
        static final int RULE_TYPE_ALLOWED = 1;

        /** Indicating this rule is for disallowing handover. */
        static final int RULE_TYPE_DISALLOWED = 2;

        private static final String RULE_TAG_SOURCE_ACCESS_NETWORKS = "source";

        private static final String RULE_TAG_TARGET_ACCESS_NETWORKS = "target";

        private static final String RULE_TAG_TYPE = "type";

        private static final String RULE_TAG_CAPABILITIES = "capabilities";

        private static final String RULE_TAG_ROAMING = "roaming";

        /** Handover rule type. */
        final @HandoverRuleType int mHandoverRuleType;

        /** The applicable source access networks for handover. */
        final @NonNull @AccessNetworkConstants.RadioAccessNetworkType Set<Integer>
                mSourceAccessNetworks;

        /** The applicable target access networks for handover. */
        final @NonNull @AccessNetworkConstants.RadioAccessNetworkType Set<Integer>
                mTargetAccessNetworks;

        /**
         * The network capabilities to any of which this handover rule applies. If is empty, then
         * capability is ignored as a rule matcher.
         */
        final @NonNull @NetCapability Set<Integer> mNetworkcapabilities;

        /** {@code true} indicates this policy is only applicable when the device is roaming. */
        final boolean mIsOnlyForRoaming;

        /**
         * Constructor
         *
         * @param ruleString The rule in string format.
         * @see CarrierConfigManager#KEY_IWLAN_HANDOVER_POLICY_STRING_ARRAY
         */
        HandoverRule(@NonNull String ruleString) {
            if (TextUtils.isEmpty(ruleString)) {
                throw new IllegalArgumentException("illegal rule " + ruleString);
            }

            Set<Integer> source = null, target = null, capabilities = Collections.emptySet();
            int type = 0;
            boolean roaming = false;

            ruleString = ruleString.trim().toLowerCase(Locale.ROOT);
            String[] expressions = ruleString.split("\\s*,\\s*");
            for (String expression : expressions) {
                String[] tokens = expression.trim().split("\\s*=\\s*");
                if (tokens.length != 2) {
                    throw new IllegalArgumentException(
                            "illegal rule " + ruleString + ", tokens=" + Arrays.toString(tokens));
                }
                String key = tokens[0].trim();
                String value = tokens[1].trim();
                try {
                    switch (key) {
                        case RULE_TAG_SOURCE_ACCESS_NETWORKS:
                            source =
                                    Arrays.stream(value.split("\\s*\\|\\s*"))
                                            .map(String::trim)
                                            .map(QnsConstants::accessNetworkTypeFromString)
                                            .collect(Collectors.toSet());
                            break;
                        case RULE_TAG_TARGET_ACCESS_NETWORKS:
                            target =
                                    Arrays.stream(value.split("\\s*\\|\\s*"))
                                            .map(String::trim)
                                            .map(QnsConstants::accessNetworkTypeFromString)
                                            .collect(Collectors.toSet());
                            break;
                        case RULE_TAG_TYPE:
                            if (value.toLowerCase(Locale.ROOT).equals("allowed")) {
                                type = RULE_TYPE_ALLOWED;
                            } else if (value.toLowerCase(Locale.ROOT).equals("disallowed")) {
                                type = RULE_TYPE_DISALLOWED;
                            } else {
                                throw new IllegalArgumentException("unexpected rule type " + value);
                            }
                            break;
                        case RULE_TAG_CAPABILITIES:
                            capabilities = QnsUtils.getNetworkCapabilitiesFromString(value);
                            break;
                        case RULE_TAG_ROAMING:
                            roaming = Boolean.parseBoolean(value);
                            break;
                        default:
                            throw new IllegalArgumentException("unexpected key " + key);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    throw new IllegalArgumentException(
                            "illegal rule \"" + ruleString + "\", e=" + e);
                }
            }

            if (source == null || target == null || source.isEmpty() || target.isEmpty()) {
                throw new IllegalArgumentException(
                        "Need to specify both source and target. " + "\"" + ruleString + "\"");
            }

            if (source.contains(AccessNetworkConstants.AccessNetworkType.UNKNOWN)) {
                throw new IllegalArgumentException(
                        "Source access networks contains unknown. " + "\"" + ruleString + "\"");
            }

            if (target.contains(AccessNetworkConstants.AccessNetworkType.UNKNOWN)) {
                throw new IllegalArgumentException(
                        "Target access networks contains unknown. " + "\"" + ruleString + "\"");
            }

            if (type == 0) {
                throw new IllegalArgumentException(
                        "Rule type is not specified correctly. " + "\"" + ruleString + "\"");
            }

            if (capabilities != null && capabilities.contains(-1)) {
                throw new IllegalArgumentException(
                        "Network capabilities contains unknown. " + "\"" + ruleString + "\"");
            }

            if (!source.contains(AccessNetworkConstants.AccessNetworkType.IWLAN)
                    && !target.contains(AccessNetworkConstants.AccessNetworkType.IWLAN)) {
                throw new IllegalArgumentException(
                        "IWLAN must be specified in either source or "
                                + "target access networks.\""
                                + ruleString
                                + "\"");
            }

            mSourceAccessNetworks = source;
            mTargetAccessNetworks = target;
            this.mHandoverRuleType = type;
            mNetworkcapabilities = capabilities;
            mIsOnlyForRoaming = roaming;
        }

        @Override
        public String toString() {
            return "[HandoverRule: type="
                    + (mHandoverRuleType == RULE_TYPE_ALLOWED ? "allowed" : "disallowed")
                    + ", source="
                    + mSourceAccessNetworks.stream()
                            .map(QnsConstants::accessNetworkTypeToString)
                            .collect(Collectors.joining("|"))
                    + ", target="
                    + mTargetAccessNetworks.stream()
                            .map(QnsConstants::accessNetworkTypeToString)
                            .collect(Collectors.joining("|"))
                    + ", isRoaming="
                    + mIsOnlyForRoaming
                    + ", capabilities="
                    + QnsUtils.networkCapabilitiesToString(mNetworkcapabilities)
                    + "]";
        }
    }

    private class QnsCarrierConfigChangeHandler extends Handler {
        QnsCarrierConfigChangeHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message message) {
            switch (message.what) {
                case QnsEventDispatcher.QNS_EVENT_CARRIER_CONFIG_CHANGED:
                    Log.d(mLogTag, "Event Received QNS_EVENT_CARRIER_CONFIG_CHANGED");
                    if (SubscriptionManager.isValidSubscriptionId(getSubId())) {
                        int mNewCarrierID = getCarrierId();
                        Log.d(mLogTag, "new carrier id " + mNewCarrierID);
                        Log.d(mLogTag, "current carrier id " + mCurrCarrierId);
                        if (mNewCarrierID != 0 && mNewCarrierID != UNKNOWN_CARRIER_ID) {
                            if (mCurrCarrierId != mNewCarrierID) {
                                mCurrCarrierId = mNewCarrierID;
                                loadQnsConfigurations();
                                notifyLoadQnsConfigurationsCompleted();
                            } else {
                                if (isQnsConfigChanged()) {
                                    Log.d(mLogTag, "Qns Carrier config updated found");
                                    notifyQnsConfigurationsChanged();
                                }
                            }
                        }
                    }
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * Constructor to Initial Slot and Context whose carrier config ID needs to be loaded along with
     * initialising the Action Intent on which Carrier Config ID to be loaded.
     *
     * @param slotIndex : Constructor for initialising the current Slot ID
     * @param context : Constructor for initialising the current context
     */
    private QnsCarrierConfigManager(int slotIndex, Context context) {
        mLogTag =
                QnsConstants.QNS_TAG
                        + "_"
                        + QnsCarrierConfigManager.class.getSimpleName()
                        + "_"
                        + slotIndex;
        mQnsEventDispatcher = QnsEventDispatcher.getInstance(context, slotIndex);
        mAnspConfigMgr = QnsCarrierAnspSupportConfig.getInstance(context, slotIndex);
        mSlotIndex = slotIndex;
        mContext = context;

        HandlerThread handlerThread = new HandlerThread(mLogTag);
        handlerThread.start();
        mHandler =
                new QnsCarrierConfigManager.QnsCarrierConfigChangeHandler(
                        handlerThread.getLooper());

        List<Integer> events = new ArrayList<>();
        events.add(QnsEventDispatcher.QNS_EVENT_CARRIER_CONFIG_CHANGED);
        mQnsEventDispatcher.registerEvent(events, mHandler);

        // sending empty message when new object created; as actual event will not be received in
        // case QNS restarts.
        // This EVENT will not be processed in bootup case since carrier id will be invalid until
        // actual event received from QnsEventDispatcher.
        mHandler.sendEmptyMessage(QnsEventDispatcher.QNS_EVENT_CARRIER_CONFIG_CHANGED);

        // To do : Update based on xml version Changes handling
        // To do : Operator Update on Threshold changes handling
    }

    /**
     * Below API is exposed to other classes in QNS package to register for carrier config Loading
     * corresponding key items from xml/bundle date based on NetworkProviderImpl
     *
     * @param context : Context at which QNSCarrierConfigManager instance to be created
     * @param slotIndex : slot ID at which QNSCarrierConfigManager instance to be created
     */
    static QnsCarrierConfigManager getInstance(Context context, int slotIndex) {
        QnsCarrierConfigManager qnsCarrierConfigManager = mQnsCarrierConfigManagers.get(slotIndex);
        if (qnsCarrierConfigManager != null) {
            return qnsCarrierConfigManager;
        }
        qnsCarrierConfigManager = new QnsCarrierConfigManager(slotIndex, context);
        mQnsCarrierConfigManagers.put(slotIndex, qnsCarrierConfigManager);
        return qnsCarrierConfigManager;
    }

    /**
     * Below API is exposed to other classes in QNS package, to close QNS carrier config manager
     * mapped to slot index & remove the Access Network Selection Policies
     *
     * @param slotIndex : slot ID at which QNSCarrierConfigManager to be cloed
     */
    static void closeQnsCarrierConfigManager(int slotIndex) {
        QnsCarrierConfigManager cm = mQnsCarrierConfigManagers.get(slotIndex);
        if (cm != null) {
            mQnsCarrierConfigManagers.remove(slotIndex);
            cm.close();
        }
    }

    /** Below API clears the current Access Network selection Policies */
    void close() {
        if (mHandler != null) mQnsEventDispatcher.unregisterEvent(mHandler);
        mAnspConfigMgr.close();
        mQnsCarrierConfigManagers.remove(mSlotIndex);
    }

    synchronized PersistableBundle readFromCarrierConfigManager(
            Context context, int slotidx) {
        PersistableBundle carrierConfigbundle = new PersistableBundle();
        CarrierConfigManager carrierConfigManager =
                context.getSystemService(CarrierConfigManager.class);

        if (carrierConfigManager == null) {
            throw new IllegalStateException("Carrier config manager is null.");
        }
        carrierConfigbundle = carrierConfigManager.getConfigForSubId(getSubId());

        return carrierConfigbundle;
    }

    synchronized PersistableBundle readFromAssets(Context context, int slotidx) {
        PersistableBundle assetBundle = new PersistableBundle();

        assetBundle = QnsUtils.readQnsDefaultConfigFromAssets(context, mCurrCarrierId);

        if (assetBundle == null) {
            throw new IllegalStateException("Carrier config manager is null.");
        }

        return assetBundle;
    }

    /** Below API is used for Loading the carrier configurations based on Current Carrier ID */
    void loadQnsConfigurations() {

        PersistableBundle carrierConfigBundle = readFromCarrierConfigManager(mContext, mSlotIndex);
        Log.d(mLogTag, "CarrierConfig Bundle for Slot: " + mSlotIndex + carrierConfigBundle);

        PersistableBundle assetConfigBundle = readFromAssets(mContext, mSlotIndex);
        Log.d(mLogTag, "AssetConfig Bundle for Slot: " + mSlotIndex + assetConfigBundle);

        // load configurations supporting ANE
        loadQnsAneSupportConfigurations(carrierConfigBundle, assetConfigBundle);

        // load qns Ansp (Access Network Selection Policy) carrier Support Configurations
        // for building Internal ANSP Policies
        loadAnspCarrierSupportConfigs(carrierConfigBundle, assetConfigBundle);

        mAnspConfigMgr.loadQnsAnspSupportArray(carrierConfigBundle, assetConfigBundle);

        // Load configs using Carrier Config Manager Keys
        loadDirectFromCarrierConfigManagerKey(carrierConfigBundle);
    }

    /**
     * Below API takes care of loading the configuration based on the carrier config Manager
     * available for given carrier config manager keys.
     */
    void loadDirectFromCarrierConfigManagerKey(PersistableBundle bundleCarrier) {
        loadHandoverRules(
                bundleCarrier, null, CarrierConfigManager.KEY_IWLAN_HANDOVER_POLICY_STRING_ARRAY);
        loadCarrierConfig(bundleCarrier, null);
    }

    /**
     * Below API takes care of validating the configs (Threshold & HO rules) Updates after loading
     * Qns configurations, for the current operator in use, in case of config update scenario
     *
     * @return : true/false
     */
    synchronized boolean isQnsConfigChanged() {
        PersistableBundle carrierConfigBundle = readFromCarrierConfigManager(mContext, mSlotIndex);
        Log.d(
                mLogTag,
                "Check carrier config for Qns item changefor_slot: "
                        + mSlotIndex
                        + "_"
                        + carrierConfigBundle);
        PersistableBundle assetConfigBundle = readFromAssets(mContext, mSlotIndex);
        Log.d(
                mLogTag,
                "Check Asset config for Qns item changefor_slot: "
                        + mSlotIndex
                        + "_"
                        + assetConfigBundle);

        boolean isThresholdConfigChanged =
                checkThresholdConfigChange(carrierConfigBundle, assetConfigBundle);
        boolean isHandoverRulesChanged =
                checkHandoverRuleConfigChange(
                        carrierConfigBundle,
                        null,
                        CarrierConfigManager.KEY_IWLAN_HANDOVER_POLICY_STRING_ARRAY);
        Log.d(
                mLogTag,
                "Threshold config changed = "
                        + isThresholdConfigChanged
                        + ", IMS handover rule changed = "
                        + isHandoverRulesChanged);

        return (isThresholdConfigChanged || isHandoverRulesChanged);
    }

    /**
     * Below API takes to check if current HO rules as any difference with existing HO rules Updated
     * based on Event Carrier config change event received after initial loading @Param
     * PersistableBundle : configBundle
     *
     * @return true/false
     */
    synchronized boolean checkHandoverRuleConfigChange(
            PersistableBundle carrierConfigBundle,
            PersistableBundle assetConfigBundle,
            String key) {
        List<HandoverRule> handoverUpdateRuleList =
                updateHandoverRules(carrierConfigBundle, assetConfigBundle, key);

        Log.d(mLogTag, "New rule:" + handoverUpdateRuleList.toString());
        Log.d(mLogTag, "Existing rule:" + mHandoverRuleList.toString());

        if (mHandoverRuleList.toString().equals(handoverUpdateRuleList.toString())
                || handoverUpdateRuleList.isEmpty()
                || mHandoverRuleList.isEmpty()) {
            handoverUpdateRuleList.clear();
            return false;
        } else {
            mHandoverRuleList = new ArrayList<>(handoverUpdateRuleList);
            Log.d(mLogTag, "New rule Updated:" + mHandoverRuleList.toString());
            handoverUpdateRuleList.clear();
            return true;
        }
    }

    /**
     * Below API takes to check if ANSP threshold configs was Updated based on Event Carrier config
     * change event received after initial Qns configuration loading is completed
     */
    synchronized boolean checkThresholdConfigChange(
            PersistableBundle carrierConfigBundle, PersistableBundle assetConfigBundle) {

        return mAnspConfigMgr.checkQnsAnspConfigChange(carrierConfigBundle, assetConfigBundle);
    }

    /**
     * Below API takes care of loading the configuration based on the Bundle data built based on
     * asset folder xml file . (Except reading Key item of threshold & ANSP
     */
    void loadQnsAneSupportConfigurations(
            PersistableBundle bundleCarrier, PersistableBundle bundleAsset) {

        mIsWfcInAirplaneModeOnSupport =
                getConfig(
                        bundleCarrier, bundleAsset, KEY_QNS_SUPPORT_WFC_DURING_AIRPLANE_MODE_BOOL);
        mIsInCallHoDecisionWlanToWwanWithoutVopsConditionSupported =
                getConfig(
                        bundleCarrier,
                        bundleAsset,
                        KEY_IN_CALL_HO_DECISION_WLAN_TO_WWAN_WITHOUT_VOPS_CONDITION_BOOL);
        mIsHoGuardOnPreferenceSupport =
                getConfig(bundleCarrier, bundleAsset, KEY_QNS_HO_GUARDING_BY_PREFERENCE_BOOL);
        mIsServiceBarringCheckSupport =
                getConfig(bundleCarrier, bundleAsset, KEY_QNS_SUPPORT_SERVICE_BARRING_CHECK_BOOL);
        mIsVideoOverIWLANWithCellularCheckSupport =
                getConfig(
                        bundleCarrier,
                        bundleAsset,
                        KEY_QNS_ALLOW_VIDEO_OVER_IWLAN_WITH_CELLULAR_LIMITED_CASE_BOOL);
        mIsRoveOutWifiBadGuardTimerConditionsSupported =
                getConfig(
                        bundleCarrier,
                        bundleAsset,
                        KEY_QNS_ROVE_OUT_POLICY_WITH_WIFI_BAD_GUARDTIMER_CONDITIONS_BOOL);
        mIsAllowImsOverIwlanCellularLimitedCase =
                getConfig(
                        bundleCarrier,
                        bundleAsset,
                        KEY_QNS_ALLOW_IMS_OVER_IWLAN_CELLULAR_LIMITED_CASE_BOOL);
        mIsBlockIwlanInInternationalRoamWithoutWwan =
                getConfig(
                        bundleCarrier,
                        bundleAsset,
                        KEY_BLOCK_IWLAN_IN_INTERNATIONAL_ROAMING_WITHOUT_WWAN_BOOL);
        mIsBlockIpv6OnlyWifi = getConfig(bundleCarrier, bundleAsset, KEY_BLOCK_IPV6_ONLY_WIFI_BOOL);

        mWifiThresBackHaulTimer =
                getConfig(
                        bundleCarrier,
                        bundleAsset,
                        KEY_QNS_WIFI_RSSI_THRESHOLDBACKHAUL_TIMER_MS_INT);
        mCellularThresBackHaulTimer =
                getConfig(
                        bundleCarrier,
                        bundleAsset,
                        KEY_QNS_CELLULAR_SS_THRESHOLDBACKHAUL_TIMER_MS_INT);
        mQnsImsTransportType =
                getConfig(bundleCarrier, bundleAsset, KEY_QNS_IMS_TRANSPORT_TYPE_INT);
        mQnsSosTransportType =
                getConfig(bundleCarrier, bundleAsset, KEY_QNS_SOS_TRANSPORT_TYPE_INT);
        mQnsMmsTransportType =
                getConfig(bundleCarrier, bundleAsset, KEY_QNS_MMS_TRANSPORT_TYPE_INT);
        mQnsXcapTransportType =
                getConfig(bundleCarrier, bundleAsset, KEY_QNS_XCAP_TRANSPORT_TYPE_INT);
        mQnsCbsTransportType =
                getConfig(bundleCarrier, bundleAsset, KEY_QNS_CBS_TRANSPORT_TYPE_INT);
        mQnsCbsTransportType =
                getConfig(bundleCarrier, bundleAsset, KEY_QNS_CBS_TRANSPORT_TYPE_INT);
        mXcapRatPreference = getConfig(bundleCarrier, bundleAsset, KEY_QNS_XCAP_RAT_PREFERENCE_INT);
        mSosRatPreference = getConfig(bundleCarrier, bundleAsset, KEY_QNS_SOS_RAT_PREFERENCE_INT);
        mMmsRatPreference = getConfig(bundleCarrier, bundleAsset, KEY_QNS_MMS_RAT_PREFERENCE_INT);
        mCbsRatPreference = getConfig(bundleCarrier, bundleAsset, KEY_QNS_CBS_RAT_PREFERENCE_INT);
        mNetworkEnableHysteresisTimer =
                getConfig(
                        bundleCarrier,
                        bundleAsset,
                        KEY_QNS_IMS_NETWORK_ENABLE_HO_HYSTERESIS_TIMER_INT);

        mWwanHysteresisTimer =
                getConfig(bundleCarrier, bundleAsset, KEY_IMS_WWAN_HYSTERESIS_TIMER_MS_INT_ARRAY);
        mWlanHysteresisTimer =
                getConfig(bundleCarrier, bundleAsset, KEY_IMS_WLAN_HYSTERESIS_TIMER_MS_INT_ARRAY);
        mNonImsWwanHysteresisTimer =
                getConfig(
                        bundleCarrier, bundleAsset, KEY_NON_IMS_WWAN_HYSTERESIS_TIMER_MS_INT_ARRAY);
        mNonImsWlanHysteresisTimer =
                getConfig(
                        bundleCarrier, bundleAsset, KEY_NON_IMS_WLAN_HYSTERESIS_TIMER_MS_INT_ARRAY);
        mMinimumHandoverGuardingTimer =
                getConfig(bundleCarrier, bundleAsset, KEY_MINIMUM_HANDOVER_GUARDING_TIMER_MS_INT);
        mWaitingTimerForPreferredTransport =
                getConfig(
                        bundleCarrier,
                        bundleAsset,
                        KEY_WAITING_TIME_FOR_PREFERRED_TRANSPORT_WHEN_POWER_ON_INT_ARRAY);
        mAllowMaxIwlanHoCountOnReason =
                getConfig(
                        bundleCarrier,
                        bundleAsset,
                        KEY_QNS_IN_CALL_ROVEIN_ALLOWED_COUNT_AND_FALLBACK_REASON_INT_ARRAY);
        mHoRestrictTimeOnRtpQuality =
                getConfig(
                        bundleCarrier,
                        bundleAsset,
                        KEY_QNS_HO_RESTRICT_TIME_WITH_LOW_RTP_QUALITY_MILLIS_INT_ARRAY);
        mRTPMetricsData = getConfig(bundleCarrier, bundleAsset, KEY_QNS_RTP_METRICS_INT_ARRAY);
        mWlanRttBackhaulCheckConfigsOnPing =
                getConfig(
                        bundleCarrier,
                        bundleAsset,
                        KEY_QNS_WLAN_RTT_BACKHAUL_CHECK_ON_ICMP_PING_STRING);

        mFallbackOnInitialConnectionFailure =
                getConfig(
                        bundleCarrier,
                        bundleAsset,
                        KEY_QNS_FALLBACK_ON_INITIAL_CONNECTION_FAILURE_STRING_ARRAY);
        mImsAllowedRats =
                getConfig(bundleCarrier, bundleAsset, KEY_IMS_CELLULAR_ALLOWED_RAT_STRING_ARRAY);
        mRoveInGuardTimerConditionThresholdGaps =
                getConfig(
                        bundleCarrier,
                        bundleAsset,
                        KEY_QNS_ROVEIN_THRESHOLD_GAP_WITH_GUARD_TIMER_STRING_ARRAY);
        mApnTypesInternationalRoamingCheck =
                getConfig(
                        bundleCarrier,
                        bundleAsset,
                        KEY_APN_TYPES_WITH_INTERNATIONAL_ROAMING_CONDITION_STRING_ARRAY);
        mPlmnsRegardedAsInternationalRoaming =
                getConfig(
                        bundleCarrier,
                        bundleAsset,
                        KEY_PLMN_LIST_REGARDED_AS_INTERNATIONAL_ROAMING_STRING_ARRAY);
        mPlmnsRegardedAsDomesticRoaming =
                getConfig(
                        bundleCarrier,
                        bundleAsset,
                        KEY_PLMN_LIST_REGARDED_AS_DOMESTIC_ROAMING_STRING_ARRAY);

        loadFallbackPolicyWithImsRegiFail(bundleCarrier, bundleAsset);
    }

    @VisibleForTesting
    void loadFallbackPolicyWithImsRegiFail(PersistableBundle carrier, PersistableBundle asset) {
        synchronized (this) {
            mFallbackWwanRuleWithImsUnregistered.clear();
            String[] fallbackRulesStrings =
                    getConfig(
                            carrier,
                            asset,
                            KEY_QNS_FALLBACK_WWAN_IMS_UNREGISTRATION_REASON_STRING_ARRAY);
            if (fallbackRulesStrings != null) {
                Log.d(mLogTag, "loadFallbackPolicyWithImsRegiFail" + fallbackRulesStrings.length);
                for (String ruleString : fallbackRulesStrings) {
                    Log.d(mLogTag, " ruleString1:" + ruleString);
                    FallbackRule rule = parseFallbackRule(ruleString);
                    if (rule != null) {
                        mFallbackWwanRuleWithImsUnregistered.add(rule);
                    }
                }
            } else {
                Log.d(mLogTag, "Config FallbackWwanRuleWithImsUnregistered is null");
            }
            mFallbackWwanRuleWithImsHoRegisterFail.clear();
            fallbackRulesStrings =
                    getConfig(
                            carrier,
                            asset,
                            KEY_QNS_FALLBACK_WWAN_IMS_HO_REGISTER_FAIL_REASON_STRING_ARRAY);
            if (fallbackRulesStrings != null) {
                Log.d(mLogTag, "loadFallbackPolicyWithImsRegiFail2:" + fallbackRulesStrings.length);
                for (String ruleString : fallbackRulesStrings) {
                    Log.d(mLogTag, " ruleString2:" + ruleString);
                    FallbackRule rule = parseFallbackRule(ruleString);
                    if (rule != null) {
                        mFallbackWwanRuleWithImsHoRegisterFail.add(rule);
                    }
                }
            } else {
                Log.d(mLogTag, "Config mFallbackWwanRuleWithImsHoRegisterFail is null");
            }
        }
    }

    private FallbackRule parseFallbackRule(String ruleString) {
        if (TextUtils.isEmpty(ruleString)) {
            throw new IllegalArgumentException("illegal rule " + ruleString);
        }
        Set<Integer> reasons = new ArraySet<>();
        int time = 0;
        int preferenceMode = -1;
        ruleString = ruleString.trim().toLowerCase(Locale.ROOT);
        String[] expressions = ruleString.split("\\s*,\\s*");
        for (String expression : expressions) {
            String[] tokens = expression.trim().split("\\s*=\\s*");
            if (tokens.length != 2) {
                throw new IllegalArgumentException(
                        "illegal rule " + ruleString + ", tokens=" + Arrays.toString(tokens));
            }
            String key = tokens[0].trim();
            String value = tokens[1].trim();

            try {
                switch (key) {
                    case "cause":
                        String[] cause = value.trim().split("\\s*\\|\\s*");
                        for (String c : cause) {
                            if (c.indexOf("~") == -1) {
                                reasons.add(Integer.parseInt(c));
                            } else {
                                String[] tok = c.trim().split("\\s*~\\s*");
                                if (tokens.length != 2) {
                                    throw new IllegalArgumentException(
                                            "illegal cause range "
                                                    + ruleString
                                                    + ", tokens="
                                                    + Arrays.toString(tok));
                                }
                                int start = Integer.valueOf(tok[0]);
                                int end = Integer.valueOf(tok[1]);
                                for (int i = start; i <= end; i++) {
                                    reasons.add(i);
                                }
                            }
                        }
                        break;
                    case "time":
                        time = Integer.valueOf(value);
                        break;
                    case "preference":
                        if (value.equals("cell")) {
                            preferenceMode = QnsConstants.CELL_PREF;
                        } else if (value.equals("wifi")) {
                            preferenceMode = QnsConstants.WIFI_PREF;
                        }
                        break;

                    default:
                        throw new IllegalArgumentException("unexpected key " + key);
                }

            } catch (Exception e) {
                e.printStackTrace();
                throw new IllegalArgumentException("illegal rule \"" + ruleString + "\", e=" + e);
            }
        }
        if (reasons.size() > 0) {
            return new FallbackRule(reasons, time, preferenceMode);
        } else {
            return null;
        }
    }

    private synchronized <T> T getConfig(
            PersistableBundle bundleCarrier, PersistableBundle bundleAsset, String configKey) {
        return QnsUtils.getConfig(bundleCarrier, bundleAsset, configKey);
    }

    /** Load handover rules from carrier config. */
    @VisibleForTesting
    void loadHandoverRules(
            PersistableBundle bundleCarrier, PersistableBundle bundleAsset, String key) {
        synchronized (this) {
            mHandoverRuleList.clear();
            String[] handoverRulesStrings = getConfig(bundleCarrier, bundleAsset, key);
            if (handoverRulesStrings != null) {
                for (String ruleString : handoverRulesStrings) {
                    Log.d(mLogTag, "loadHandoverRules: " + ruleString);
                    try {
                        mHandoverRuleList.add(new HandoverRule(ruleString));
                    } catch (IllegalArgumentException e) {
                        Log.d(mLogTag, "loadHandoverRules: " + e.getMessage());
                    }
                }
            }
        }
    }

    /** Updated handover rules from carrier config. */
    @VisibleForTesting
    List<HandoverRule> updateHandoverRules(
            PersistableBundle bundleCarrier, PersistableBundle bundleAsset, String key) {
        List<HandoverRule> readNewHandoverRuleList = new ArrayList<>();
        synchronized (this) {
            readNewHandoverRuleList.clear();
            String[] handoverRulesStrings = getConfig(bundleCarrier, bundleAsset, key);
            if (handoverRulesStrings != null) {
                for (String ruleString : handoverRulesStrings) {
                    Log.d(mLogTag, "UpdateHandoverRules: " + ruleString);
                    try {
                        Log.d(mLogTag, "Rule Updated");
                        readNewHandoverRuleList.add(new HandoverRule(ruleString));
                    } catch (IllegalArgumentException e) {
                        Log.d(mLogTag, "UpdateHandoverRules: " + e.getMessage());
                    }
                }
            }
        }
        return readNewHandoverRuleList;
    }

    /** Load carrier config. */
    @VisibleForTesting
    void loadCarrierConfig(PersistableBundle bundleCarrier, PersistableBundle bundleAsset) {
        mIsMmtelCapabilityRequired =
                getConfig(
                        bundleCarrier,
                        null,
                        CarrierConfigManager.Ims.KEY_IMS_PDN_ENABLED_IN_NO_VOPS_SUPPORT_INT_ARRAY);
        mIsVolteRoamingSupported =
                getConfig(
                        bundleCarrier,
                        null,
                        CarrierConfigManager.ImsVoice.KEY_CARRIER_VOLTE_ROAMING_AVAILABLE_BOOL);
    }

    /**
     * To read the current Carrier ID based on the Slot ID and Context info
     *
     * @return : Current Carrier ID
     */
    int getCarrierId() {
        TelephonyManager tm = mContext.getSystemService(TelephonyManager.class);
        if (tm != null) {
            tm = tm.createForSubscriptionId(QnsUtils.getSubId(mContext, mSlotIndex));
            return tm.getSimCarrierId();
        }
        return 0;
    }

    /**
     * To read the current Subscription ID based on the Slot ID and Context info Output : Current
     * Carrier ID
     */
    int getSubId() {
        mSubId = QnsUtils.getSubId(mContext, mSlotIndex);
        return mSubId;
    }

    /** Notify all the registrants of the Slot loaded after carrier config loading is Completed */
    protected void notifyLoadQnsConfigurationsCompleted() {

        if (mQnsCarrierConfigLoadedRegistrants != null) {
            mQnsCarrierConfigLoadedRegistrants.notifyRegistrants();
        } else {
            Log.d(mLogTag, "notifyLoadQNSConfigurationsCompleted. no Registrant.");
        }
    }

    /** Notify all the registrants of the Slot loaded after carrier config loading is Completed */
    protected void notifyQnsConfigurationsChanged() {

        if (mQnsCarrierConfigChangedRegistrants != null) {
            mQnsCarrierConfigChangedRegistrants.notifyRegistrants();
        } else {
            Log.d(mLogTag, "notifyQnsConfigurationschanged. no Registrant.");
        }
    }

    /**
     * API exposed for other classes to register for notification with handlers on Carrier
     * Configuration Loaded
     *
     * @param h Handler to receive event
     * @param what Event on which to be handled
     */
    void registerForConfigurationLoaded(Handler h, int what) {
        mQnsCarrierConfigLoadedRegistrants.addUnique(h, what, null);
    }

    /**
     * API exposed for other classes to register for notification with handlers on Carrier
     * Configuration changed
     *
     * @param h Handler to receive event
     * @param what Event on which to be handled
     */
    void registerForConfigurationChanged(Handler h, int what) {
        mQnsCarrierConfigChangedRegistrants.addUnique(h, what, null);
    }

    /**
     * API exposed for other classes to unregister for notification of QNS Configuration loaded with
     * handlers
     *
     * @param h Handler to Unregister receiving event Output : Void
     */
    void unregisterForConfigurationLoaded(Handler h) {
        mQnsCarrierConfigLoadedRegistrants.remove(h);
    }

    /**
     * API exposed for other classes to unregister for notification of QNS Configuration changed
     * with handlers
     *
     * @param h Handler to Unregister receiving event Output : Void
     */
    void unregisterForConfigurationChanged(Handler h) {
        mQnsCarrierConfigChangedRegistrants.remove(h);
    }

    /**
     * This method returns if WFC is supported in Airplane Mode On
     *
     * @return : boolean (True/False)
     */
    boolean allowWFCOnAirplaneModeOn() {

        return mIsWfcInAirplaneModeOnSupport;
    }

    /**
     * This method returns if in-call handover decision from WLAN to WWAN should not consider VoPS
     * status.
     *
     * @return True if in-call handover decision from WLAN to WWAN should not consider VoPS status,
     * otherwise false.
     */
    boolean isInCallHoDecisionWlanToWwanWithoutVopsCondition() {
        return mIsInCallHoDecisionWlanToWwanWithoutVopsConditionSupported;
    }

    /**
     * This method returns VOPS/VONR bit is required for WWAN availability.
     *
     * @return : boolean (True/False)
     */
    boolean isMmtelCapabilityRequired(int coverage) {
        if (mIsMmtelCapabilityRequired == null || mIsMmtelCapabilityRequired.length == 0) {
            return true;
        }
        for (int i : mIsMmtelCapabilityRequired) {
            if ((i == CarrierConfigManager.Ims.NETWORK_TYPE_HOME
                            && coverage == QnsConstants.COVERAGE_HOME)
                    || (i == CarrierConfigManager.Ims.NETWORK_TYPE_ROAMING
                            && coverage == QnsConstants.COVERAGE_ROAM)) {
                return false;
            }
        }
        return true;
    }

    /**
     * This method returns if VoLTE roaming is supported by a carrier.
     *
     * @return True if VoLTE roaming is supported or UE is in home network, otherwise false.
     */
    boolean isVolteRoamingSupported(@QnsConstants.CellularCoverage int coverage) {
        if (coverage == QnsConstants.COVERAGE_ROAM) {
            return mIsVolteRoamingSupported;
        }
        return true;
    }

    /**
     * This method returns Video call over WFC with wfc off & LTE preconditions met
     *
     * @return : boolean (True/False)
     */
    boolean allowVideoOverIWLANWithCellularLimitedCase() {
        return mIsVideoOverIWLANWithCellularCheckSupport;
    }

    /**
     * This method returns if handover is allowed by policy
     *
     * @return True if handover is allowed by policy, otherwise false.
     */
    boolean isHandoverAllowedByPolicy(
            int netCapability, int srcAn, int destAn, @QnsConstants.CellularCoverage int coverage) {
        Log.d(
                mLogTag,
                "isHandoverAllowedByPolicy netCapability: "
                        + QnsUtils.getNameOfNetCapability(netCapability)
                        + " srcAccessNetwork:"
                        + QnsConstants.accessNetworkTypeToString(srcAn)
                        + " destAccessNetwork:"
                        + QnsConstants.accessNetworkTypeToString(destAn)
                        + "  "
                        + QnsConstants.coverageToString(coverage));
        // check Telephony handover policy.
        // Matching the rules by the configured order. Bail out if find first matching rule.
        for (HandoverRule rule : mHandoverRuleList) {
            if (rule.mIsOnlyForRoaming && coverage != QnsConstants.COVERAGE_ROAM) continue;

            if (rule.mSourceAccessNetworks.contains(srcAn)
                    && rule.mTargetAccessNetworks.contains(destAn)) {
                // if no capability rule specified, data network capability is considered matched.
                // otherwise, any capabilities overlap is also considered matched.
                if (rule.mNetworkcapabilities.isEmpty()
                        || rule.mNetworkcapabilities.contains(netCapability)) {
                    if (rule.mHandoverRuleType == HandoverRule.RULE_TYPE_DISALLOWED) {
                        Log.d(mLogTag, "isHandoverAllowedByPolicy:Not allowed by policy " + rule);
                        return false;
                    } else {
                        Log.d(mLogTag, "isHandoverAllowedByPolicy: allowed by policy " + rule);
                        return true;
                    }
                }
            }
        }

        Log.d(mLogTag, "isHandoverAllowedByPolicy: Did not find matching rule. ");
        // Disallow handover for non-IMS network capability anyway if no rule is found.
        if (netCapability != NetworkCapabilities.NET_CAPABILITY_IMS) return false;

        // Allow handover for IMS network capability anyway if no rule is found.
        return true;
    }

    /**
     * This method returns if Service Barring Check for HO decision is Supported
     *
     * @return : boolean (True/False)
     */
    boolean isServiceBarringCheckSupported() {

        return mIsServiceBarringCheckSupport;
    }

    /**
     * This method returns if the Guard timer (Ping Pong) hysteresis is preference specific
     *
     * @return : Based on Carrier Config Settings based on operator requirement possible values:
     *     True / False
     */
    boolean isGuardTimerHysteresisOnPrefSupported() {

        return mIsHoGuardOnPreferenceSupport;
    }

    /**
     * This method returns if the network(HOME or ROAM) requires handover guard timer.
     *
     * @return : Based on Carrier Config Settings based on operator requirement possible values:
     *     True / False
     */
    boolean isHysteresisTimerEnabled(int coverage) {
        if (mNetworkEnableHysteresisTimer == QnsConstants.COVERAGE_BOTH
                || mNetworkEnableHysteresisTimer == coverage) {
            return true;
        }
        return false;
    }

    /**
     * Get carrier config for the KEY_ROAM_TRANSPORT_TYPE_SELECTION_WITHOUT_SIGNAL_STRENGTH_BOOL if
     * true, It ignores all thresholds needed to only refer to availability.
     *
     * @return true for key value is true. False for otherwise.
     */
    boolean isTransportTypeSelWithoutSSInRoamSupported() {

        return mAnspSupportConfigArray[0];
    }

    /*
     * get carrierconfig for KEY_PREFER_CURRENT_TRANSPORT_TYPE_IN_VOICE_CALL
     * true: Prefer current transport type during voice call.
     *
     * @return true for key value is true. False for otherwise.
     */
    boolean isCurrentTransportTypeInVoiceCallSupported() {

        return mAnspSupportConfigArray[1];
    }

    /**
     * Get carrierconfig for KEY_POLICY_OVERRIDE_CELL_PREF_TO_IMS_PREF_HOME_BOOL
     * true: Use IMS Preferred when WFC Mode is Cellular Preferred at Home Network.
     *
     * @return true for key value is true. False for otherwise.
     */
    boolean isOverrideImsPreferenceSupported() {
        return mAnspSupportConfigArray[2];
    }

    /**
     * The method is to return if choose WFC prferred transport in both WWAN and WLAN are bad
     * conditions. It is controlled by
     * KEY_CHOOSE_WFC_PREFERRED_TRANSPORT_IN_BOTH_BAD_CONDITION_INT_ARRAY.
     *
     * @return : boolean (True/False)
     */
    boolean isChooseWfcPreferredTransportInBothBadCondition(int wfcMode) {
        if (mIsWfcPreferredTransportRequired == null
                || mIsWfcPreferredTransportRequired.length == 0) {
            return false;
        }
        for (int i : mIsWfcPreferredTransportRequired) {
            if (wfcMode == i) {
                return true;
            }
        }
        return false;
    }

    /**
     * This method returns whether the rove out(to Cellular) policy includes a Wi-Fi bad condition
     * at handover guarding time.
     *
     * @return : Based on Carrier Config Settings based on operator requirement possible values:
     *     True / False
     */
    boolean isRoveOutWithWiFiLowQualityAtGuardingTime() {

        return mIsRoveOutWifiBadGuardTimerConditionsSupported;
    }

    /**
     * This method returns the waiting time for the preferred transport type at power up.
     *
     * @return : A timer in millisecond
     */
    int getWaitingTimerForPreferredTransportOnPowerOn(int transportType) {
        switch (transportType) {
            case TRANSPORT_TYPE_WWAN:
                return mWaitingTimerForPreferredTransport[0];
            case TRANSPORT_TYPE_WLAN:
                return mWaitingTimerForPreferredTransport[1];
            default:
                Log.d(mLogTag, "Invalid transport type, return the default timer.");
                return QnsConstants.KEY_DEFAULT_VALUE;
        }
    }

    /**
     * This method returns the Transport type Preference on Power On.
     *
     * @return : Based on Carrier Config Settings Possible values (3000msec:Default or operator
     *     customisation.
     */
    int getWIFIRssiBackHaulTimer() {
        return mWifiThresBackHaulTimer;
    }

    /**
     * This method returns Cellular SS Backhaul Timer.
     *
     * @return : Based on Carrier Config Settings based on operator requirement possible values ( 0
     *     : Invalid or 320ms)
     */
    int getCellularSSBackHaulTimer() {

        return mCellularThresBackHaulTimer;
    }

    /**
     * This method returns IWLAN HO Avoid time due to Low RTP Quality Backhaul Timer.
     *
     * @return : Based on Carrier Config Settings based on operator requirement possible values ( 0
     *     : or operator requirement)
     */
    int getHoRestrictedTimeOnLowRTPQuality(
            @AccessNetworkConstants.RadioAccessNetworkType int accessNetwork) {

        if (accessNetwork == TRANSPORT_TYPE_WLAN) {
            return mHoRestrictTimeOnRtpQuality[0];
        } else if (accessNetwork == TRANSPORT_TYPE_WWAN) {
            return mHoRestrictTimeOnRtpQuality[1];
        } else {
            return QnsConstants.KEY_DEFAULT_VALUE;
        }
    }

    /**
     * This method returns QNS preferred transport type for network capabilities / Services
     *
     * @return : Based on Carrier Config Settings based on operator requirement possible values:
     *     TRANSPORT_TYPE_ALLOWED_WWAN = 0 TRANSPORT_TYPE_ALLOWED_IWLAN = 1
     *     TRANSPORT_TYPE_ALLOWED_BOTH = 2
     */
    int getQnsSupportedTransportType(int netCapability) {
        if (netCapability == NetworkCapabilities.NET_CAPABILITY_IMS) {
            return mQnsImsTransportType;
        } else if (netCapability == NetworkCapabilities.NET_CAPABILITY_EIMS) {
            return mQnsSosTransportType;
        } else if (netCapability == NetworkCapabilities.NET_CAPABILITY_MMS) {
            return mQnsMmsTransportType;
        } else if (netCapability == NetworkCapabilities.NET_CAPABILITY_XCAP) {
            return mQnsXcapTransportType;
        } else if (netCapability == NetworkCapabilities.NET_CAPABILITY_CBS) {
            return mQnsCbsTransportType;
        }
        return QnsConstants.INVALID_ID;
    }

    /**
     * This method returns the hysteresis timer when handover from WLAN to WWAN.
     *
     * @return : the hysteresis timer
     */
    int getWwanHysteresisTimer(int netCapability, @QnsConstants.QnsCallType int callType) {
        if (mQnsProvisioningInfo.hasItem(ProvisioningManager.KEY_LTE_EPDG_TIMER_SEC)) {
            return mQnsProvisioningInfo.getIntegerItem(ProvisioningManager.KEY_LTE_EPDG_TIMER_SEC);
        }
        switch (netCapability) {
            case NetworkCapabilities.NET_CAPABILITY_IMS:
            case NetworkCapabilities.NET_CAPABILITY_EIMS:
                if (callType == QnsConstants.CALL_TYPE_IDLE) {
                    return mWwanHysteresisTimer[0];
                } else if (callType == QnsConstants.CALL_TYPE_VOICE) {
                    return mWwanHysteresisTimer[1];
                } else if (callType == QnsConstants.CALL_TYPE_VIDEO) {
                    return mWwanHysteresisTimer[2];
                } else {
                    return QnsConstants.KEY_DEFAULT_VALUE;
                }
            case NetworkCapabilities.NET_CAPABILITY_MMS:
            case NetworkCapabilities.NET_CAPABILITY_XCAP:
            case NetworkCapabilities.NET_CAPABILITY_CBS:
                if (callType == QnsConstants.CALL_TYPE_IDLE) {
                    return mNonImsWwanHysteresisTimer[0];
                } else {
                    return mNonImsWwanHysteresisTimer[1];
                }
            default:
                return QnsConstants.KEY_DEFAULT_VALUE;
        }
    }

    /**
     * This method returns the hysteresis timer when handover from WWAN to WLAN.
     *
     * @return : the hysteresis timer
     */
    int getWlanHysteresisTimer(int netCapability, @QnsConstants.QnsCallType int callType) {
        if (mQnsProvisioningInfo.hasItem(ProvisioningManager.KEY_WIFI_EPDG_TIMER_SEC)) {
            return mQnsProvisioningInfo.getIntegerItem(ProvisioningManager.KEY_WIFI_EPDG_TIMER_SEC);
        }
        switch (netCapability) {
            case NetworkCapabilities.NET_CAPABILITY_IMS:
            case NetworkCapabilities.NET_CAPABILITY_EIMS:
                if (callType == QnsConstants.CALL_TYPE_IDLE) {
                    return mWlanHysteresisTimer[0];
                } else if (callType == QnsConstants.CALL_TYPE_VOICE) {
                    return mWlanHysteresisTimer[1];
                } else if (callType == QnsConstants.CALL_TYPE_VIDEO) {
                    return mWlanHysteresisTimer[2];
                } else {
                    return QnsConstants.KEY_DEFAULT_VALUE;
                }
            case NetworkCapabilities.NET_CAPABILITY_MMS:
            case NetworkCapabilities.NET_CAPABILITY_XCAP:
            case NetworkCapabilities.NET_CAPABILITY_CBS:
                if (callType == QnsConstants.CALL_TYPE_IDLE) {
                    return mNonImsWlanHysteresisTimer[0];
                } else {
                    return mNonImsWlanHysteresisTimer[1];
                }
            default:
                return QnsConstants.KEY_DEFAULT_VALUE;
        }
    }

    /**
     * This method returns the timer millis for the minimum guarding timer.
     *
     * @return the minimum guarding timer in millis. applies when handover guarding is disabled or
     *     there is no guarding time.
     */
    int getMinimumHandoverGuardingTimer() {
        int timer = mMinimumHandoverGuardingTimer;
        if (timer < QnsConstants.CONFIG_DEFAULT_MIN_HANDOVER_GUARDING_TIMER) {
            timer = QnsConstants.CONFIG_DEFAULT_MIN_HANDOVER_GUARDING_TIMER;
        }
        if (timer > QnsConstants.CONFIG_DEFAULT_MIN_HANDOVER_GUARDING_TIMER_LIMIT) {
            timer = QnsConstants.CONFIG_DEFAULT_MIN_HANDOVER_GUARDING_TIMER_LIMIT;
        }
        return timer;
    }

    /**
     * This method returns the Threshold gap offset based on which threshold to be registered during
     * Guard timer Running / Expired conditions from Evaluator
     *
     * @return : Based on Carrier Config Settings & operator requirement Default Value : 0 gap
     *     offset (Means different threshold for Guard timer conditions not enabled)
     */
    int getThresholdGapWithGuardTimer(
            @AccessNetworkConstants.RadioAccessNetworkType int accessNetwork, int measType) {

        if (mRoveInGuardTimerConditionThresholdGaps == null) {
            return QnsConstants.KEY_DEFAULT_VALUE;
        }
        if (!mRoveInGuardTimerConditionThresholdGaps[0].isEmpty()) {
            for (String check_offset : mRoveInGuardTimerConditionThresholdGaps) {
                String[] gap = check_offset.split(":");
                String access_network = sAccessNetworkMap.get(accessNetwork);
                String measurement_Type = sMeasTypeMap.get(measType);

                try {
                    if (gap[0].equalsIgnoreCase(access_network)
                            && gap[1].equalsIgnoreCase(measurement_Type)) {
                        return Integer.parseInt(gap[2]);
                    }
                } catch (Exception e) {
                }
            }
        }
        return QnsConstants.KEY_DEFAULT_VALUE;
    }

    boolean hasThresholdGapWithGuardTimer() {
        if (mRoveInGuardTimerConditionThresholdGaps == null) {
            return false;
        }
        return true;
    }

    /**
     * This method returns Access Network Selection Policy based on network capability
     *
     * @param targetTransportType : WWAN/WLAN
     * @return : Target transport mapped to string
     */
    static String transportNetworkToString(int targetTransportType) {
        switch (targetTransportType) {
            case TRANSPORT_TYPE_WWAN:
                return "CELLULAR";
            case TRANSPORT_TYPE_WLAN:
                return "WIFI";
        }
        return "";
    }

    /**
     * Finds and returns a threshold config that meets the given parameter condition.
     *
     * @param accessNetwork (EUTRAN/UTRAN/NGRAN/GERAN/IWLAN)
     * @param callType (IDLE/VOICE/VIDEO)
     * @param measurementType (RSRP/RSRQ/RSSNR/SSRSP/SSRSQ/RSCP/RSSI)
     * @return QnsConfigArray for good, bad and worst thresholds. If the value does not exist or is
     *     not supported, it is filled with invalid (0x0000FFFF). Note, for the wifi case, the worst
     *     in thresholds will be invalid.
     * @return IVALID VALUE(to do description, if not found item or exceptions)
     */
    QnsConfigArray getThreshold(
            @AccessNetworkConstants.RadioAccessNetworkType int accessNetwork,
            int callType,
            int measurementType) {
        int[] thresholdList = new int[] {0x0000FFFF, 0x0000FFFF, 0x0000FFFF};
        if (accessNetwork == AccessNetworkConstants.AccessNetworkType.UNKNOWN
                || measurementType == SIGNAL_MEASUREMENT_TYPE_UNKNOWN) {
            return null;
        }

        String access_network = sAccessNetworkMap.get(accessNetwork);
        String measurement_Type = sMeasTypeMap.get(measurementType);
        String call_Type = sCallTypeMap.get(callType);

        if (access_network != null && measurement_Type != null && call_Type != null) {
            String key =
                    "qns."
                            + call_Type
                            + "_"
                            + access_network
                            + "_"
                            + measurement_Type
                            + "_"
                            + "int_array";

            thresholdList = mAnspConfigMgr.getAnspCarrierThreshold(key);

            if (thresholdList != null && thresholdList.length > 1) {
                if (AccessNetworkConstants.AccessNetworkType.IWLAN == accessNetwork
                        || thresholdList.length == 2) {
                    return new QnsConfigArray(thresholdList[0], thresholdList[1]);
                } else {
                    return new QnsConfigArray(thresholdList[0], thresholdList[1], thresholdList[2]);
                }
            } else {
                thresholdList = new int[] {0x0000FFFF, 0x0000FFFF, 0x0000FFFF};
            }
        }
        return new QnsConfigArray(thresholdList[0], thresholdList[1], thresholdList[2]);
    }

    QnsConfigArray getThresholdByPref(
            int accessNetwork, int callType, int measurementType, int preference) {
        if (accessNetwork == AccessNetworkConstants.AccessNetworkType.UNKNOWN
                || measurementType == SIGNAL_MEASUREMENT_TYPE_UNKNOWN) {
            return null;
        }

        int[] thresholdList = null;
        String access_network = sAccessNetworkMap.get(accessNetwork);
        String measurement_Type = sMeasTypeMap.get(measurementType);
        String call_Type = sCallTypeMap.get(callType);

        if (access_network == null || measurement_Type == null || call_Type == null) {
            return new QnsConfigArray(0x0000FFFF, 0x0000FFFF, 0x0000FFFF);
        }

        if (accessNetwork == AccessNetworkConstants.AccessNetworkType.IWLAN
                && preference == QnsConstants.WIFI_PREF) {
            String overrideKey =
                    "qns.override_wifi_pref_"
                            + call_Type
                            + "_"
                            + access_network
                            + "_"
                            + measurement_Type
                            + "_int_array";
            thresholdList = mAnspConfigMgr.getAnspCarrierThreshold(overrideKey);
        }

        if (thresholdList == null || thresholdList.length < 2) {
            String key =
                    "qns."
                            + call_Type
                            + "_"
                            + access_network
                            + "_"
                            + measurement_Type
                            + "_int_array";
            thresholdList = mAnspConfigMgr.getAnspCarrierThreshold(key);
        }
        if (thresholdList == null || thresholdList.length < 2) {
            return new QnsConfigArray(0x0000FFFF, 0x0000FFFF, 0x0000FFFF);
        }

        if (AccessNetworkConstants.AccessNetworkType.IWLAN == accessNetwork
                || thresholdList.length == 2) {
            QnsConfigArray qnsConfigArray = new QnsConfigArray(thresholdList[0], thresholdList[1]);
            return applyProvisioningInfo(qnsConfigArray, accessNetwork, measurementType, callType);
        }

        QnsConfigArray qnsConfigArray =
                new QnsConfigArray(thresholdList[0], thresholdList[1], thresholdList[2]);
        return applyProvisioningInfo(qnsConfigArray, accessNetwork, measurementType, callType);
    }

    QnsConfigArray getWifiRssiThresholdWithoutCellular(int callType) {
        int[] thresholdList = null;
        String call_Type = sCallTypeMap.get(callType);

        if (call_Type == null) {
            return new QnsConfigArray(0x0000FFFF, 0x0000FFFF, 0x0000FFFF);
        }

        String key = "qns." + call_Type + "_wifi_rssi_without_cellular_int_array";
        thresholdList = mAnspConfigMgr.getAnspCarrierThreshold(key);

        if (thresholdList == null || thresholdList.length < 2) {
            return new QnsConfigArray(0x0000FFFF, 0x0000FFFF, 0x0000FFFF);
        }

        return new QnsConfigArray(thresholdList[0], thresholdList[1]);
    }

    /**
     * Finds and returns a policy config that meets the given parameter condition.
     *
     * @param direction (ROVE_IN / ROVE_OUT)
     * @param preCondition (Types of CALL, PREFERENCE, COVERAGE and so on)
     * @return QnsConfigArray for good, bad and worst policy. If the value does not exist or is not
     *     supported, it is filled with invalid. (0x0000FFFF). Note, for the wifi case, the worst in
     *     thresholds will be invalid.
     * @return null (to do description, if not found item or exceptions)
     */
    String[] getPolicy(
            @QnsConstants.RoveDirection int direction,
            AccessNetworkSelectionPolicy.PreCondition preCondition) {

        String key =
                "qns.condition_"
                        + QnsConstants.directionToString(direction).toLowerCase()
                        + "_"
                        + QnsConstants.callTypeToString(preCondition.getCallType()).toLowerCase()
                        + "_"
                        + QnsConstants.preferenceToString(preCondition.getPreference())
                                .toLowerCase()
                        + "_"
                        + QnsConstants.coverageToString(preCondition.getCoverage()).toLowerCase()
                        + "_";

        if (preCondition instanceof AccessNetworkSelectionPolicy.GuardingPreCondition) {
            AccessNetworkSelectionPolicy.GuardingPreCondition guardingCondition =
                    (AccessNetworkSelectionPolicy.GuardingPreCondition) preCondition;
            String guardingKey =
                    key
                            + QnsConstants.guardingToString(guardingCondition.getGuarding())
                                    .toLowerCase()
                            + "_string_array";
            String[] guardingPolicy = mAnspConfigMgr.getAnspCarrierPolicy(guardingKey);
            if (guardingPolicy != null) {
                return guardingPolicy;
            }
        }
        key = key + "string_array";
        return mAnspConfigMgr.getAnspCarrierPolicy(key);
    }

    /**
     * This method returns RTP Metrics data of Carrier for HO decision making
     *
     * @return : config of RTP metrics. refer {@link RtpMetricsConfig}
     */
    @VisibleForTesting
    RtpMetricsConfig getRTPMetricsData() {

        return new RtpMetricsConfig(
                mRTPMetricsData[0], mRTPMetricsData[1], mRTPMetricsData[2], mRTPMetricsData[3]);
    }

    /**
     * This retrieves fallback timer to WWAN with the reason of IMS unregistered.
     *
     * @param reason
     * @param preferMode
     * @return fallback time in millis.
     */
    @VisibleForTesting
    int getFallbackTimeImsUnreigstered(int reason, int preferMode) {
        Log.d(
                mLogTag,
                "getFallbackTimeImsUnreigstered reason:" + reason + " prefMode:" + preferMode);
        for (FallbackRule rule : mFallbackWwanRuleWithImsUnregistered) {
            Log.d(mLogTag, rule.toString());
            if (preferMode != QnsConstants.WIFI_ONLY
                    && (rule.mPreferenceMode == -1 || rule.mPreferenceMode == preferMode)) {
                int time = rule.getFallBackTime(reason);
                if (time > 0) {
                    Log.d(mLogTag, "getFallbackTimeImsUnreigstered fallbackTime:" + time);
                    return time;
                }
            }
        }
        Log.d(mLogTag, "getFallbackTimeImsUnreigstered fallbackTime:" + 0);
        return 0;
    }

    /**
     * This retrieves fallback timer to WWAN with the reason of IMS HO register fail.
     *
     * @param reason
     * @param preferMode
     * @return fallback time in millis.
     */
    @VisibleForTesting
    int getFallbackTimeImsHoRegisterFailed(int reason, int preferMode) {
        Log.d(
                mLogTag,
                "getFallbackTimeImsHoRegisterFailed reason:" + reason + " prefMode:" + preferMode);
        for (FallbackRule rule : mFallbackWwanRuleWithImsHoRegisterFail) {
            if (preferMode != QnsConstants.WIFI_ONLY
                    && (rule.mPreferenceMode == -1 || rule.mPreferenceMode == preferMode)) {
                Log.d(mLogTag, rule.toString());
                int time = rule.getFallBackTime(reason);
                Log.d(mLogTag, "getFallbackTimeImsHoRegisterFailed fallback time: " + time);
                if (time > 0) return time;
            }
        }
        Log.d(mLogTag, "getFallbackTimeImsHoRegisterFailed fallback time: " + 0);
        return 0;
    }

    /**
     * This method returns Access Network Selection Policy Support configurations with boolean array
     * list type
     *
     * @return: Boolean array as a list of Boolean Type
     */
    void loadAnspCarrierSupportConfigs(
            PersistableBundle bundleCarrier, PersistableBundle bundleAsset) {
        int i = 0;
        String[] anspConfigs = {
            KEY_ROAM_TRANSPORT_TYPE_SELECTION_WITHOUT_SIGNAL_STRENGTH_BOOL,
            KEY_PREFER_CURRENT_TRANSPORT_TYPE_IN_VOICE_CALL_BOOL,
            KEY_POLICY_OVERRIDE_CELL_PREF_TO_IMS_PREF_HOME_BOOL
        };

        for (String key : anspConfigs) {
            mAnspSupportConfigArray[i] = getConfig(bundleCarrier, bundleAsset, key);
            i += 1;
        }

        mIsWfcPreferredTransportRequired =
                getConfig(
                        bundleCarrier,
                        bundleAsset,
                        KEY_CHOOSE_WFC_PREFERRED_TRANSPORT_IN_BOTH_BAD_CONDITION_INT_ARRAY);
    }

    /**
     * This method gives the network capabilities supported based on
     * KEY_QNS_<NetworkCapability></NetworkCapability>_TRANSPORT_TYPE_INT
     *
     * @return : Supported network capabilities
     */
    List<Integer> getQnsSupportedNetCapabilities() {
        List<Integer> netCapabilities = new ArrayList<>();
        if (mQnsImsTransportType == QnsConstants.TRANSPORT_TYPE_ALLOWED_IWLAN
                || mQnsImsTransportType == QnsConstants.TRANSPORT_TYPE_ALLOWED_BOTH) {
            netCapabilities.add(NetworkCapabilities.NET_CAPABILITY_IMS);
        }
        if (mQnsSosTransportType == QnsConstants.TRANSPORT_TYPE_ALLOWED_IWLAN
                || mQnsSosTransportType == QnsConstants.TRANSPORT_TYPE_ALLOWED_BOTH) {
            netCapabilities.add(NetworkCapabilities.NET_CAPABILITY_EIMS);
        }
        if (mQnsMmsTransportType == QnsConstants.TRANSPORT_TYPE_ALLOWED_IWLAN
                || mQnsMmsTransportType == QnsConstants.TRANSPORT_TYPE_ALLOWED_BOTH) {
            netCapabilities.add(NetworkCapabilities.NET_CAPABILITY_MMS);
        }
        if (mQnsXcapTransportType == QnsConstants.TRANSPORT_TYPE_ALLOWED_IWLAN
                || mQnsXcapTransportType == QnsConstants.TRANSPORT_TYPE_ALLOWED_BOTH) {
            netCapabilities.add(NetworkCapabilities.NET_CAPABILITY_XCAP);
        }
        if (mQnsCbsTransportType == QnsConstants.TRANSPORT_TYPE_ALLOWED_IWLAN
                || mQnsCbsTransportType == QnsConstants.TRANSPORT_TYPE_ALLOWED_BOTH) {
            netCapabilities.add(NetworkCapabilities.NET_CAPABILITY_CBS);
        }
        return netCapabilities;
    }

    /**
     * This method returns Allowed cellular RAT for IMS
     *
     * @param accessNetwork , netCapability : EUTRAN / NGRAN / UTRAN/ GERAN
     * @return : True or False based on configuration
     */
    boolean isAccessNetworkAllowed(int accessNetwork, int netCapability) {

        // cases to be enhanced for different key items when added
        if (netCapability == NetworkCapabilities.NET_CAPABILITY_IMS) {
            if (mImsAllowedRats != null) {
                for (String cellularRatType : mImsAllowedRats) {
                    if ((cellularRatType.contains("LTE")
                                    && accessNetwork
                                            == AccessNetworkConstants.AccessNetworkType.EUTRAN)
                            || (cellularRatType.contains("NR")
                                    && accessNetwork
                                            == AccessNetworkConstants.AccessNetworkType.NGRAN)
                            || (cellularRatType.contains("3G")
                                    && accessNetwork
                                            == AccessNetworkConstants.AccessNetworkType.UTRAN)
                            || (cellularRatType.contains("2G")
                                    && accessNetwork
                                            == AccessNetworkConstants.AccessNetworkType.GERAN)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * This method returns max HO Back to IWLAN count value with Fallback reason to Rove Out
     *
     * @return : int array (Ex: -1,-1 or 1,2 or 3,1 etc... )
     */
    int getQnsMaxIwlanHoCountDuringCall() {

        if (mAllowMaxIwlanHoCountOnReason[0] <= 0) {
            mAllowMaxIwlanHoCountOnReason[0] = MAX_COUNT_INVALID;
        }

        return mAllowMaxIwlanHoCountOnReason[0];
    }

    /**
     * This method returns Supported Fallback reason to Rove Out from IWLAN
     *
     * @return : int array (Ex: -1,-1 or 1,2 or 3,1 etc... )
     */
    int getQnsIwlanHoRestrictReason() {
        if (mAllowMaxIwlanHoCountOnReason[1] <= 0) {
            mAllowMaxIwlanHoCountOnReason[1] = FALLBACK_REASON_INVALID;
        }
        return mAllowMaxIwlanHoCountOnReason[1];
    }

    /**
     * This method returns to allow enabled Wi-Fi calling based on exceptional cellular state, even
     * when Wi-Fi calling is disabled.
     *
     * <p>Enable Wi-Fi calling If the call state is idle and the cellular network the UE is staying
     * on does not allow ims pdn.
     *
     * @return : Based on Carrier Config Settings based on operator requirement possible values:
     *     True / False
     */
    boolean allowImsOverIwlanCellularLimitedCase() {
        return mIsAllowImsOverIwlanCellularLimitedCase;
    }

    /**
     * This method returns if Iwlan is not allowed when UE is in no WWAN coverage and the last
     * stored country code is outside the home country.
     *
     * @return True if need to block Iwlan, otherwise false.
     */
    boolean blockIwlanInInternationalRoamWithoutWwan() {
        return mIsBlockIwlanInInternationalRoamWithoutWwan;
    }

    /**
     * This method returns if IPv6 only WiFi is allowed
     *
     * @return True if need to block IPv6 only WiFi, otherwise false.
     */
    boolean blockIpv6OnlyWifi() {
        return mIsBlockIpv6OnlyWifi;
    }

    /**
     * This method returns whether the IMS Registration state option is added when reporting a
     * qualified Wi-Fi network for network capabilities other than ims.
     *
     * @return : Based on Carrier Config Settings based on operator requirement possible values:
     *     True / False
     */
    int getRatPreference(int netCapability) {
        switch (netCapability) {
            case NetworkCapabilities.NET_CAPABILITY_XCAP:
                return mXcapRatPreference;
            case NetworkCapabilities.NET_CAPABILITY_EIMS:
                return mSosRatPreference;
            case NetworkCapabilities.NET_CAPABILITY_MMS:
                return mMmsRatPreference;
            case NetworkCapabilities.NET_CAPABILITY_CBS:
                return mCbsRatPreference;
        }
        return QnsConstants.RAT_PREFERENCE_DEFAULT;
    }

    /**
     * This method returns whether the network capability check roaming condition with International
     * Roaming or not.
     *
     * @return : Based on Carrier Config Settings based on operator requirement possible values:
     *     True / False
     */
    boolean needToCheckInternationalRoaming(int netCapability) {
        if (mApnTypesInternationalRoamingCheck == null
                || mApnTypesInternationalRoamingCheck.length == 0) {
            return false;
        }

        return QnsUtils.getNetCapabilitiesFromApnTypesString(mApnTypesInternationalRoamingCheck)
                .stream()
                .mapToInt(netCapabilityFromApn -> netCapabilityFromApn)
                .anyMatch(netCapabilityFromApn -> netCapabilityFromApn == netCapability);
    }

    /**
     * This method returns whether input plmn needs to be regarded as international roaming or not.
     *
     * @return : Based on Carrier Config Settings based on operator requirement possible values:
     *     True / False
     */
    boolean isDefinedInternationalRoamingPlmn(String plmn) {
        if (mPlmnsRegardedAsInternationalRoaming != null
                && mPlmnsRegardedAsInternationalRoaming.length > 0) {
            for (String configuredPlmn : mPlmnsRegardedAsInternationalRoaming) {
                Log.d(
                        mLogTag,
                        "isDefinedInternationalRoamingPlmn"
                                + configuredPlmn
                                + " is match with "
                                + plmn);
                if (configuredPlmn.length() == 3 && plmn.startsWith(configuredPlmn)) {
                    return true;
                } else if (configuredPlmn.length() > 4 && configuredPlmn.length() <= 6) {
                    if ((Integer.valueOf(configuredPlmn.substring(0, 3))
                                    .equals(Integer.valueOf(plmn.substring(0, 3))))
                            && (Integer.valueOf(configuredPlmn.substring(3)))
                                    .equals(Integer.valueOf(plmn.substring(3)))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * This method returns the rtt check server address config as per operator requirement
     *
     * @return : Based on carrier config settings of operator . By default to be made empty to
     *     disable the feature.
     */
    String getWlanRttServerAddressConfig() {
        String[] ping_address = getWlanRttPingConfigs();

        if (ping_address != null && ping_address[0] != null && !ping_address[0].isEmpty()) {
            return ping_address[0];
        } else return null;
    }

    /**
     * This method returns No of Pings, Intra Ping Interval, Size of the packet, RTT criteria RTT
     * retry timer
     *
     * @return : Based on carrier config settings as per operator requirement
     */
    int[] getWlanRttOtherConfigs() {
        int[] pingConfigs = new int[5];
        String[] rtt_ping_config = getWlanRttPingConfigs();

        if (rtt_ping_config != null && !rtt_ping_config[0].isEmpty()) {
            for (int i = 1; i < 6; i++) {
                if (rtt_ping_config[i] != null) {
                    pingConfigs[i - 1] = Integer.parseInt(rtt_ping_config[i]);
                }
            }
        }
        return pingConfigs;
    }

    /**
     * This method returns fallback Hysteresis timer on RTT Failure.
     *
     * @return : Based on carrier config settings as per operator requirement
     */
    int getWlanRttFallbackHystTimer() {
        String[] rtt_hyst_fallback_timer = getWlanRttPingConfigs();

        if (rtt_hyst_fallback_timer != null
                && !rtt_hyst_fallback_timer[0].isEmpty()
                && rtt_hyst_fallback_timer[6] != null) {
            return Integer.parseInt(rtt_hyst_fallback_timer[6]);
        } else return 0;
    }

    private String[] getWlanRttPingConfigs() {
        if (mWlanRttBackhaulCheckConfigsOnPing == null) return null;

        return mWlanRttBackhaulCheckConfigsOnPing.split(",");
    }

    /**
     * If fallback for Initial connection failure for the network capability is met is supported ,
     * this method provides information about the failure retry count or retry timer or both if
     * supported until fallback to other transport.
     *
     * @param netCapability : (ims,sos,mms,xcap,cbs)
     * @return :
     *     <NetworkCapability_SupportforFallback>:<retry_count>:<retry_timer>:<max_fallback_count>
     */
    int[] getInitialDataConnectionFallbackConfig(int netCapability) {

        int[] fallbackConfigOnDataFail = new int[4];
        String[] fallback_config = getFallbackConfigForNetCapability(netCapability);

        if (fallback_config != null
                && fallback_config[0] != null
                && fallback_config[0].length() > 0) {
            // netCapability Availability Status
            fallbackConfigOnDataFail[0] = 1;

            // Retry Count :  && fallback_config[1].length() > 0
            if (fallback_config.length > 1
                    && fallback_config[1] != null
                    && !fallback_config[1].isEmpty()) {
                fallbackConfigOnDataFail[1] = Integer.parseInt(fallback_config[1]);
            }

            // Retry timer
            if (fallback_config.length > 2
                    && fallback_config[2] != null
                    && !fallback_config[2].isEmpty()) {
                fallbackConfigOnDataFail[2] = Integer.parseInt(fallback_config[2]);
            }

            // Max fallback count
            if (fallback_config.length > 4
                    && fallback_config[4] != null
                    && !fallback_config[4].isEmpty()) {
                fallbackConfigOnDataFail[3] = Integer.parseInt(fallback_config[4]);
            }
        }
        return fallbackConfigOnDataFail;
    }

    /**
     * This method returns the fall back timer to be starting the restriction , for no. of retries
     * when met with the pdn fail fallback causes
     *
     * @param netCapability : (ims,sos,mms,xcap,cbs)
     * @return : Fallback Guard timer to be set on starting the fallback restrict @ RestrictManager
     */
    int getFallbackGuardTimerOnInitialConnectionFail(int netCapability) {
        String[] fallback_guard_timer = getFallbackConfigForNetCapability(netCapability);

        if (fallback_guard_timer != null
                && fallback_guard_timer[0] != null
                && fallback_guard_timer[0].length() > 0
                && ((fallback_guard_timer.length > 1
                                && fallback_guard_timer[1] != null
                                && !fallback_guard_timer[1].isEmpty())
                        || (fallback_guard_timer.length > 2
                                && fallback_guard_timer[2] != null
                                && !fallback_guard_timer[2].isEmpty()))
                && (fallback_guard_timer.length > 3
                        && fallback_guard_timer[3] != null
                        && !fallback_guard_timer[3].isEmpty())) {
            return Integer.parseInt(fallback_guard_timer[3]);
        } else {
            return 0;
        }
    }

    /**
     * To support find the right Initial Pdn connection failure fallback config based on network
     * capability
     */
    private String[] getFallbackConfigForNetCapability(int netCapability) {
        if (mFallbackOnInitialConnectionFailure != null
                && mFallbackOnInitialConnectionFailure.length > 0) {
            String netCapabilityName = QnsUtils.getNameOfNetCapability(netCapability);
            for (String config : mFallbackOnInitialConnectionFailure) {
                Log.d(mLogTag, "Fallback On Initial Failure enabled for " + config);
                if (config.contains(netCapabilityName)) {
                    return config.split(":");
                }
            }
        }
        return null;
    }

    /**
     * This method returns whether input plmn needs to be regarded as domestic roaming or not.
     *
     * @return : Based on Carrier Config Settings based on operator requirement possible values:
     *     True / False
     */
    boolean isDefinedDomesticRoamingPlmn(String plmn) {
        if (mPlmnsRegardedAsDomesticRoaming != null && mPlmnsRegardedAsDomesticRoaming.length > 0) {
            for (String configuredPlmn : mPlmnsRegardedAsDomesticRoaming) {
                Log.d(
                        mLogTag,
                        "isDefinedDomesticRoamingPlmn" + configuredPlmn + " is match with " + plmn);
                if (configuredPlmn.length() == 3 && plmn.startsWith(configuredPlmn)) {
                    return true;
                } else if (configuredPlmn.length() > 4 && configuredPlmn.length() <= 6) {
                    if ((Integer.valueOf(configuredPlmn.substring(0, 3))
                                    .equals(Integer.valueOf(plmn.substring(0, 3))))
                            && (Integer.valueOf(configuredPlmn.substring(3)))
                                    .equals(Integer.valueOf(plmn.substring(3)))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    static class QnsConfigArray {

        /*
         * static invalid
         */
        static final int INVALID = 0x0000FFFF;
        /*
         * Thresholds, A signal value of good strength to enter.
         */
        int mGood = INVALID;
        /*
         * Thresholds, A signal value of bad strength to leave.
         */
        int mBad = INVALID;
        /*
         * Thresholds, A signal value of worst strength to enter.
         * The worst strength is only applicable for cellular.
         */
        int mWorst = INVALID;

        QnsConfigArray(int good, int bad, int worst) {
            set(good, bad, worst);
        }

        QnsConfigArray(int good, int bad) {
            set(good, bad, INVALID);
        }

        void set(int good, int bad, int worst) {
            mGood = good;
            mBad = bad;
            mWorst = worst;
        }

        @Override
        public String toString() {
            return "QnsConfigArray{"
                    + "Good="
                    + mGood
                    + ", Bad="
                    + mBad
                    + ", Worst="
                    + mWorst
                    + '}';
        }
    }

    @VisibleForTesting
    static class RtpMetricsConfig {
        /** Maximum jitter */
        final int mJitter;

        /** RTP packet loss rate in percentage */
        final int mPktLossRate;

        /** Time interval of RTP packet loss rate */
        final int mPktLossTime;

        /** No RTP interval in seconds */
        final int mNoRtpInterval;

        RtpMetricsConfig(int jitter, int pktLossRate, int pktLossTime, int noRtpInterval) {
            this.mJitter = jitter;
            this.mPktLossRate = pktLossRate;
            this.mPktLossTime = pktLossTime;
            this.mNoRtpInterval = noRtpInterval;
        }

        @Override
        public String toString() {
            return "RtpMetricsConfig{"
                    + "mJitter="
                    + mJitter
                    + ", mPktLossRate="
                    + mPktLossRate
                    + ", mPktLossTime="
                    + mPktLossTime
                    + ", mNoRtpInterval="
                    + mNoRtpInterval
                    + '}';
        }
    }
}