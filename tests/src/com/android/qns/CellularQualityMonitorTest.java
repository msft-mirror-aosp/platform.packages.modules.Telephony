package com.android.qns;

import static com.android.qns.QualityMonitor.EVENT_CELLULAR_QNS_TELEPHONY_INFO_CHANGED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.HandlerThread;
import android.os.Message;
import android.telephony.AccessNetworkConstants;
import android.telephony.CellSignalStrengthCdma;
import android.telephony.CellSignalStrengthGsm;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.CellSignalStrengthTdscdma;
import android.telephony.CellSignalStrengthWcdma;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SignalStrengthUpdateRequest;
import android.telephony.SignalThresholdInfo;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.telephony.data.ApnSetting;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@RunWith(JUnit4.class)
public final class CellularQualityMonitorTest extends QnsTest {
    @Mock private QnsTelephonyListener.QnsTelephonyInfo mQnsTelephonyInfo;
    private CellularQualityMonitor mCellularQualityMonitor;
    int apnType1 = ApnSetting.TYPE_IMS;
    int apnType2 = ApnSetting.TYPE_EMERGENCY;
    int slotIndex = 0;
    Threshold th1[];
    Threshold th2[];
    Threshold th3[] = new Threshold[1];
    Threshold[] mOutputThs;
    CountDownLatch latch;
    ThresholdListener mThresholdListener;

    private class ThresholdListener extends ThresholdCallback
            implements ThresholdCallback.CellularThresholdListener {

        ThresholdListener(Executor executor) {
            this.init(executor);
        }

        @Override
        public void onCellularThresholdChanged(Threshold[] thresholds) {
            mOutputThs = thresholds;
            latch.countDown();
        }
    }

    Executor e = Runnable::run;

    HandlerThread mHandlerThread =
            new HandlerThread("") {
                @Override
                protected void onLooperPrepared() {
                    super.onLooperPrepared();
                    mCellularQualityMonitor =
                            (CellularQualityMonitor)
                                    QualityMonitor.getInstance(
                                            sMockContext,
                                            AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
                                            slotIndex);
                    setReady(true);
                }
            };

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        super.setUp();
        mHandlerThread.start();
        waitUntilReady();
        latch = new CountDownLatch(1);
        mThresholdListener = new ThresholdListener(e);

