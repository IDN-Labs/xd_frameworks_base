/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.frameworks.core.powerstatsviewer;

import android.content.Context;
import android.os.Process;

import com.android.internal.os.BatterySipper;
import com.android.internal.os.BatteryStatsHelper;

import java.util.ArrayList;
import java.util.List;

public class PowerStatsData {
    private static final String PACKAGE_CALENDAR_PROVIDER = "com.android.providers.calendar";
    private static final String PACKAGE_MEDIA_PROVIDER = "com.android.providers.media";
    private static final String PACKAGE_SYSTEMUI = "com.android.systemui";
    private static final String[] PACKAGES_SYSTEM = {PACKAGE_MEDIA_PROVIDER,
            PACKAGE_CALENDAR_PROVIDER, PACKAGE_SYSTEMUI};

    enum EntryType {
        POWER,
        DURATION,
    }

    public static class Entry {
        public String title;
        public EntryType entryType;
        public double value;
        public double total;
    }

    private final PowerConsumerInfoHelper.PowerConsumerInfo mPowerConsumerInfo;
    private final List<Entry> mEntries = new ArrayList<>();

    public PowerStatsData(Context context, BatteryStatsHelper batteryStatsHelper,
            String powerConsumerId) {
        List<BatterySipper> usageList = batteryStatsHelper.getUsageList();

        double totalPowerMah = 0;
        double totalSmearedPowerMah = 0;
        double totalPowerExcludeSystemMah = 0;
        double totalScreenPower = 0;
        double totalProportionalSmearMah = 0;
        double totalCpuPowerMah = 0;
        double totalSystemServiceCpuPowerMah = 0;
        double totalUsagePowerMah = 0;
        double totalWakeLockPowerMah = 0;
        double totalMobileRadioPowerMah = 0;
        double totalWifiPowerMah = 0;
        double totalBluetoothPowerMah = 0;
        double totalGpsPowerMah = 0;
        double totalCameraPowerMah = 0;
        double totalFlashlightPowerMah = 0;
        double totalSensorPowerMah = 0;
        double totalAudioPowerMah = 0;
        double totalVideoPowerMah = 0;

        long totalCpuTimeMs = 0;
        long totalCpuFgTimeMs = 0;
        long totalWakeLockTimeMs = 0;
        long totalWifiRunningTimeMs = 0;
        long totalBluetoothRunningTimeMs = 0;
        long totalGpsTimeMs = 0;
        long totalCameraTimeMs = 0;
        long totalFlashlightTimeMs = 0;
        long totalAudioTimeMs = 0;
        long totalVideoTimeMs = 0;

        BatterySipper requestedPowerConsumer = null;
        for (BatterySipper sipper : usageList) {
            if (sipper.drainType == BatterySipper.DrainType.SCREEN) {
                totalScreenPower = sipper.sumPower();
            }

            if (powerConsumerId(sipper).equals(powerConsumerId)) {
                requestedPowerConsumer = sipper;
            }

            totalPowerMah += sipper.sumPower();
            totalSmearedPowerMah += sipper.totalSmearedPowerMah;
            totalProportionalSmearMah += sipper.proportionalSmearMah;

            if (!isSystemSipper(sipper)) {
                totalPowerExcludeSystemMah += sipper.totalSmearedPowerMah;
            }

            totalCpuPowerMah += sipper.cpuPowerMah;
            totalSystemServiceCpuPowerMah += sipper.systemServiceCpuPowerMah;
            totalUsagePowerMah += sipper.usagePowerMah;
            totalWakeLockPowerMah += sipper.wakeLockPowerMah;
            totalMobileRadioPowerMah += sipper.mobileRadioPowerMah;
            totalWifiPowerMah += sipper.wifiPowerMah;
            totalBluetoothPowerMah += sipper.bluetoothPowerMah;
            totalGpsPowerMah += sipper.gpsPowerMah;
            totalCameraPowerMah += sipper.cameraPowerMah;
            totalFlashlightPowerMah += sipper.flashlightPowerMah;
            totalSensorPowerMah += sipper.sensorPowerMah;
            totalAudioPowerMah += sipper.audioPowerMah;
            totalVideoPowerMah += sipper.videoPowerMah;

            totalCpuTimeMs += sipper.cpuTimeMs;
            totalCpuFgTimeMs += sipper.cpuFgTimeMs;
            totalWakeLockTimeMs += sipper.wakeLockTimeMs;
            totalWifiRunningTimeMs += sipper.wifiRunningTimeMs;
            totalBluetoothRunningTimeMs += sipper.bluetoothRunningTimeMs;
            totalGpsTimeMs += sipper.gpsTimeMs;
            totalCameraTimeMs += sipper.cameraTimeMs;
            totalFlashlightTimeMs += sipper.flashlightTimeMs;
            totalAudioTimeMs += sipper.audioTimeMs;
            totalVideoTimeMs += sipper.videoTimeMs;
        }

        if (requestedPowerConsumer == null) {
            mPowerConsumerInfo = null;
            return;
        }

        mPowerConsumerInfo = PowerConsumerInfoHelper.makePowerConsumerInfo(
                context.getPackageManager(), requestedPowerConsumer);

        addEntry("Total power", EntryType.POWER,
                requestedPowerConsumer.totalSmearedPowerMah, totalSmearedPowerMah);
        addEntry("... excluding system", EntryType.POWER,
                requestedPowerConsumer.totalSmearedPowerMah, totalPowerExcludeSystemMah);
        addEntry("Screen, smeared", EntryType.POWER,
                requestedPowerConsumer.screenPowerMah, totalScreenPower);
        addEntry("Other, smeared", EntryType.POWER,
                requestedPowerConsumer.proportionalSmearMah, totalProportionalSmearMah);
        addEntry("Excluding smeared", EntryType.POWER,
                requestedPowerConsumer.totalPowerMah, totalPowerMah);
        addEntry("CPU", EntryType.POWER,
                requestedPowerConsumer.cpuPowerMah, totalCpuPowerMah);
        addEntry("System services", EntryType.POWER,
                requestedPowerConsumer.systemServiceCpuPowerMah, totalSystemServiceCpuPowerMah);
        addEntry("Usage", EntryType.POWER,
                requestedPowerConsumer.usagePowerMah, totalUsagePowerMah);
        addEntry("Wake lock", EntryType.POWER,
                requestedPowerConsumer.wakeLockPowerMah, totalWakeLockPowerMah);
        addEntry("Mobile radio", EntryType.POWER,
                requestedPowerConsumer.mobileRadioPowerMah, totalMobileRadioPowerMah);
        addEntry("WiFi", EntryType.POWER,
                requestedPowerConsumer.wifiPowerMah, totalWifiPowerMah);
        addEntry("Bluetooth", EntryType.POWER,
                requestedPowerConsumer.bluetoothPowerMah, totalBluetoothPowerMah);
        addEntry("GPS", EntryType.POWER,
                requestedPowerConsumer.gpsPowerMah, totalGpsPowerMah);
        addEntry("Camera", EntryType.POWER,
                requestedPowerConsumer.cameraPowerMah, totalCameraPowerMah);
        addEntry("Flashlight", EntryType.POWER,
                requestedPowerConsumer.flashlightPowerMah, totalFlashlightPowerMah);
        addEntry("Sensors", EntryType.POWER,
                requestedPowerConsumer.sensorPowerMah, totalSensorPowerMah);
        addEntry("Audio", EntryType.POWER,
                requestedPowerConsumer.audioPowerMah, totalAudioPowerMah);
        addEntry("Video", EntryType.POWER,
                requestedPowerConsumer.videoPowerMah, totalVideoPowerMah);

        addEntry("CPU time", EntryType.DURATION,
                requestedPowerConsumer.cpuTimeMs, totalCpuTimeMs);
        addEntry("CPU foreground time", EntryType.DURATION,
                requestedPowerConsumer.cpuFgTimeMs, totalCpuFgTimeMs);
        addEntry("Wake lock time", EntryType.DURATION,
                requestedPowerConsumer.wakeLockTimeMs, totalWakeLockTimeMs);
        addEntry("WiFi running time", EntryType.DURATION,
                requestedPowerConsumer.wifiRunningTimeMs, totalWifiRunningTimeMs);
        addEntry("Bluetooth time", EntryType.DURATION,
                requestedPowerConsumer.bluetoothRunningTimeMs, totalBluetoothRunningTimeMs);
        addEntry("GPS time", EntryType.DURATION,
                requestedPowerConsumer.gpsTimeMs, totalGpsTimeMs);
        addEntry("Camera time", EntryType.DURATION,
                requestedPowerConsumer.cameraTimeMs, totalCameraTimeMs);
        addEntry("Flashlight time", EntryType.DURATION,
                requestedPowerConsumer.flashlightTimeMs, totalFlashlightTimeMs);
        addEntry("Audio time", EntryType.DURATION,
                requestedPowerConsumer.audioTimeMs, totalAudioTimeMs);
        addEntry("Video time", EntryType.DURATION,
                requestedPowerConsumer.videoTimeMs, totalVideoTimeMs);
    }

    private boolean isSystemSipper(BatterySipper sipper) {
        final int uid = sipper.uidObj == null ? -1 : sipper.getUid();
        if (uid >= Process.ROOT_UID && uid < Process.FIRST_APPLICATION_UID) {
            return true;
        } else if (sipper.mPackages != null) {
            for (final String packageName : sipper.mPackages) {
                for (final String systemPackage : PACKAGES_SYSTEM) {
                    if (systemPackage.equals(packageName)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private void addEntry(String title, EntryType entryType, double amount, double totalAmount) {
        Entry entry = new Entry();
        entry.title = title;
        entry.entryType = entryType;
        entry.value = amount;
        entry.total = totalAmount;
        mEntries.add(entry);
    }

    public PowerConsumerInfoHelper.PowerConsumerInfo getPowerConsumerInfo() {
        return mPowerConsumerInfo;
    }

    public List<Entry> getEntries() {
        return mEntries;
    }

    public static String powerConsumerId(BatterySipper sipper) {
        return sipper.drainType + "|" + sipper.userId + "|" + sipper.getUid();
    }
}
