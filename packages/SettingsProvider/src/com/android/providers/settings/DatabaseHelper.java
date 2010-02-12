/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.providers.settings;

import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDoneException;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.media.AudioManager;
import android.media.AudioService;
import android.net.ConnectivityManager;
import android.os.Environment;
import android.os.SystemProperties;
import android.provider.Settings;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.text.TextUtils;
import android.util.Config;
import android.util.Log;
import android.util.Xml;
import com.android.common.XmlUtils;
import com.android.internal.telephony.RILConstants;

import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockPatternView;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.List;

/**
 * Database helper class for {@link SettingsProvider}.
 * Mostly just has a bit {@link #onCreate} to initialize the database.
 */
public class DatabaseHelper extends SQLiteOpenHelper {
    /**
     * Path to file containing default bookmarks, relative to ANDROID_ROOT.
     */
    private static final String DEFAULT_BOOKMARKS_PATH = "etc/bookmarks.xml";

    private static final String TAG = "SettingsProvider";
    private static final String DATABASE_NAME = "settings.db";

    // Please, please please. If you update the database version, check to make sure the
    // database gets upgraded properly. At a minimum, please confirm that 'upgradeVersion'
    // is properly propagated through your change.  Not doing so will result in a loss of user
    // settings.
    private static final int DATABASE_VERSION = 49;

    private Context mContext;

