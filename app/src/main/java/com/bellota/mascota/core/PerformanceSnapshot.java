package com.bellota.mascota.core;

import android.app.ActivityManager;
import android.content.Context;
import android.os.BatteryManager;
import android.os.Debug;
import android.os.Process;

import java.util.Locale;

/** Lightweight process/system snapshot for debug stability runs. */
public final class PerformanceSnapshot {
    private PerformanceSnapshot() { }

    public static String capture(Context context) {
        Runtime runtime = Runtime.getRuntime();
        long javaUsedKb = Math.max(0L, runtime.totalMemory() - runtime.freeMemory()) / 1024L;
        long nativeHeapKb = Debug.getNativeHeapAllocatedSize() / 1024L;
        long pssKb = Debug.getPss();
        long cpuTimeMs = Process.getElapsedCpuTime();
        long availableRamKb = -1L;
        int batteryPercent = -1;

        ActivityManager activityManager =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager != null) {
            ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
            activityManager.getMemoryInfo(memoryInfo);
            availableRamKb = memoryInfo.availMem / 1024L;
        }
        BatteryManager batteryManager =
                (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        if (batteryManager != null) {
            batteryPercent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        }

        return String.format(Locale.US,
                "sample_time_ms=%d pss_kb=%d native_heap_kb=%d java_heap_used_kb=%d "
                        + "process_cpu_ms=%d system_available_ram_kb=%d battery_pct=%d",
                System.currentTimeMillis(), pssKb, nativeHeapKb, javaUsedKb,
                cpuTimeMs, availableRamKb, batteryPercent);
    }
}
