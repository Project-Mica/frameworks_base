/*
 * Copyright (C) 2023 The Pixel Experience Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.util.mica;

import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Utility class for managing DeviceConfig properties with dynamic overrides
 * read from a JSON file instead of static resources.
 */
public class DeviceConfigUtils {

    private static final String TAG = DeviceConfigUtils.class.getSimpleName();
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final String DATA_FILE = "device_configs_override.json";

    // Map to store {namespace/property -> value} overrides
    private static Map<String, String> sDeviceConfigsOverride = null;

    private static Map<String, String> loadDeviceConfigsOverride() {
        if (sDeviceConfigsOverride != null) {
            return sDeviceConfigsOverride;
        }

        File dataFile = new File(Environment.getDataSystemDirectory(), DATA_FILE);
        JSONObject jsonObject = null;
        
        if (dataFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(dataFile))) {
                StringBuilder content = new StringBuilder();
                String line;

                while ((line = reader.readLine()) != null) {
                    content.append(line);
                }
                
                if (content.length() > 0) {
                    jsonObject = new JSONObject(content.toString());
                } else {
                    Log.w(TAG, DATA_FILE + " is empty.");
                }

            } catch (IOException e) {
                Log.e(TAG, "Error reading from file " + DATA_FILE, e);
            } catch (JSONException e) {
                Log.e(TAG, "Error parsing JSON from file " + DATA_FILE, e);
            }
        } else {
            if (DEBUG) Log.d(TAG, DATA_FILE + " does not exist, using empty map.");
        }

        Map<String, String> configs = new HashMap<>();
        if (jsonObject != null) {
            Iterator<String> keys = jsonObject.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                try {
                    // key format is expected to be "namespace/property"
                    configs.put(key, jsonObject.getString(key));
                } catch (JSONException e) {
                    Log.e(TAG, "Error getting value for key: " + key, e);
                }
            }
        }
        
        sDeviceConfigsOverride = Collections.unmodifiableMap(configs);
        if (DEBUG) Log.d(TAG, "Loaded " + sDeviceConfigsOverride.size() + " device config overrides.");
        return sDeviceConfigsOverride;
    }

    /**
     * Checks if a given DeviceConfig property should be denied (i.e., overridden).
     * The override keys in the JSON are expected to be in "namespace/property" format.
     *
     * @param namespace The namespace of the DeviceConfig property.
     * @param property The name of the DeviceConfig property.
     * @return true if an override exists for this property, false otherwise.
     */
    public static boolean shouldDenyDeviceConfigControl(String namespace, String property) {
        String fullKey = namespace + "/" + property;
        Map<String, String> overrides = loadDeviceConfigsOverride();

        boolean deny = overrides.containsKey(fullKey);
        if (DEBUG) Log.d(TAG, "shouldDenyDeviceConfigControl for " + fullKey + ": " + (deny ? "DENY" : "ALLOW"));
        return deny;
    }

    /**
     * Sets the default values for all properties found in the override JSON file
     * if they are not filtered.
     *
     * @param filterNamespace If not null, properties in this namespace are skipped.
     * @param filterProperty If not null, properties with this name are skipped.
     */
    public static void setDefaultProperties(String filterNamespace, String filterProperty) {
        if (DEBUG) Log.d(TAG, "setDefaultProperties");
        Map<String, String> overrides = loadDeviceConfigsOverride();

        for (Map.Entry<String, String> entry : overrides.entrySet()) {
            String fullKey = entry.getKey();
            String value = entry.getValue();

            String[] nsKey = fullKey.split("/", 2); // Split only once
            if (nsKey.length != 2) {
                Log.e(TAG, "Invalid key format in JSON: " + fullKey);
                continue;
            }

            String namespace = nsKey[0];
            String key = nsKey[1];

            if (filterNamespace != null && filterNamespace.equals(namespace)){
                continue;
            }

            if (filterProperty != null && filterProperty.equals(key)){
                continue;
            }

            // The 'false' argument means 'makeDefault' is false, which is correct
            // for setting a potentially-overridden default value.
            Settings.Config.putString(namespace, key, value, false);
            if (DEBUG) Log.d(TAG, "Set default: " + fullKey + "=" + value);
        }
    }
}