    public DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        mContext = context;
    }

    private void createSecureTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE secure (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT UNIQUE ON CONFLICT REPLACE," +
                "value TEXT" +
                ");");
        db.execSQL("CREATE INDEX secureIndex1 ON secure (name);");
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE system (" +
                    "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "name TEXT UNIQUE ON CONFLICT REPLACE," +
                    "value TEXT" +
                    ");");
        db.execSQL("CREATE INDEX systemIndex1 ON system (name);");

        createSecureTable(db);

        db.execSQL("CREATE TABLE bluetooth_devices (" +
                    "_id INTEGER PRIMARY KEY," +
                    "name TEXT," +
                    "addr TEXT," +
                    "channel INTEGER," +
                    "type INTEGER" +
                    ");");

        db.execSQL("CREATE TABLE bookmarks (" +
                    "_id INTEGER PRIMARY KEY," +
                    "title TEXT," +
                    "folder TEXT," +
                    "intent TEXT," +
                    "shortcut INTEGER," +
                    "ordering INTEGER" +
                    ");");

        db.execSQL("CREATE INDEX bookmarksIndex1 ON bookmarks (folder);");
        db.execSQL("CREATE INDEX bookmarksIndex2 ON bookmarks (shortcut);");

        // Populate bookmarks table with initial bookmarks
        loadBookmarks(db);

        // Load initial volume levels into DB
        loadVolumeLevels(db);

        // Load inital settings values
        loadSettings(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int currentVersion) {
        Log.w(TAG, "Upgrading settings database from version " + oldVersion + " to "
                + currentVersion);

        int upgradeVersion = oldVersion;

        // Pattern for upgrade blocks:
        //
        //    if (upgradeVersion == [the DATABASE_VERSION you set] - 1) {
        //        .. your upgrade logic..
        //        upgradeVersion = [the DATABASE_VERSION you set]
        //    }

        if (upgradeVersion == 20) {
            /*
             * Version 21 is part of the volume control refresh. There is no
             * longer a UI-visible for setting notification vibrate on/off (in
             * our design), but the functionality still exists. Force the
             * notification vibrate to on.
             */
            loadVibrateSetting(db, true);
            if (Config.LOGD) Log.d(TAG, "Reset system vibrate setting");

            upgradeVersion = 21;
        }

        if (upgradeVersion < 22) {
            upgradeVersion = 22;
            // Upgrade the lock gesture storage location and format
            upgradeLockPatternLocation(db);
        }

        if (upgradeVersion < 23) {
            db.execSQL("UPDATE favorites SET iconResource=0 WHERE iconType=0");
            upgradeVersion = 23;
        }

        if (upgradeVersion == 23) {
            db.beginTransaction();
            try {
                db.execSQL("ALTER TABLE favorites ADD spanX INTEGER");
                db.execSQL("ALTER TABLE favorites ADD spanY INTEGER");
                // Shortcuts, applications, folders
                db.execSQL("UPDATE favorites SET spanX=1, spanY=1 WHERE itemType<=0");
                // Photo frames, clocks
                db.execSQL(
                    "UPDATE favorites SET spanX=2, spanY=2 WHERE itemType=1000 or itemType=1002");
                // Search boxes
                db.execSQL("UPDATE favorites SET spanX=4, spanY=1 WHERE itemType=1001");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            upgradeVersion = 24;
        }

        if (upgradeVersion == 24) {
            db.beginTransaction();
            try {
                // The value of the constants for preferring wifi or preferring mobile have been
                // swapped, so reload the default.
                db.execSQL("DELETE FROM system WHERE name='network_preference'");
                db.execSQL("INSERT INTO system ('name', 'value') values ('network_preference', '" +
                        ConnectivityManager.DEFAULT_NETWORK_PREFERENCE + "')");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            upgradeVersion = 25;
        }

        if (upgradeVersion == 25) {
            db.beginTransaction();
            try {
                db.execSQL("ALTER TABLE favorites ADD uri TEXT");
                db.execSQL("ALTER TABLE favorites ADD displayMode INTEGER");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            upgradeVersion = 26;
        }

        if (upgradeVersion == 26) {
            // This introduces the new secure settings table.
            db.beginTransaction();
            try {
                createSecureTable(db);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            upgradeVersion = 27;
        }

        if (upgradeVersion == 27) {
            // Copy settings values from 'system' to 'secure' and delete them from 'system'
            SQLiteStatement insertStmt = null;
            SQLiteStatement deleteStmt = null;

            db.beginTransaction();
            try {
                insertStmt =
                    db.compileStatement("INSERT INTO secure (name,value) SELECT name,value FROM "
                        + "system WHERE name=?");
                deleteStmt = db.compileStatement("DELETE FROM system WHERE name=?");

                String[] settingsToMove = {
                    Settings.Secure.ADB_ENABLED,
                    Settings.Secure.ANDROID_ID,
                    Settings.Secure.BLUETOOTH_ON,
                    Settings.Secure.DATA_ROAMING,
                    Settings.Secure.DEVICE_PROVISIONED,
                    Settings.Secure.HTTP_PROXY,
                    Settings.Secure.INSTALL_NON_MARKET_APPS,
                    Settings.Secure.LOCATION_PROVIDERS_ALLOWED,
                    Settings.Secure.LOGGING_ID,
                    Settings.Secure.NETWORK_PREFERENCE,
                    Settings.Secure.PARENTAL_CONTROL_ENABLED,
                    Settings.Secure.PARENTAL_CONTROL_LAST_UPDATE,
                    Settings.Secure.PARENTAL_CONTROL_REDIRECT_URL,
                    Settings.Secure.SETTINGS_CLASSNAME,
                    Settings.Secure.USB_MASS_STORAGE_ENABLED,
                    Settings.Secure.USE_GOOGLE_MAIL,
                    Settings.Secure.WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON,
                    Settings.Secure.WIFI_NETWORKS_AVAILABLE_REPEAT_DELAY,
                    Settings.Secure.WIFI_NUM_OPEN_NETWORKS_KEPT,
                    Settings.Secure.WIFI_ON,
                    Settings.Secure.WIFI_WATCHDOG_ACCEPTABLE_PACKET_LOSS_PERCENTAGE,
                    Settings.Secure.WIFI_WATCHDOG_AP_COUNT,
                    Settings.Secure.WIFI_WATCHDOG_BACKGROUND_CHECK_DELAY_MS,
                    Settings.Secure.WIFI_WATCHDOG_BACKGROUND_CHECK_ENABLED,
                    Settings.Secure.WIFI_WATCHDOG_BACKGROUND_CHECK_TIMEOUT_MS,
                    Settings.Secure.WIFI_WATCHDOG_INITIAL_IGNORED_PING_COUNT,
                    Settings.Secure.WIFI_WATCHDOG_MAX_AP_CHECKS,
                    Settings.Secure.WIFI_WATCHDOG_ON,
                    Settings.Secure.WIFI_WATCHDOG_PING_COUNT,
                    Settings.Secure.WIFI_WATCHDOG_PING_DELAY_MS,
                    Settings.Secure.WIFI_WATCHDOG_PING_TIMEOUT_MS,
                };

                for (String setting : settingsToMove) {
                    insertStmt.bindString(1, setting);
                    insertStmt.execute();

                    deleteStmt.bindString(1, setting);
                    deleteStmt.execute();
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
                if (insertStmt != null) {
                    insertStmt.close();
                }
                if (deleteStmt != null) {
                    deleteStmt.close();
                }
            }
            upgradeVersion = 28;
        }

        if (upgradeVersion == 28 || upgradeVersion == 29) {
            // Note: The upgrade to 28 was flawed since it didn't delete the old
            // setting first before inserting. Combining 28 and 29 with the
            // fixed version.

            // This upgrade adds the STREAM_NOTIFICATION type to the list of
            // types affected by ringer modes (silent, vibrate, etc.)
            db.beginTransaction();
            try {
                db.execSQL("DELETE FROM system WHERE name='"
                        + Settings.System.MODE_RINGER_STREAMS_AFFECTED + "'");
                int newValue = (1 << AudioManager.STREAM_RING)
                        | (1 << AudioManager.STREAM_NOTIFICATION)
                        | (1 << AudioManager.STREAM_SYSTEM);
                db.execSQL("INSERT INTO system ('name', 'value') values ('"
                        + Settings.System.MODE_RINGER_STREAMS_AFFECTED + "', '"
                        + String.valueOf(newValue) + "')");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }

            upgradeVersion = 30;
        }

        if (upgradeVersion == 30) {
            /*
             * Upgrade 31 clears the title for all quick launch shortcuts so the
             * activities' titles will be resolved at display time. Also, the
             * folder is changed to '@quicklaunch'.
             */
            db.beginTransaction();
            try {
                db.execSQL("UPDATE bookmarks SET folder = '@quicklaunch'");
                db.execSQL("UPDATE bookmarks SET title = ''");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            upgradeVersion = 31;
        }

        if (upgradeVersion == 31) {
            /*
             * Animations are now managed in preferences, and may be
             * enabled or disabled based on product resources.
             */
            db.beginTransaction();
            try {
                db.execSQL("DELETE FROM system WHERE name='"
                        + Settings.System.WINDOW_ANIMATION_SCALE + "'");
                db.execSQL("DELETE FROM system WHERE name='"
                        + Settings.System.TRANSITION_ANIMATION_SCALE + "'");
                SQLiteStatement stmt = db.compileStatement("INSERT INTO system(name,value)"
                        + " VALUES(?,?);");
                loadDefaultAnimationSettings(stmt);
                stmt.close();
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            upgradeVersion = 32;
        }

        if (upgradeVersion == 32) {
            // The Wi-Fi watchdog SSID list is now seeded with the value of
            // the property ro.com.android.wifi-watchlist
            String wifiWatchList = SystemProperties.get("ro.com.android.wifi-watchlist");
            if (!TextUtils.isEmpty(wifiWatchList)) {
                db.beginTransaction();
                try {
                    db.execSQL("INSERT OR IGNORE INTO secure(name,value) values('" +
                            Settings.Secure.WIFI_WATCHDOG_WATCH_LIST + "','" +
                            wifiWatchList + "');");
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            }
            upgradeVersion = 33;
        }

        if (upgradeVersion == 33) {
            // Set the default zoom controls to: tap-twice to bring up +/-
            db.beginTransaction();
            try {
                db.execSQL("INSERT INTO system(name,value) values('zoom','2');");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            upgradeVersion = 34;
        }

        if (upgradeVersion == 34) {
            db.beginTransaction();
            try {
                SQLiteStatement stmt = db.compileStatement("INSERT OR IGNORE INTO secure(name,value)"
                        + " VALUES(?,?);");
                loadSecure35Settings(stmt);
                stmt.close();
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            upgradeVersion = 35;
        }
            // due to a botched merge from donut to eclair, the initialization of ASSISTED_GPS_ENABLED
            // was accidentally done out of order here.
            // to fix this, ASSISTED_GPS_ENABLED is now initialized while upgrading from 38 to 39,
            // and we intentionally do nothing from 35 to 36 now.
        if (upgradeVersion == 35) {
            upgradeVersion = 36;
        }

        if (upgradeVersion == 36) {
           // This upgrade adds the STREAM_SYSTEM_ENFORCED type to the list of
            // types affected by ringer modes (silent, vibrate, etc.)
            db.beginTransaction();
            try {
                db.execSQL("DELETE FROM system WHERE name='"
                        + Settings.System.MODE_RINGER_STREAMS_AFFECTED + "'");
                int newValue = (1 << AudioManager.STREAM_RING)
                        | (1 << AudioManager.STREAM_NOTIFICATION)
                        | (1 << AudioManager.STREAM_SYSTEM)
                        | (1 << AudioManager.STREAM_SYSTEM_ENFORCED);
                db.execSQL("INSERT INTO system ('name', 'value') values ('"
                        + Settings.System.MODE_RINGER_STREAMS_AFFECTED + "', '"
                        + String.valueOf(newValue) + "')");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            upgradeVersion = 37;
        }

        if (upgradeVersion == 37) {
            db.beginTransaction();
            try {
                SQLiteStatement stmt = db.compileStatement("INSERT OR IGNORE INTO system(name,value)"
                        + " VALUES(?,?);");
                loadStringSetting(stmt, Settings.System.AIRPLANE_MODE_TOGGLEABLE_RADIOS,
                        R.string.airplane_mode_toggleable_radios);
                stmt.close();
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            upgradeVersion = 38;
        }

        if (upgradeVersion == 38) {
            db.beginTransaction();
            try {
                String value =
                        mContext.getResources().getBoolean(R.bool.assisted_gps_enabled) ? "1" : "0";
                db.execSQL("INSERT OR IGNORE INTO secure(name,value) values('" +
                        Settings.Secure.ASSISTED_GPS_ENABLED + "','" + value + "');");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }

            upgradeVersion = 39;
        }

        if (upgradeVersion == 39) {
            db.beginTransaction();
            try {
                String value =
                        mContext.getResources().getBoolean(
                        R.bool.def_screen_brightness_automatic_mode) ? "1" : "0";
                db.execSQL("INSERT OR IGNORE INTO system(name,value) values('" +
                        Settings.System.SCREEN_BRIGHTNESS_MODE + "','" + value + "');");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }

            upgradeVersion = 40;
        }

        if (upgradeVersion == 40) {
            /*
             * All animations are now turned on by default!
             */
            db.beginTransaction();
            try {
                db.execSQL("DELETE FROM system WHERE name='"
                        + Settings.System.WINDOW_ANIMATION_SCALE + "'");
                db.execSQL("DELETE FROM system WHERE name='"
                        + Settings.System.TRANSITION_ANIMATION_SCALE + "'");
                SQLiteStatement stmt = db.compileStatement("INSERT INTO system(name,value)"
                        + " VALUES(?,?);");
                loadDefaultAnimationSettings(stmt);
                stmt.close();
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            upgradeVersion = 41;
        }

        if (upgradeVersion == 41) {
            /*
             * Initialize newly public haptic feedback setting
             */
            db.beginTransaction();
            try {
                db.execSQL("DELETE FROM system WHERE name='"
                        + Settings.System.HAPTIC_FEEDBACK_ENABLED + "'");
                SQLiteStatement stmt = db.compileStatement("INSERT INTO system(name,value)"
                        + " VALUES(?,?);");
                loadDefaultHapticSettings(stmt);
                stmt.close();
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            upgradeVersion = 42;
        }

        if (upgradeVersion == 42) {
            /*
             * Initialize new notification pulse setting
             */
            db.beginTransaction();
            try {
                SQLiteStatement stmt = db.compileStatement("INSERT INTO system(name,value)"
                        + " VALUES(?,?);");
                loadBooleanSetting(stmt, Settings.System.NOTIFICATION_LIGHT_PULSE,
                        R.bool.def_notification_pulse);
                stmt.close();
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            upgradeVersion = 43;
        }

        if (upgradeVersion == 43) {
            /*
             * This upgrade stores bluetooth volume separately from voice volume
             */
            db.beginTransaction();
            try {
                SQLiteStatement stmt = db.compileStatement("INSERT OR IGNORE INTO system(name,value)"
                        + " VALUES(?,?);");
                loadSetting(stmt, Settings.System.VOLUME_BLUETOOTH_SCO,
                        AudioManager.DEFAULT_STREAM_VOLUME[AudioManager.STREAM_BLUETOOTH_SCO]);
                stmt.close();
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            upgradeVersion = 44;
        }

        if (upgradeVersion == 44) {
            /*
             * Gservices was moved into vendor/google.
             */
            db.execSQL("DROP TABLE IF EXISTS gservices");
            db.execSQL("DROP INDEX IF EXISTS gservicesIndex1");
            upgradeVersion = 45;
        }

        if (upgradeVersion == 45) {
             /*
              * New settings for MountService
              */
            db.beginTransaction();
            try {
                db.execSQL("INSERT INTO secure(name,value) values('" +
                        Settings.Secure.MOUNT_PLAY_NOTIFICATION_SND + "','1');");
                db.execSQL("INSERT INTO secure(name,value) values('" +
                        Settings.Secure.MOUNT_UMS_AUTOSTART + "','0');");
                db.execSQL("INSERT INTO secure(name,value) values('" +
                        Settings.Secure.MOUNT_UMS_PROMPT + "','1');");
                db.execSQL("INSERT INTO secure(name,value) values('" +
                        Settings.Secure.MOUNT_UMS_NOTIFY_ENABLED + "','1');");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            upgradeVersion = 46;
        }

        if (upgradeVersion == 46) {
            /*
             * The password mode constants have changed; reset back to no
             * password.
             */
            db.beginTransaction();
            try {
                db.execSQL("DELETE FROM system WHERE name='lockscreen.password_type';");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
           upgradeVersion = 47;
       }


        if (upgradeVersion == 47) {
            /*
             * The password mode constants have changed again; reset back to no
             * password.
             */
            db.beginTransaction();
            try {
                db.execSQL("DELETE FROM system WHERE name='lockscreen.password_type';");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
           upgradeVersion = 48;
       }

       if (upgradeVersion == 48) {
           /*
            * Adding a new setting for which voice recognition service to use.
            */
           db.beginTransaction();
           try {
               SQLiteStatement stmt = db.compileStatement("INSERT OR IGNORE INTO secure(name,value)"
                       + " VALUES(?,?);");
               loadVoiceRecognitionServiceSetting(stmt);
               stmt.close();
               db.setTransactionSuccessful();
           } finally {
               db.endTransaction();
           }
           upgradeVersion = 49;
       }

       if (upgradeVersion != currentVersion) {
            Log.w(TAG, "Got stuck trying to upgrade from version " + upgradeVersion
                    + ", must wipe the settings provider");
            db.execSQL("DROP TABLE IF EXISTS system");
            db.execSQL("DROP INDEX IF EXISTS systemIndex1");
            db.execSQL("DROP TABLE IF EXISTS secure");
            db.execSQL("DROP INDEX IF EXISTS secureIndex1");
            db.execSQL("DROP TABLE IF EXISTS gservices");
            db.execSQL("DROP INDEX IF EXISTS gservicesIndex1");
            db.execSQL("DROP TABLE IF EXISTS bluetooth_devices");
            db.execSQL("DROP TABLE IF EXISTS bookmarks");
            db.execSQL("DROP INDEX IF EXISTS bookmarksIndex1");
            db.execSQL("DROP INDEX IF EXISTS bookmarksIndex2");
            db.execSQL("DROP TABLE IF EXISTS favorites");
            onCreate(db);

            // Added for diagnosing settings.db wipes after the fact
            String wipeReason = oldVersion + "/" + upgradeVersion + "/" + currentVersion;
            db.execSQL("INSERT INTO secure(name,value) values('" +
                    "wiped_db_reason" + "','" + wipeReason + "');");
        }
    }

    private void upgradeLockPatternLocation(SQLiteDatabase db) {
        Cursor c = db.query("system", new String[] {"_id", "value"}, "name='lock_pattern'",
                null, null, null, null);
        if (c.getCount() > 0) {
            c.moveToFirst();
            String lockPattern = c.getString(1);
            if (!TextUtils.isEmpty(lockPattern)) {
                // Convert lock pattern
                try {
                    LockPatternUtils lpu = new LockPatternUtils(mContext);
                    List<LockPatternView.Cell> cellPattern =
                            LockPatternUtils.stringToPattern(lockPattern);
                    lpu.saveLockPattern(cellPattern);
                } catch (IllegalArgumentException e) {
                    // Don't want corrupted lock pattern to hang the reboot process
                }
            }
            c.close();
            db.delete("system", "name='lock_pattern'", null);
        } else {
            c.close();
        }
    }

    /**
     * Loads the default set of bookmarked shortcuts from an xml file.
     *
     * @param db The database to write the values into
     * @param startingIndex The zero-based position at which bookmarks in this file should begin
     * @param subPath The relative path from ANDROID_ROOT to the file to read
     * @param quiet If true, do no complain if the file is missing
     */
    private int loadBookmarks(SQLiteDatabase db, int startingIndex, String subPath,
            boolean quiet) {
        FileReader bookmarksReader;

        // Environment.getRootDirectory() is a fancy way of saying ANDROID_ROOT or "/system".
        final File favFile = new File(Environment.getRootDirectory(), subPath);
        try {
            bookmarksReader = new FileReader(favFile);
        } catch (FileNotFoundException e) {
            if (!quiet) {
                Log.e(TAG, "Couldn't find or open bookmarks file " + favFile);
            }
            return 0;
        }

        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        ContentValues values = new ContentValues();

        PackageManager packageManager = mContext.getPackageManager();
        ActivityInfo info;
        int i = startingIndex;
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(bookmarksReader);

            XmlUtils.beginDocument(parser, "bookmarks");

            while (true) {
                XmlUtils.nextElement(parser);

                String name = parser.getName();
                if (!"bookmark".equals(name)) {
                    break;
                }

                String pkg = parser.getAttributeValue(null, "package");
                String cls = parser.getAttributeValue(null, "class");
                String shortcutStr = parser.getAttributeValue(null, "shortcut");
                int shortcutValue = (int) shortcutStr.charAt(0);
                if (TextUtils.isEmpty(shortcutStr)) {
                    Log.w(TAG, "Unable to get shortcut for: " + pkg + "/" + cls);
                }
                try {
                    ComponentName cn = new ComponentName(pkg, cls);
                    info = packageManager.getActivityInfo(cn, 0);
                    intent.setComponent(cn);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    values.put(Settings.Bookmarks.INTENT, intent.toURI());
                    values.put(Settings.Bookmarks.TITLE,
                            info.loadLabel(packageManager).toString());
                    values.put(Settings.Bookmarks.SHORTCUT, shortcutValue);
                    db.insert("bookmarks", null, values);
                    i++;
                } catch (PackageManager.NameNotFoundException e) {
                    Log.w(TAG, "Unable to add bookmark: " + pkg + "/" + cls, e);
                }
            }
        } catch (XmlPullParserException e) {
            Log.w(TAG, "Got execption parsing bookmarks.", e);
        } catch (IOException e) {
            Log.w(TAG, "Got execption parsing bookmarks.", e);
        }

        return i;
    }

    /**
     * Loads the default set of bookmark packages.
     *
     * @param db The database to write the values into
     */
    private void loadBookmarks(SQLiteDatabase db) {
        loadBookmarks(db, 0, DEFAULT_BOOKMARKS_PATH, false);
    }

    /**
     * Loads the default volume levels. It is actually inserting the index of
     * the volume array for each of the volume controls.
     *
     * @param db the database to insert the volume levels into
     */
    private void loadVolumeLevels(SQLiteDatabase db) {
        SQLiteStatement stmt = db.compileStatement("INSERT OR IGNORE INTO system(name,value)"
                + " VALUES(?,?);");

        loadSetting(stmt, Settings.System.VOLUME_MUSIC,
                AudioManager.DEFAULT_STREAM_VOLUME[AudioManager.STREAM_MUSIC]);
        loadSetting(stmt, Settings.System.VOLUME_RING,
                AudioManager.DEFAULT_STREAM_VOLUME[AudioManager.STREAM_RING]);
        loadSetting(stmt, Settings.System.VOLUME_SYSTEM,
                AudioManager.DEFAULT_STREAM_VOLUME[AudioManager.STREAM_SYSTEM]);
        loadSetting(
                stmt,
                Settings.System.VOLUME_VOICE,
                AudioManager.DEFAULT_STREAM_VOLUME[AudioManager.STREAM_VOICE_CALL]);
        loadSetting(stmt, Settings.System.VOLUME_ALARM,
                AudioManager.DEFAULT_STREAM_VOLUME[AudioManager.STREAM_ALARM]);
        loadSetting(
                stmt,
                Settings.System.VOLUME_NOTIFICATION,
                AudioManager.DEFAULT_STREAM_VOLUME[AudioManager.STREAM_NOTIFICATION]);
        loadSetting(
                stmt,
                Settings.System.VOLUME_BLUETOOTH_SCO,
                AudioManager.DEFAULT_STREAM_VOLUME[AudioManager.STREAM_BLUETOOTH_SCO]);

        loadSetting(stmt, Settings.System.MODE_RINGER,
                AudioManager.RINGER_MODE_NORMAL);

        loadVibrateSetting(db, false);

        // By default, only the ring/notification and system streams are affected
        loadSetting(stmt, Settings.System.MODE_RINGER_STREAMS_AFFECTED,
                (1 << AudioManager.STREAM_RING) | (1 << AudioManager.STREAM_NOTIFICATION) |
                (1 << AudioManager.STREAM_SYSTEM) | (1 << AudioManager.STREAM_SYSTEM_ENFORCED));

        loadSetting(stmt, Settings.System.MUTE_STREAMS_AFFECTED,
                ((1 << AudioManager.STREAM_MUSIC) |
                 (1 << AudioManager.STREAM_RING) |
                 (1 << AudioManager.STREAM_NOTIFICATION) |
                 (1 << AudioManager.STREAM_SYSTEM)));

        stmt.close();
    }

    private void loadVibrateSetting(SQLiteDatabase db, boolean deleteOld) {
        if (deleteOld) {
            db.execSQL("DELETE FROM system WHERE name='" + Settings.System.VIBRATE_ON + "'");
        }

        SQLiteStatement stmt = db.compileStatement("INSERT OR IGNORE INTO system(name,value)"
                + " VALUES(?,?);");

        // Vibrate off by default for ringer, on for notification
        int vibrate = 0;
        vibrate = AudioService.getValueForVibrateSetting(vibrate,
                AudioManager.VIBRATE_TYPE_NOTIFICATION, AudioManager.VIBRATE_SETTING_ON);
        vibrate = AudioService.getValueForVibrateSetting(vibrate,
                AudioManager.VIBRATE_TYPE_RINGER, AudioManager.VIBRATE_SETTING_OFF);
        loadSetting(stmt, Settings.System.VIBRATE_ON, vibrate);
    }

    private void loadSettings(SQLiteDatabase db) {
        loadSystemSettings(db);
        loadSecureSettings(db);
    }

    private void loadSystemSettings(SQLiteDatabase db) {
        SQLiteStatement stmt = db.compileStatement("INSERT OR IGNORE INTO system(name,value)"
                + " VALUES(?,?);");

        Resources r = mContext.getResources();

        loadBooleanSetting(stmt, Settings.System.DIM_SCREEN,
                R.bool.def_dim_screen);
        loadSetting(stmt, Settings.System.STAY_ON_WHILE_PLUGGED_IN,
                "1".equals(SystemProperties.get("ro.kernel.qemu")) ? 1 : 0);
        loadIntegerSetting(stmt, Settings.System.SCREEN_OFF_TIMEOUT,
                R.integer.def_screen_off_timeout);

        // Set default cdma emergency tone
        loadSetting(stmt, Settings.System.EMERGENCY_TONE, 0);

        // Set default cdma call auto retry
        loadSetting(stmt, Settings.System.CALL_AUTO_RETRY, 0);

        // Set default cdma DTMF type
        loadSetting(stmt, Settings.System.DTMF_TONE_TYPE_WHEN_DIALING, 0);

        // Set default hearing aid
        loadSetting(stmt, Settings.System.HEARING_AID, 0);

        // Set default tty mode
        loadSetting(stmt, Settings.System.TTY_MODE, 0);

        loadBooleanSetting(stmt, Settings.System.AIRPLANE_MODE_ON,
                R.bool.def_airplane_mode_on);

        loadStringSetting(stmt, Settings.System.AIRPLANE_MODE_RADIOS,
                R.string.def_airplane_mode_radios);

        loadStringSetting(stmt, Settings.System.AIRPLANE_MODE_TOGGLEABLE_RADIOS,
                R.string.airplane_mode_toggleable_radios);

        loadBooleanSetting(stmt, Settings.System.AUTO_TIME,
                R.bool.def_auto_time); // Sync time to NITZ

        loadIntegerSetting(stmt, Settings.System.SCREEN_BRIGHTNESS,
                R.integer.def_screen_brightness);

        loadBooleanSetting(stmt, Settings.System.SCREEN_BRIGHTNESS_MODE,
                R.bool.def_screen_brightness_automatic_mode);

        loadDefaultAnimationSettings(stmt);

        loadBooleanSetting(stmt, Settings.System.ACCELEROMETER_ROTATION,
                R.bool.def_accelerometer_rotation);

        loadDefaultHapticSettings(stmt);

        loadBooleanSetting(stmt, Settings.System.NOTIFICATION_LIGHT_PULSE,
                R.bool.def_notification_pulse);
        loadBooleanSetting(stmt, Settings.System.SET_INSTALL_LOCATION, R.bool.set_install_location);
        loadSetting(stmt, Settings.System.DEFAULT_INSTALL_LOCATION,
                PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY);
        stmt.close();
    }

    private void loadDefaultAnimationSettings(SQLiteStatement stmt) {
        loadFractionSetting(stmt, Settings.System.WINDOW_ANIMATION_SCALE,
                R.fraction.def_window_animation_scale, 1);
        loadFractionSetting(stmt, Settings.System.TRANSITION_ANIMATION_SCALE,
                R.fraction.def_window_transition_scale, 1);
    }

    private void loadDefaultHapticSettings(SQLiteStatement stmt) {
        loadBooleanSetting(stmt, Settings.System.HAPTIC_FEEDBACK_ENABLED,
                R.bool.def_haptic_feedback);
    }

    private void loadSecureSettings(SQLiteDatabase db) {
        SQLiteStatement stmt = db.compileStatement("INSERT OR IGNORE INTO secure(name,value)"
                + " VALUES(?,?);");

        loadBooleanSetting(stmt, Settings.Secure.BLUETOOTH_ON,
                R.bool.def_bluetooth_on);

        // Data roaming default, based on build
        loadSetting(stmt, Settings.Secure.DATA_ROAMING,
                "true".equalsIgnoreCase(
                        SystemProperties.get("ro.com.android.dataroaming",
                                "false")) ? 1 : 0);

        loadBooleanSetting(stmt, Settings.Secure.INSTALL_NON_MARKET_APPS,
                R.bool.def_install_non_market_apps);

        loadStringSetting(stmt, Settings.Secure.LOCATION_PROVIDERS_ALLOWED,
                R.string.def_location_providers_allowed);

        loadBooleanSetting(stmt, Settings.Secure.ASSISTED_GPS_ENABLED,
                R.bool.assisted_gps_enabled);

        loadIntegerSetting(stmt, Settings.Secure.NETWORK_PREFERENCE,
                R.integer.def_network_preference);

        loadBooleanSetting(stmt, Settings.Secure.USB_MASS_STORAGE_ENABLED,
                R.bool.def_usb_mass_storage_enabled);

        loadBooleanSetting(stmt, Settings.Secure.WIFI_ON,
                R.bool.def_wifi_on);
        loadBooleanSetting(stmt, Settings.Secure.WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON,
                R.bool.def_networks_available_notification_on);

        String wifiWatchList = SystemProperties.get("ro.com.android.wifi-watchlist");
        if (!TextUtils.isEmpty(wifiWatchList)) {
            loadSetting(stmt, Settings.Secure.WIFI_WATCHDOG_WATCH_LIST, wifiWatchList);
        }

        // Set the preferred network mode to 0 = Global, CDMA default
        int type = SystemProperties.getInt("ro.telephony.default_network",
                RILConstants.PREFERRED_NETWORK_MODE);
        loadSetting(stmt, Settings.Secure.PREFERRED_NETWORK_MODE, type);

        // Enable or disable Cell Broadcast SMS
        loadSetting(stmt, Settings.Secure.CDMA_CELL_BROADCAST_SMS,
                RILConstants.CDMA_CELL_BROADCAST_SMS_DISABLED);

        // Set the preferred cdma subscription to 0 = Subscription from RUIM, when available
        loadSetting(stmt, Settings.Secure.PREFERRED_CDMA_SUBSCRIPTION,
                RILConstants.PREFERRED_CDMA_SUBSCRIPTION);

        // Don't do this.  The SystemServer will initialize ADB_ENABLED from a
        // persistent system property instead.
        //loadSetting(stmt, Settings.Secure.ADB_ENABLED, 0);

        // Allow mock locations default, based on build
        loadSetting(stmt, Settings.Secure.ALLOW_MOCK_LOCATION,
                "1".equals(SystemProperties.get("ro.allow.mock.location")) ? 1 : 0);

        loadSecure35Settings(stmt);

        loadBooleanSetting(stmt, Settings.Secure.MOUNT_PLAY_NOTIFICATION_SND,
                R.bool.def_mount_play_notification_snd);

        loadBooleanSetting(stmt, Settings.Secure.MOUNT_UMS_AUTOSTART,
                R.bool.def_mount_ums_autostart);

        loadBooleanSetting(stmt, Settings.Secure.MOUNT_UMS_PROMPT,
                R.bool.def_mount_ums_prompt);

        loadBooleanSetting(stmt, Settings.Secure.MOUNT_UMS_NOTIFY_ENABLED,
                R.bool.def_mount_ums_notify_enabled);

        loadVoiceRecognitionServiceSetting(stmt);

        stmt.close();
    }

    private void loadSecure35Settings(SQLiteStatement stmt) {
        loadBooleanSetting(stmt, Settings.Secure.BACKUP_ENABLED,
                R.bool.def_backup_enabled);

        loadStringSetting(stmt, Settings.Secure.BACKUP_TRANSPORT,
                R.string.def_backup_transport);
    }

    /**
     * Introduced in database version 49.
     */
    private void loadVoiceRecognitionServiceSetting(SQLiteStatement stmt) {
        String selectedService = null;
        List<ResolveInfo> availableRecognitionServices =
                mContext.getPackageManager().queryIntentServices(
                        new Intent(RecognitionService.SERVICE_INTERFACE), 0);
        int numAvailable = availableRecognitionServices.size();

        if (numAvailable == 0) {
            Log.w(TAG, "no available voice recognition services found");
        } else {
            if (numAvailable > 1) {
                Log.w(TAG, "more than one voice recognition service found, picking first");
            }

            ServiceInfo serviceInfo = availableRecognitionServices.get(0).serviceInfo;
            selectedService =
                    new ComponentName(serviceInfo.packageName, serviceInfo.name).flattenToString();
        }

        loadSetting(stmt, Settings.Secure.VOICE_RECOGNITION_SERVICE,
                selectedService == null ? "" : selectedService);
    }

    private void loadSetting(SQLiteStatement stmt, String key, Object value) {
        stmt.bindString(1, key);
        stmt.bindString(2, value.toString());
        stmt.execute();
    }

    private void loadStringSetting(SQLiteStatement stmt, String key, int resid) {
        loadSetting(stmt, key, mContext.getResources().getString(resid));
    }

    private void loadBooleanSetting(SQLiteStatement stmt, String key, int resid) {
        loadSetting(stmt, key,
                mContext.getResources().getBoolean(resid) ? "1" : "0");
    }

    private void loadIntegerSetting(SQLiteStatement stmt, String key, int resid) {
        loadSetting(stmt, key,
                Integer.toString(mContext.getResources().getInteger(resid)));
    }

    private void loadFractionSetting(SQLiteStatement stmt, String key, int resid, int base) {
        loadSetting(stmt, key,
                Float.toString(mContext.getResources().getFraction(resid, base, base)));
    }
}