        th1 =
                new Threshold[] {
                    new Threshold(
                            AccessNetworkConstants.AccessNetworkType.EUTRAN,
                            SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRP,
                            -117,
                            QnsConstants.THRESHOLD_EQUAL_OR_SMALLER,
                            QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER),
                    new Threshold(
                            AccessNetworkConstants.AccessNetworkType.EUTRAN,
                            SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSNR,
                            -15,
                            QnsConstants.THRESHOLD_EQUAL_OR_SMALLER,
                            QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER)
                };
        th2 =
                new Threshold[] {
                    new Threshold(
                            AccessNetworkConstants.AccessNetworkType.EUTRAN,
                            SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRP,
                            -110,
                            QnsConstants.THRESHOLD_EQUAL_OR_SMALLER,
                            QnsConstants.INVALID_ID)
                };
        th3 =
                new Threshold[] {
                    new Threshold(
                            AccessNetworkConstants.AccessNetworkType.EUTRAN,
                            SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSNR,
                            -18,
                            QnsConstants.THRESHOLD_EQUAL_OR_SMALLER,
                            QnsConstants.INVALID_ID)
                };
    }

    @Test
    public void testGetCurrentQuality() {
        ArrayList<Byte> NrCqiReport = new ArrayList<>(Arrays.asList((byte) 3, (byte) 2, (byte) 1));
        SignalStrength ss =
                new SignalStrength(
                        new CellSignalStrengthCdma(-93, -132, -89, -125, 5),
                        new CellSignalStrengthGsm(-79, 2, 5),
                        new CellSignalStrengthWcdma(-94, 4, -102, -5),
                        new CellSignalStrengthTdscdma(-95, 2, -103), // not using Tdscdma
                        new CellSignalStrengthLte(-85, -91, -6, -10, 1, 12, 1),
                        new CellSignalStrengthNr(-91, -6, 3, 1, NrCqiReport, -80, -7, 4));
        when(mockTelephonyManager.getSignalStrength()).thenReturn(ss);
        int quality =
                mCellularQualityMonitor.getCurrentQuality(
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRP);
        Assert.assertEquals(-91, quality);

        quality =
                mCellularQualityMonitor.getCurrentQuality(
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRQ);
        Assert.assertEquals(-6, quality);

        quality =
                mCellularQualityMonitor.getCurrentQuality(
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSNR);
        Assert.assertEquals(-10, quality);

        quality =
                mCellularQualityMonitor.getCurrentQuality(
                        AccessNetworkConstants.AccessNetworkType.GERAN,
                        SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSI);
        Assert.assertEquals(-79, quality);

        quality =
                mCellularQualityMonitor.getCurrentQuality(
                        AccessNetworkConstants.AccessNetworkType.NGRAN,
                        SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_SSRSRP);
        Assert.assertEquals(-80, quality);

        quality =
                mCellularQualityMonitor.getCurrentQuality(
                        AccessNetworkConstants.AccessNetworkType.NGRAN,
                        SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_SSRSRQ);
        Assert.assertEquals(-7, quality);

        quality =
                mCellularQualityMonitor.getCurrentQuality(
                        AccessNetworkConstants.AccessNetworkType.NGRAN,
                        SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_SSSINR);
        Assert.assertEquals(4, quality);

        quality =
                mCellularQualityMonitor.getCurrentQuality(
                        AccessNetworkConstants.AccessNetworkType.UTRAN,
                        SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_ECNO);
        Assert.assertEquals(-5, quality);

        quality =
                mCellularQualityMonitor.getCurrentQuality(
                        AccessNetworkConstants.AccessNetworkType.EUTRAN,
                        SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_UNKNOWN);
        Assert.assertEquals(SignalStrength.INVALID, quality);
    }

    @Test
    public void testRegisterThresholdChange() {

        mCellularQualityMonitor.registerThresholdChange(
                mThresholdListener, apnType1, th1, slotIndex);
        Assert.assertEquals(1, mCellularQualityMonitor.mThresholdCallbackMap.size());
        Assert.assertEquals(1, mCellularQualityMonitor.mThresholdsList.size());
        Assert.assertEquals(2, mCellularQualityMonitor.getSignalThresholdInfo().size());

        mCellularQualityMonitor.registerThresholdChange(
                mThresholdListener, apnType2, th2, slotIndex);
        Assert.assertEquals(2, mCellularQualityMonitor.mThresholdCallbackMap.size());
        Assert.assertEquals(2, mCellularQualityMonitor.mThresholdsList.size());

        Assert.assertTrue(
                mCellularQualityMonitor.mThresholdsList.containsKey(
                        mCellularQualityMonitor.getKey(apnType1, slotIndex)));
        Assert.assertTrue(
                mCellularQualityMonitor.mThresholdsList.containsKey(
                        mCellularQualityMonitor.getKey(apnType2, slotIndex)));

        Assert.assertEquals(
                2,
                mCellularQualityMonitor
                        .mThresholdsList
                        .get(mCellularQualityMonitor.getKey(apnType1, slotIndex))
                        .size());
        Assert.assertEquals(
                1,
                mCellularQualityMonitor
                        .mThresholdsList
                        .get(mCellularQualityMonitor.getKey(apnType2, slotIndex))
                        .size());

        // multiple measurement type supported
        Assert.assertEquals(2, mCellularQualityMonitor.getSignalThresholdInfo().size());
    }

    @Test
    public void testUnregisterThresholdChange() {
        testRegisterThresholdChange();
        mCellularQualityMonitor.unregisterThresholdChange(apnType1, slotIndex);
        Assert.assertEquals(1, mCellularQualityMonitor.mThresholdCallbackMap.size());
        Assert.assertEquals(1, mCellularQualityMonitor.mThresholdsList.size());
        Assert.assertTrue(
                mCellularQualityMonitor.mThresholdsList.containsKey(
                        mCellularQualityMonitor.getKey(apnType2, slotIndex)));
        Assert.assertFalse(
                mCellularQualityMonitor.mThresholdsList.containsKey(
                        mCellularQualityMonitor.getKey(apnType1, slotIndex)));
        Assert.assertEquals(1, mCellularQualityMonitor.getSignalThresholdInfo().size());

        mCellularQualityMonitor.unregisterThresholdChange(apnType2, slotIndex);
        Assert.assertEquals(0, mCellularQualityMonitor.mThresholdCallbackMap.size());
        Assert.assertEquals(0, mCellularQualityMonitor.mThresholdsList.size());

        Assert.assertFalse(
                mCellularQualityMonitor.mThresholdsList.containsKey(
                        mCellularQualityMonitor.getKey(apnType1, slotIndex)));
        Assert.assertFalse(
                mCellularQualityMonitor.mThresholdsList.containsKey(
                        mCellularQualityMonitor.getKey(apnType2, slotIndex)));

        Assert.assertEquals(0, mCellularQualityMonitor.getSignalThresholdInfo().size());
    }

    @Test
    public void testUpdateThresholdsForApn() {
        testRegisterThresholdChange();
        mCellularQualityMonitor.updateThresholdsForApn(apnType2, slotIndex, th3);
        Assert.assertEquals(2, mCellularQualityMonitor.mThresholdCallbackMap.size());
        Assert.assertEquals(2, mCellularQualityMonitor.mThresholdsList.size());
        Assert.assertTrue(
                mCellularQualityMonitor.mThresholdsList.containsKey(
                        mCellularQualityMonitor.getKey(apnType1, slotIndex)));
        Assert.assertTrue(
                mCellularQualityMonitor.mThresholdsList.containsKey(
                        mCellularQualityMonitor.getKey(apnType2, slotIndex)));

        Assert.assertEquals(
                1,
                mCellularQualityMonitor
                        .mThresholdsList
                        .get(mCellularQualityMonitor.getKey(apnType2, slotIndex))
                        .size());
        Assert.assertEquals(2, mCellularQualityMonitor.getSignalThresholdInfo().size());
    }

    @Test
    public void testGetSignalThresholdInfo() {
        testRegisterThresholdChange();
        List<SignalThresholdInfo> stInfoList = mCellularQualityMonitor.getSignalThresholdInfo();
        Assert.assertEquals(2, stInfoList.size()); // multiple measurement type supported
    }

    @Test
    public void testDiffApnDiffThresholdSameMeasurementType() {
        int[] thresholds = new int[] {-110, -112, -99, -100, -70};
        int apn1 = ApnSetting.TYPE_MMS;
        int apn2 = ApnSetting.TYPE_IMS;
        int apn3 = ApnSetting.TYPE_XCAP;
        Threshold[] t1 =
                new Threshold[] {
                    new Threshold(
                            AccessNetworkConstants.AccessNetworkType.EUTRAN,
                            SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRP,
                            thresholds[0],
                            QnsConstants.THRESHOLD_EQUAL_OR_LARGER,
                            QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER)
                };
        Threshold[] t2 =
                new Threshold[] {
                    new Threshold(
                            AccessNetworkConstants.AccessNetworkType.EUTRAN,
                            SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRP,
                            thresholds[1],
                            QnsConstants.THRESHOLD_EQUAL_OR_LARGER,
                            QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER),
                    new Threshold(
                            AccessNetworkConstants.AccessNetworkType.EUTRAN,
                            SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRP,
                            thresholds[2],
                            QnsConstants.THRESHOLD_EQUAL_OR_LARGER,
                            QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER),
                };
        Threshold[] t3 =
                new Threshold[] {
                    new Threshold(
                            AccessNetworkConstants.AccessNetworkType.EUTRAN,
                            SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRP,
                            thresholds[3],
                            QnsConstants.THRESHOLD_EQUAL_OR_LARGER,
                            QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER),
                    new Threshold(
                            AccessNetworkConstants.AccessNetworkType.EUTRAN,
                            SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRP,
                            thresholds[4],
                            QnsConstants.THRESHOLD_EQUAL_OR_LARGER,
                            QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER),
                };
        mCellularQualityMonitor.registerThresholdChange(mThresholdListener, apn1, t1, slotIndex);
        List<SignalThresholdInfo> stInfo = mCellularQualityMonitor.getSignalThresholdInfo();
        Assert.assertEquals(1, stInfo.size());
        Assert.assertArrayEquals(new int[] {thresholds[0]}, stInfo.get(0).getThresholds());

        mCellularQualityMonitor.registerThresholdChange(mThresholdListener, apn2, t2, slotIndex);
        stInfo = mCellularQualityMonitor.getSignalThresholdInfo();
        Assert.assertEquals(1, stInfo.size());
        int[] th_array = new int[] {thresholds[0], thresholds[1], thresholds[2]};
        Arrays.sort(th_array);
        Assert.assertArrayEquals(th_array, stInfo.get(0).getThresholds());

        mCellularQualityMonitor.registerThresholdChange(mThresholdListener, apn3, t3, slotIndex);
        stInfo = mCellularQualityMonitor.getSignalThresholdInfo();
        Assert.assertEquals(1, stInfo.size());
        th_array = new int[] {thresholds[0], thresholds[1], thresholds[2], thresholds[3]};
        Arrays.sort(th_array);
        Assert.assertArrayEquals(th_array, stInfo.get(0).getThresholds());
    }

    @Test
    public void testDiffApnSameThresholdSameMeasurementType() {
        int[] thresholds = new int[] {-110, -100, -110, -100, -70};
        int apn1 = ApnSetting.TYPE_MMS;
        int apn2 = ApnSetting.TYPE_IMS;
        int apn3 = ApnSetting.TYPE_XCAP;
        Threshold[] t1 =
                new Threshold[] {
                    new Threshold(
                            AccessNetworkConstants.AccessNetworkType.EUTRAN,
                            SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRP,
                            thresholds[0],
                            QnsConstants.THRESHOLD_EQUAL_OR_LARGER,
                            QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER)
                };
        Threshold[] t2 =
                new Threshold[] {
                    new Threshold(
                            AccessNetworkConstants.AccessNetworkType.EUTRAN,
                            SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRP,
                            thresholds[1],
                            QnsConstants.THRESHOLD_EQUAL_OR_LARGER,
                            QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER),
                    new Threshold(
                            AccessNetworkConstants.AccessNetworkType.EUTRAN,
                            SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRP,
                            thresholds[2],
                            QnsConstants.THRESHOLD_EQUAL_OR_LARGER,
                            QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER),
                };
        Threshold[] t3 =
                new Threshold[] {
                    new Threshold(
                            AccessNetworkConstants.AccessNetworkType.EUTRAN,
                            SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRP,
                            thresholds[3],
                            QnsConstants.THRESHOLD_EQUAL_OR_LARGER,
                            QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER),
                    new Threshold(
                            AccessNetworkConstants.AccessNetworkType.EUTRAN,
                            SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRP,
                            thresholds[4],
                            QnsConstants.THRESHOLD_EQUAL_OR_LARGER,
                            QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER),
                };
        mCellularQualityMonitor.registerThresholdChange(mThresholdListener, apn1, t1, slotIndex);
        List<SignalThresholdInfo> stInfo = mCellularQualityMonitor.getSignalThresholdInfo();
        Assert.assertEquals(1, stInfo.size());
        Assert.assertArrayEquals(new int[] {thresholds[0]}, stInfo.get(0).getThresholds());

        mCellularQualityMonitor.registerThresholdChange(mThresholdListener, apn2, t2, slotIndex);
        stInfo = mCellularQualityMonitor.getSignalThresholdInfo();
        Assert.assertEquals(1, stInfo.size());
        int[] th_array = new int[] {thresholds[0], thresholds[1]};
        Arrays.sort(th_array);
        Assert.assertArrayEquals(th_array, stInfo.get(0).getThresholds());

        mCellularQualityMonitor.registerThresholdChange(mThresholdListener, apn3, t3, slotIndex);
        stInfo = mCellularQualityMonitor.getSignalThresholdInfo();
        Assert.assertEquals(1, stInfo.size());
        th_array = new int[] {thresholds[0], thresholds[1], thresholds[4]};
        Arrays.sort(th_array);
        Assert.assertArrayEquals(th_array, stInfo.get(0).getThresholds());
    }

    @Test
    public void testSameApnDiffThresholdsDiffMeasurementType() {
        int[] thresholds_rsrp = new int[] {-110, -100};
        int[] thresholds_rssnr = new int[] {-11, -10};
        int[] thresholds_rsrq = new int[] {-20};
        int apn1 = ApnSetting.TYPE_IMS;
        Threshold[] t =
                new Threshold[] {
                    new Threshold(
                            AccessNetworkConstants.AccessNetworkType.EUTRAN,
                            SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRP,
                            thresholds_rsrp[0],
                            QnsConstants.THRESHOLD_EQUAL_OR_LARGER,
                            QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER),
                    new Threshold(
                            AccessNetworkConstants.AccessNetworkType.EUTRAN,
                            SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRP,
                            thresholds_rsrp[1],
                            QnsConstants.THRESHOLD_EQUAL_OR_LARGER,
                            QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER),
                    new Threshold(
                            AccessNetworkConstants.AccessNetworkType.EUTRAN,
                            SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSNR,
                            thresholds_rssnr[0],
                            QnsConstants.THRESHOLD_EQUAL_OR_LARGER,
                            QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER),
                    new Threshold(
                            AccessNetworkConstants.AccessNetworkType.EUTRAN,
                            SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSNR,
                            thresholds_rssnr[1],
                            QnsConstants.THRESHOLD_EQUAL_OR_LARGER,
                            QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER),
                    new Threshold(
                            AccessNetworkConstants.AccessNetworkType.EUTRAN,
                            SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRQ,
                            thresholds_rsrq[0],
                            QnsConstants.THRESHOLD_EQUAL_OR_LARGER,
                            QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER)
                };
        mCellularQualityMonitor.registerThresholdChange(mThresholdListener, apn1, t, slotIndex);
        List<SignalThresholdInfo> stInfoList = mCellularQualityMonitor.getSignalThresholdInfo();
        Assert.assertEquals(3, stInfoList.size());
        for (SignalThresholdInfo stInfo : stInfoList) {
            if (stInfo.getSignalMeasurementType()
                    == SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSNR) {
                Assert.assertArrayEquals(thresholds_rssnr, stInfo.getThresholds());
            } else if (stInfo.getSignalMeasurementType()
                    == SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRP) {
                Assert.assertArrayEquals(thresholds_rsrp, stInfo.getThresholds());
            } else if (stInfo.getSignalMeasurementType()
                    == SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRQ) {
                Assert.assertArrayEquals(thresholds_rsrq, stInfo.getThresholds());
            } else {
                Assert.fail();
            }
        }
    }

    @Test
    public void testNullThresholds() {
        mCellularQualityMonitor.registerThresholdChange(
                mThresholdListener, apnType1, null, slotIndex);
        Assert.assertEquals(1, mCellularQualityMonitor.mThresholdCallbackMap.size());
        Assert.assertEquals(0, mCellularQualityMonitor.mThresholdsList.size());
    }

    @Test
    public void testBackhaulTimerUpdate() {
        int waitTime = 4000;
        th1[0].setWaitTime(waitTime);
        mCellularQualityMonitor.registerThresholdChange(
                mThresholdListener, apnType1, new Threshold[] {th1[0]}, slotIndex);
        List<SignalThresholdInfo> signalThresholdInfoList =
                mCellularQualityMonitor.getSignalThresholdInfo();
        assertNotNull(signalThresholdInfoList);
        SignalThresholdInfo stInfo = signalThresholdInfoList.get(0);
        Assert.assertEquals(waitTime, stInfo.getHysteresisMs());

        mCellularQualityMonitor.updateThresholdsForApn(apnType1, slotIndex, th2);
        signalThresholdInfoList = mCellularQualityMonitor.getSignalThresholdInfo();
        assertNotNull(signalThresholdInfoList);
        stInfo = signalThresholdInfoList.get(0);
        Assert.assertEquals(0, stInfo.getHysteresisMs());
    }

    @Test
    public void testOnSignalStrengthsChanged_Matching() throws InterruptedException {
        th1 =
                new Threshold[] {
                    new Threshold(
                            AccessNetworkConstants.AccessNetworkType.EUTRAN,
                            SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRP,
                            -117,
                            QnsConstants.THRESHOLD_EQUAL_OR_LARGER,
                            QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER)
                };
        mCellularQualityMonitor.registerThresholdChange(
                mThresholdListener, apnType1, th1, slotIndex);
        ArgumentCaptor<TelephonyCallback> capture =
                ArgumentCaptor.forClass(TelephonyCallback.class);
        verify(mockTelephonyManager)
                .registerTelephonyCallback(isA(Executor.class), capture.capture());
        TelephonyCallback.SignalStrengthsListener callback =
                (TelephonyCallback.SignalStrengthsListener) capture.getValue();
        assertNotNull(callback);

        ArrayList<Byte> NrCqiReport = new ArrayList<>(Arrays.asList((byte) 3, (byte) 2, (byte) 1));
        SignalStrength ss =
                new SignalStrength(
                        new CellSignalStrengthCdma(-93, -132, -89, -125, 5),
                        new CellSignalStrengthGsm(-79, 2, 5),
                        new CellSignalStrengthWcdma(-94, 4, -102, -5),
                        new CellSignalStrengthTdscdma(-95, 2, -103), // not using Tdscdma
                        new CellSignalStrengthLte(-85, -91, -6, -10, 1, 12, 1),
                        new CellSignalStrengthNr(-91, -6, 3, 1, NrCqiReport, -80, -7, 4));

        latch = new CountDownLatch(1);
        mOutputThs = null;
        callback.onSignalStrengthsChanged(ss);
        verifyReportedThreshold(-91);

        th1 =
                new Threshold[] {
                    new Threshold(
                            AccessNetworkConstants.AccessNetworkType.UTRAN,
                            SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSCP,
                            -90,
                            QnsConstants.THRESHOLD_EQUAL_OR_SMALLER,
                            QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER)
                };
        mCellularQualityMonitor.updateThresholdsForApn(apnType1, slotIndex, th1);

        latch = new CountDownLatch(1);
        mOutputThs = null;
        callback.onSignalStrengthsChanged(ss);
        verifyReportedThreshold(-102);

        th1 =
                new Threshold[] {
                    new Threshold(
                            AccessNetworkConstants.AccessNetworkType.GERAN,
                            SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSI,
                            -70,
                            QnsConstants.THRESHOLD_EQUAL_OR_SMALLER,
                            QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER)
                };
        mCellularQualityMonitor.updateThresholdsForApn(apnType1, slotIndex, th1);

        latch = new CountDownLatch(1);
        mOutputThs = null;
        callback.onSignalStrengthsChanged(ss);
        verifyReportedThreshold(-79);

        th1 =
                new Threshold[] {
                    new Threshold(
                            AccessNetworkConstants.AccessNetworkType.NGRAN,
                            SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_SSRSRP,
                            -90,
                            QnsConstants.THRESHOLD_EQUAL_OR_LARGER,
                            QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER)
                };
        mCellularQualityMonitor.updateThresholdsForApn(apnType1, slotIndex, th1);

        latch = new CountDownLatch(1);
        mOutputThs = null;
        callback.onSignalStrengthsChanged(ss);
        verifyReportedThreshold(-80);
    }

    private void verifyReportedThreshold(int expected) throws InterruptedException {
        assertTrue(latch.await(100, TimeUnit.MILLISECONDS));
        assertTrue(mOutputThs.length > 0);
        assertEquals(expected, mOutputThs[0].getThreshold());
    }

    @Test
    public void testOnSignalStrengthsChanged_NotMatching() throws InterruptedException {
        th1 =
                new Threshold[] {
                    new Threshold(
                            AccessNetworkConstants.AccessNetworkType.EUTRAN,
                            SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSRP,
                            -90,
                            QnsConstants.THRESHOLD_EQUAL_OR_LARGER,
                            QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER)
                };
        mCellularQualityMonitor.registerThresholdChange(
                mThresholdListener, apnType1, th1, slotIndex);
        ArgumentCaptor<TelephonyCallback> capture =
                ArgumentCaptor.forClass(TelephonyCallback.class);
        verify(mockTelephonyManager)
                .registerTelephonyCallback(isA(Executor.class), capture.capture());
        TelephonyCallback.SignalStrengthsListener callback =
                (TelephonyCallback.SignalStrengthsListener) capture.getValue();
        assertNotNull(callback);

        ArrayList<Byte> NrCqiReport = new ArrayList<>(Arrays.asList((byte) 3, (byte) 2, (byte) 1));
        SignalStrength ss =
                new SignalStrength(
                        new CellSignalStrengthCdma(-93, -132, -89, -125, 5),
                        new CellSignalStrengthGsm(-79, 2, 5),
                        new CellSignalStrengthWcdma(-94, 4, -102, -5),
                        new CellSignalStrengthTdscdma(-95, 2, -103), // not using Tdscdma
                        new CellSignalStrengthLte(-85, -91, -6, -10, 1, 12, 1),
                        new CellSignalStrengthNr(-91, -6, 3, 1, NrCqiReport, -80, -7, 4));

        latch = new CountDownLatch(1);
        mOutputThs = null;
        callback.onSignalStrengthsChanged(ss);
        assertFalse(latch.await(100, TimeUnit.MILLISECONDS));
        assertNull(mOutputThs);

        th1 =
                new Threshold[] {
                    new Threshold(
                            AccessNetworkConstants.AccessNetworkType.UTRAN,
                            SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSCP,
                            -105,
                            QnsConstants.THRESHOLD_EQUAL_OR_SMALLER,
                            QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER)
                };
        mCellularQualityMonitor.updateThresholdsForApn(apnType1, slotIndex, th1);

        latch = new CountDownLatch(1);
        mOutputThs = null;
        callback.onSignalStrengthsChanged(ss);
        assertFalse(latch.await(100, TimeUnit.MILLISECONDS));
        assertNull(mOutputThs);

        th1 =
                new Threshold[] {
                    new Threshold(
                            AccessNetworkConstants.AccessNetworkType.GERAN,
                            SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_RSSI,
                            -70,
                            QnsConstants.THRESHOLD_EQUAL_OR_LARGER,
                            QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER)
                };
        mCellularQualityMonitor.updateThresholdsForApn(apnType1, slotIndex, th1);

        latch = new CountDownLatch(1);
        mOutputThs = null;
        callback.onSignalStrengthsChanged(ss);
        assertFalse(latch.await(100, TimeUnit.MILLISECONDS));
        assertNull(mOutputThs);

        th1 =
                new Threshold[] {
                    new Threshold(
                            AccessNetworkConstants.AccessNetworkType.NGRAN,
                            SignalThresholdInfo.SIGNAL_MEASUREMENT_TYPE_SSRSRP,
                            -81,
                            QnsConstants.THRESHOLD_EQUAL_OR_SMALLER,
                            QnsConstants.DEFAULT_WIFI_BACKHAUL_TIMER)
                };
        mCellularQualityMonitor.updateThresholdsForApn(apnType1, slotIndex, th1);

        latch = new CountDownLatch(1);
        mOutputThs = null;
        callback.onSignalStrengthsChanged(ss);
        assertFalse(latch.await(100, TimeUnit.MILLISECONDS));
        assertNull(mOutputThs);
    }

    @Test
    public void testOnQnsTelephonyInfoChanged() {
        testRegisterThresholdChange();
        when(mQnsTelephonyInfo.getDataRegState()).thenReturn(ServiceState.STATE_IN_SERVICE);
        when(mQnsTelephonyInfo.getDataNetworkType()).thenReturn(TelephonyManager.NETWORK_TYPE_LTE);
        when(mQnsTelephonyInfo.isCellularAvailable()).thenReturn(true, false);
        when(mQnsTelephonyInfo.getDataRegState()).thenReturn(ServiceState.STATE_IN_SERVICE);
        Message.obtain(
                        mCellularQualityMonitor.mHandler,
                        EVENT_CELLULAR_QNS_TELEPHONY_INFO_CHANGED,
                        new QnsAsyncResult(null, mQnsTelephonyInfo, null))
                .sendToTarget();
        verify(mockTelephonyManager)
                .clearSignalStrengthUpdateRequest(isA(SignalStrengthUpdateRequest.class));
        Mockito.clearInvocations(mockTelephonyManager);

        Message.obtain(
                        mCellularQualityMonitor.mHandler,
                        EVENT_CELLULAR_QNS_TELEPHONY_INFO_CHANGED,
                        new QnsAsyncResult(null, mQnsTelephonyInfo, null))
                .sendToTarget();
        verify(mockTelephonyManager, never())
                .clearSignalStrengthUpdateRequest(isA(SignalStrengthUpdateRequest.class));
    }

    @After
    public void tearDown() {
        if (mHandlerThread != null) {
            mHandlerThread.quit();
        }
        if (mCellularQualityMonitor != null) {
            mCellularQualityMonitor.dispose();
        }
    }
}
