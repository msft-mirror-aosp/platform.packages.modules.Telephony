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

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class QnsConstantsTest {

    @Test
    public void testCallTypeToString() {
        String callType_str = null;

        callType_str = QnsConstants.callTypeToString(QnsConstants.CALL_TYPE_IDLE);
        Assert.assertEquals(callType_str, "IDLE");
        callType_str = QnsConstants.callTypeToString(QnsConstants.CALL_TYPE_VOICE);
        Assert.assertEquals(callType_str, "VOICE");
        callType_str = QnsConstants.callTypeToString(QnsConstants.CALL_TYPE_VIDEO);
        Assert.assertEquals(callType_str, "VIDEO");
        callType_str = QnsConstants.callTypeToString(QnsConstants.CALL_TYPE_EMERGENCY);
        Assert.assertEquals(callType_str, "SOS");
        callType_str = QnsConstants.callTypeToString(-1);
        Assert.assertEquals(callType_str, "");
    }

    @Test
    public void testCoverageToString() {
        String coverage_str = null;

        coverage_str = QnsConstants.coverageToString(QnsConstants.COVERAGE_HOME);
        Assert.assertEquals(coverage_str, "HOME");
        coverage_str = QnsConstants.coverageToString(QnsConstants.COVERAGE_ROAM);
        Assert.assertEquals(coverage_str, "ROAM");
        coverage_str = QnsConstants.coverageToString(2);
        Assert.assertEquals(coverage_str, "");
    }

    @Test
    public void testPreferenceToString() {
        String preference_str = null;

        preference_str = QnsConstants.preferenceToString(QnsConstants.WIFI_ONLY);
        Assert.assertEquals(preference_str, "WIFI_ONLY");
        preference_str = QnsConstants.preferenceToString(QnsConstants.CELL_PREF);
        Assert.assertEquals(preference_str, "CELL_PREF");
        preference_str = QnsConstants.preferenceToString(QnsConstants.WIFI_PREF);
        Assert.assertEquals(preference_str, "WIFI_PREF");

        preference_str = QnsConstants.preferenceToString(3);
        Assert.assertEquals(preference_str, "");
    }

    @Test
    public void testDirectionToString() {
        String direction_str = null;

        direction_str = QnsConstants.directionToString(QnsConstants.ROVE_IN);
        Assert.assertEquals(direction_str, "ROVE_IN");
        direction_str = QnsConstants.directionToString(QnsConstants.ROVE_OUT);
        Assert.assertEquals(direction_str, "ROVE_OUT");
    }

    @Test
    public void guardingToString() {
        String guarding_str = null;

        guarding_str = QnsConstants.guardingToString(QnsConstants.GUARDING_NONE);
        Assert.assertEquals(guarding_str, "GUARDING_NONE");
        guarding_str = QnsConstants.guardingToString(QnsConstants.GUARDING_CELLULAR);
        Assert.assertEquals(guarding_str, "GUARDING_CELL");
        guarding_str = QnsConstants.guardingToString(QnsConstants.GUARDING_WIFI);
        Assert.assertEquals(guarding_str, "GUARDING_WIFI");
        guarding_str = QnsConstants.guardingToString(QnsConstants.INVALID_ID);
        Assert.assertEquals(guarding_str, "");
    }

    @Test
    public void imsRegistrationEventToString() {
        String imsRegEvent_str = null;

        imsRegEvent_str =
                QnsConstants.imsRegistrationEventToString(
                        QnsConstants.IMS_REGISTRATION_CHANGED_UNREGISTERED);
        Assert.assertEquals(imsRegEvent_str, "IMS_REGISTRATION_CHANGED_UNREGISTERED");
        imsRegEvent_str =
                QnsConstants.imsRegistrationEventToString(
                        QnsConstants.IMS_REGISTRATION_CHANGED_ACCESS_NETWORK_CHANGE_FAILED);
        Assert.assertEquals(
                imsRegEvent_str, "IMS_REGISTRATION_CHANGED_ACCESS_NETWORK_CHANGE_FAILED");
        imsRegEvent_str =
                QnsConstants.imsRegistrationEventToString(
                        QnsConstants.IMS_REGISTRATION_CHANGED_REGISTERED);
        Assert.assertEquals(imsRegEvent_str, "IMS_REGISTRATION_CHANGED_REGISTERED");
        imsRegEvent_str = QnsConstants.imsRegistrationEventToString(QnsConstants.INVALID_ID);
        Assert.assertEquals(imsRegEvent_str, "");
    }
}
