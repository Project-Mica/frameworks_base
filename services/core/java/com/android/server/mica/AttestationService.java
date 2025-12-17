/*
 * Copyright (C) 2024 The LeafOS Project
 * Copyright (C) 2024 The Clover Project
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package com.android.server.mica;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Environment;
import android.util.Log;

import com.android.server.SystemService;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class AttestationService extends SystemService {

    private static final String TAG = AttestationService.class.getSimpleName();

    // GMS Props Constants (Original)
    private static final String GMS_API = "https://github.com/Project-Mica/vendor_certification/raw/refs/heads/main/gms_certified_props.json";
    private static final String GMS_DATA_FILE = "gms_certified_props.json";

    private static final String DEVICE_CONFIG_API = "https://github.com/Project-Mica/vendor_certification/raw/refs/heads/main/device_configs_override.json";
    private static final String DEVICE_CONFIG_DATA_FILE = "device_configs_override.json";

    private static final long INITIAL_DELAY = 0; // Start immediately on boot
    private static final long INTERVAL = 8; // Interval in hours
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final Context mContext;
    private final File mGmsDataFile; // Original
    private final File mDeviceConfigDataFile; // NEW
    private final ScheduledExecutorService mScheduler;
    private final ConnectivityManager mConnectivityManager;
    private final FetchPropsRunnable mFetchRunnable;

    private boolean mPendingUpdate;

    public AttestationService(Context context) {
        super(context);
        mContext = context;
        mGmsDataFile = new File(Environment.getDataSystemDirectory(), GMS_DATA_FILE); // Original
        mDeviceConfigDataFile = new File(Environment.getDataSystemDirectory(), DEVICE_CONFIG_DATA_FILE); // NEW
        mFetchRunnable = new FetchPropsRunnable();
        mScheduler = Executors.newSingleThreadScheduledExecutor();
        mConnectivityManager =
                (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        registerNetworkCallback();
    }

    @Override
    public void onStart() {}

    @Override
    public void onBootPhase(int phase) {
        if (isPackageInstalled(mContext, "com.google.android.gms", true)
                && phase == PHASE_BOOT_COMPLETED) {
            Log.i(TAG, "Scheduling periodic fetch every " + INTERVAL + " hours");
            mScheduler.scheduleAtFixedRate(
                    mFetchRunnable, INITIAL_DELAY, INTERVAL, TimeUnit.HOURS);
        }
    }

    private String readFromFile(File file) {
        StringBuilder content = new StringBuilder();

        if (file.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;

                while ((line = reader.readLine()) != null) {
                    content.append(line);
                }
            } catch (IOException e) {
                Log.e(TAG, "Error reading from file " + file.getName(), e);
            }
        }
        return content.toString();
    }

    private void writeToFile(File file, String data) {
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(data);
            file.setReadable(true, false); // Set -rw-r--r-- (644)
        } catch (IOException e) {
            Log.e(TAG, "Error writing to file " + file.getName(), e);
        }
    }

    // Original method now renamed and takes the API URL
    private String fetchProps(String apiUrl) {
        try {
            URL url = new URI(apiUrl).toURL();
            HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();

            try {
                urlConnection.setConnectTimeout(10000);
                urlConnection.setReadTimeout(10000);

                try (BufferedReader reader =
                             new BufferedReader(new InputStreamReader(urlConnection.getInputStream()))) {
                    StringBuilder response = new StringBuilder();
                    String line;

                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }

                    return response.toString();
                }
            } finally {
                urlConnection.disconnect();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error making API request to " + apiUrl, e);
            return null;
        }
    }

    private void dlog(String message) {
        if (DEBUG) Log.d(TAG, message);
    }

    private boolean isInternetConnected() {
        Network network = mConnectivityManager.getActiveNetwork();
        if (network != null) {
            NetworkCapabilities capabilities = mConnectivityManager.getNetworkCapabilities(network);
            boolean connected = capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            dlog("isInternetConnected(): " + connected);
            return connected;
        }
        dlog("No active network");
        return false;
    }

    private void registerNetworkCallback() {
        mConnectivityManager.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                Log.i(TAG, "Connectivity established");

                if (mPendingUpdate) {
                    Log.i(TAG, "Pending fetch detected. Executing now");
                    mScheduler.schedule(mFetchRunnable, 0, TimeUnit.SECONDS);
                    mPendingUpdate = false;
                }
            }

            @Override
            public void onLost(Network network) {
                Log.w(TAG, "Connectivity lost");
            }
        });
    }

    public static boolean isPackageInstalled(Context context, String packageName, boolean ignoreState) {
        if (packageName != null) {
            try {
                PackageInfo pi = context.getPackageManager().getPackageInfo(packageName, 0);
                if (!pi.applicationInfo.enabled && !ignoreState) {
                    return false;
                }
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
        }
        return true;
    }

    private class FetchPropsRunnable implements Runnable {
        
        private void updateFile(String apiUrl, File dataFile, String logTag) {
            try {
                String savedProps = readFromFile(dataFile);
                String props = fetchProps(apiUrl);

                if (props != null) {
                    if (!savedProps.equals(props)) {
                        dlog("Found new " + logTag + ", updating file");
                        writeToFile(dataFile, props);
                        dlog(logTag + " updated successfully");
                    } else {
                        dlog("No change in " + logTag);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in updating " + logTag, e);
            }
        }

        @Override
        public void run() {
            try {
                dlog("FetchPropsRunnable started");

                if (!isInternetConnected()) {
                    if (!mPendingUpdate) {
                        Log.w(TAG, "Internet unavailable, deferring update until network is restored");
                        mPendingUpdate = true;
                    }
                    return;
                }
                
                // 1. Update GMS Certified Props (Original Logic)
                updateFile(GMS_API, mGmsDataFile, "GMS Certified Props");

                // 2. Update DeviceConfig Overrides (NEW Logic)
                updateFile(DEVICE_CONFIG_API, mDeviceConfigDataFile, "DeviceConfig Overrides");

            } catch (Exception e) {
                Log.e(TAG, "Error in FetchPropsRunnable", e);
            }
        }
    }
}
