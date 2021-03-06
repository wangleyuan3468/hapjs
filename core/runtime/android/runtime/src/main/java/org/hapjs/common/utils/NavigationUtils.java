/*
 * Copyright (c) 2021, the hapjs-platform Project Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.hapjs.common.utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.ContextThemeWrapper;
import java.lang.ref.WeakReference;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.hapjs.bridge.HybridRequest;
import org.hapjs.logging.RuntimeLogManager;
import org.hapjs.pm.NativePackageProvider;
import org.hapjs.runtime.ProviderManager;
import org.hapjs.runtime.R;
import org.hapjs.system.SysOpProvider;

public class NavigationUtils {
    public static final String EXTRA_SMS_BODY = "sms_body";
    private static final String TAG = "NavigationUtils";
    private static final String SCHEMA_TEL = "tel";
    private static final String SCHEMA_SMS = "sms";
    private static final String SCHEMA_MAILTO = "mailto";
    private static final String HOST_HAP_SETTINGS = "settings";
    private static final String PATH_LOCATION_SOURCE_MANAGER = "/location_source_manager";
    private static final String PATH_WLAN_MANAGER = "/wlan_manager";
    private static final String PATH_BLUETOOTH_MANAGER = "/bluetooth_manager";
    private static final String PATH_5G_MANAGER = "/5g";
    private static final String GOOGLE_PLAY_PACKAGE = "com.android.vending";
    private static final String GOOGLE_SERVICE_PACKAGE = "com.google.android.gms";
    private static final Set<String> WHITE_APP_SET = new HashSet<>();
    private static final Map<String, String> SETTING_MAP = new HashMap<>();
    private static WeakReference<AlertDialog> sDialogRef;

    static {
        WHITE_APP_SET.add(GOOGLE_PLAY_PACKAGE);
        WHITE_APP_SET.add(GOOGLE_SERVICE_PACKAGE);
        SETTING_MAP.put(PATH_LOCATION_SOURCE_MANAGER, Settings.ACTION_LOCATION_SOURCE_SETTINGS);
        SETTING_MAP.put(PATH_WLAN_MANAGER, Settings.ACTION_WIFI_SETTINGS);
        SETTING_MAP.put(PATH_BLUETOOTH_MANAGER, Settings.ACTION_BLUETOOTH_SETTINGS);
    }

    public static boolean navigate(
            Context context, String pkg, HybridRequest request, Bundle extras,
            String routerAppFrom) {
        if (request == null) {
            return false;
        }
        String url = request.getUri();
        if (url == null) {
            return false;
        }

        Uri uri = Uri.parse(url);
        String schema = uri.getScheme();
        if (TextUtils.isEmpty(schema) || UriUtils.isWebSchema(schema)) {
            return false;
        }

        if (UriUtils.isHybridSchema(schema)) {
            return handleHapSetting(context, uri);
        }

        try {
            if (SCHEMA_TEL.equals(schema)) {
                dial(context, pkg, uri, extras, routerAppFrom);
            } else if (SCHEMA_SMS.equals(schema) || SCHEMA_MAILTO.equals(schema)) {
                sendto(context, pkg, uri, request, extras, routerAppFrom);
            } else {
                boolean isDeeplink = request.isDeepLink();
                return view(context, pkg, url, isDeeplink, extras, routerAppFrom);
            }
            return true;
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "Fail to navigate to url: " + url, e);
            return false;
        }
    }

    private static boolean handleHapSetting(Context context, Uri uri) {
        String host = uri.getHost();
        if (HOST_HAP_SETTINGS.equals(host)) {
            String path = uri.getPath();
            String setting = SETTING_MAP.get(path);
            if (TextUtils.isEmpty(setting) == false) {
                Intent intent = new Intent(setting);
                context.startActivity(intent);
                return true;

            } else if (TextUtils.equals(PATH_5G_MANAGER, path)) {
                SysOpProvider provider =
                        ProviderManager.getDefault().getProvider(SysOpProvider.NAME);
                ComponentName componentName = provider.get5gMgrComponent();
                Intent intent = new Intent();
                intent.setComponent(componentName);
                ResolveInfo resolveInfo =
                        context.getPackageManager()
                                .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
                if (null != resolveInfo) {
                    try {
                        context.startActivity(intent);
                        return true;
                    } catch (Exception e) {
                        Log.e(TAG, "Failed route to 5g mgr.", e);
                    }
                } else {
                    Log.e(TAG, "null of resolve info.");
                }
                return false;
            }
        }
        return false;
    }

    private static void dial(
            Context context, String pkg, Uri uri, Bundle extras, String routerAppFrom) {
        Intent intent = new Intent(Intent.ACTION_DIAL);
        intent.setData(uri);
        intent.putExtras(extras);
        context.startActivity(intent);

        statRouterNativeApp(context, pkg, uri.toString(), intent, routerAppFrom, true);
    }

    private static void sendto(
            Context context,
            String pkg,
            Uri uri,
            HybridRequest request,
            Bundle extras,
            String routerAppFrom) {
        if (request != null && request.getParams() != null) {
            for (Map.Entry<String, String> entry : request.getParams().entrySet()) {
                if ("body".equals(entry.getKey())) {
                    extras.putString(EXTRA_SMS_BODY, entry.getValue());
                } else {
                    extras.putString(entry.getKey(), entry.getValue());
                }
            }
        }
        Intent intent = new Intent(Intent.ACTION_SENDTO);
        intent.setData(uri);
        intent.putExtras(extras);
        context.startActivity(intent);

        statRouterNativeApp(context, pkg, uri.toString(), intent, routerAppFrom, true);
    }

    private static boolean view(
            Context context,
            String pkg,
            String url,
            boolean isDeepLink,
            Bundle extras,
            String routerAppFrom) {
        try {
            Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
            PackageManager packageManager = context.getPackageManager();
            ResolveInfo info = packageManager.resolveActivity(intent, 0);
            if (info == null) {
                return false;
            }
            String packageName = info.activityInfo.packageName;
            if (isDeepLink) {
                if (!isInWhiteList(packageName)
                        && !PackageUtils.isSystemPackage(context, packageName)) {
                    statRouterNativeApp(context, pkg, url, intent, routerAppFrom, false);
                    return false;
                }
            }
            if (url.startsWith(UriUtils.SCHEMA_INTENT)
                    &&
                    (packageName.equals(context.getPackageName()) || !info.activityInfo.exported)) {
                return false;
            }
            if (extras != null) {
                intent.putExtras(extras);
            }
            return openNativeApp(
                    (Activity) context, packageManager, pkg, intent, info, routerAppFrom, url);
        } catch (URISyntaxException e) {
            // ignore
        }
        return false;
    }

    public static boolean openNativeApp(
            Activity activity,
            PackageManager packageManager,
            String rpkPkg,
            Intent intent,
            ResolveInfo info,
            String routerAppFrom,
            String url) {
        if (packageManager == null) {
            packageManager = activity.getPackageManager();
        }
        String packageName = info.activityInfo.packageName;

        NativePackageProvider provider =
                ProviderManager.getDefault().getProvider(NativePackageProvider.NAME);
        if (provider.inRouterForbiddenList(activity, rpkPkg, packageName)
                || !provider.triggeredByGestureEvent(activity, rpkPkg)) {
            Log.d(TAG,
                    "Fail to launch app: match router blacklist or open app without user input.");
            statRouterNativeApp(activity, rpkPkg, url, intent, routerAppFrom, false);
            return false;
        }

        if (!provider.inRouterDialogList(activity, rpkPkg, packageName)) {
            activity.startActivity(intent);
            statRouterNativeApp(activity, rpkPkg, url, intent, routerAppFrom, true);
        } else {
            showOpenAppDialog(activity, intent, rpkPkg, url, routerAppFrom, info, packageManager);
        }

        return true;
    }

    private static void showOpenAppDialog(
            Activity activity,
            Intent intent,
            String rpkPkg,
            String url,
            String routerAppFrom,
            ResolveInfo info,
            PackageManager packageManager) {
        if (info == null) {
            return;
        }
        String appName = info.loadLabel(packageManager).toString();
        String message = activity.getString(R.string.quick_app_open_native, appName);
        AlertDialog.Builder builder =
                new AlertDialog.Builder(
                        new ContextThemeWrapper(activity, ThemeUtils.getAlertDialogTheme()));
        builder.setMessage(message);
        builder.setCancelable(true);

        activity.runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {
                        if (activity.isFinishing() || activity.isDestroyed()) {
                            return;
                        }
                        AlertDialog tempDialog = sDialogRef == null ? null : sDialogRef.get();
                        if (tempDialog != null && tempDialog.isShowing()) {
                            return;
                        }
                        Application.ActivityLifecycleCallbacks activityLifecycle =
                                new Application.ActivityLifecycleCallbacks() {
                                    @Override
                                    public void onActivityCreated(Activity activity,
                                                                  Bundle savedInstanceState) {
                                    }

                                    @Override
                                    public void onActivityStarted(Activity activity) {
                                    }

                                    @Override
                                    public void onActivityResumed(Activity activity) {
                                    }

                                    @Override
                                    public void onActivityPaused(Activity activity) {
                                    }

                                    @Override
                                    public void onActivityStopped(Activity activity) {
                                        AlertDialog tempDialog =
                                                sDialogRef == null ? null : sDialogRef.get();
                                        if (tempDialog != null && tempDialog.isShowing()) {
                                            tempDialog.dismiss();
                                        }
                                        sDialogRef = null;
                                        activity.getApplication()
                                                .unregisterActivityLifecycleCallbacks(this);
                                    }

                                    @Override
                                    public void onActivitySaveInstanceState(Activity activity,
                                                                            Bundle outState) {
                                    }

                                    @Override
                                    public void onActivityDestroyed(Activity activity) {
                                    }
                                };
                        DialogInterface.OnClickListener listener =
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        sDialogRef = null;
                                        boolean tempResult = false;
                                        if (which == DialogInterface.BUTTON_POSITIVE) {
                                            activity.startActivity(intent);
                                            tempResult = true;
                                        } else {
                                            Log.d(TAG, "Fail to open native package: " + rpkPkg
                                                    + ", user denied");
                                        }
                                        statRouterNativeApp(activity, rpkPkg, url, intent,
                                                routerAppFrom, tempResult);
                                        RuntimeLogManager.getDefault()
                                                .logRouterDialogClick(rpkPkg,
                                                        info.activityInfo.packageName, tempResult);
                                        activity
                                                .getApplication()
                                                .unregisterActivityLifecycleCallbacks(
                                                        activityLifecycle);
                                    }
                                };
                        builder.setNegativeButton(android.R.string.cancel, listener);
                        builder.setPositiveButton(android.R.string.ok, listener);
                        builder.setOnCancelListener(
                                new DialogInterface.OnCancelListener() {
                                    @Override
                                    public void onCancel(DialogInterface dialog) {
                                        Log.d(TAG, "Fail to open native package: " + rpkPkg
                                                + ", canceled");
                                        sDialogRef = null;
                                        statRouterNativeApp(activity, rpkPkg, url, intent,
                                                routerAppFrom, false);
                                        RuntimeLogManager.getDefault()
                                                .logRouterDialogClick(rpkPkg,
                                                        info.activityInfo.packageName, false);
                                        activity
                                                .getApplication()
                                                .unregisterActivityLifecycleCallbacks(
                                                        activityLifecycle);
                                    }
                                });
                        AlertDialog dialog = builder.create();
                        sDialogRef = new WeakReference<>(dialog);
                        activity.getApplication()
                                .registerActivityLifecycleCallbacks(activityLifecycle);
                        dialog.show();
                        RuntimeLogManager.getDefault()
                                .logRouterDialogShow(rpkPkg, info.activityInfo.packageName);
                    }
                });
    }

    public static void statRouterNativeApp(
            Context context,
            String pkg,
            String uri,
            Intent intent,
            String routerAppFrom,
            boolean result) {
        ResolveInfo info = context.getPackageManager().resolveActivity(intent, 0);
        if (info != null) {
            RuntimeLogManager.getDefault()
                    .logAppRouterNativeApp(
                            pkg,
                            uri,
                            info.activityInfo.packageName,
                            info.activityInfo.name,
                            routerAppFrom,
                            result);
        }
    }

    private static boolean isInWhiteList(String pkg) {
        return WHITE_APP_SET.contains(pkg);
    }
}
