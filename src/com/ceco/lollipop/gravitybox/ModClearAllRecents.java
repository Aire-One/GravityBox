/*
 * Copyright (C) 2015 Peter Gregus for GravityBox Project (C3C076@xda)
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

package com.ceco.lollipop.gravitybox;

import java.util.ArrayList;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ImageView.ScaleType;
import android.widget.TextView;

import com.ceco.lollipop.gravitybox.R;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class ModClearAllRecents {
    private static final String TAG = "GB:ModClearAllRecents";
    public static final String PACKAGE_NAME = "com.android.systemui";
    public static final String CLASS_RECENT_VERTICAL_SCROLL_VIEW = "com.android.systemui.recent.RecentsVerticalScrollView";
    public static final String CLASS_RECENT_HORIZONTAL_SCROLL_VIEW = "com.android.systemui.recent.RecentsHorizontalScrollView";
    public static final String CLASS_RECENT_PANEL_VIEW = "com.android.systemui.recent.RecentsPanelView";
    public static final String CLASS_RECENT_ACTIVITY = "com.android.systemui.recents.RecentsActivity";
    public static final String CLASS_SWIPE_HELPER = "com.android.systemui.recents.views.SwipeHelper";
    public static final String CLASS_TASK_STACK_VIEW = "com.android.systemui.recents.views.TaskStackView";
    private static final boolean DEBUG = false;

    private static ImageView mRecentsClearButton;
    private static int mButtonGravity;
    private static int mMarginTopPx;
    private static int mMarginBottomPx;
    private static boolean mNavbarLeftHanded;
    private static ViewGroup mRecentsView;

    // RAM bar
    private static TextView mBackgroundProcessText;
    private static TextView mForegroundProcessText;
    private static ActivityManager mAm;
    private static MemInfoReader mMemInfoReader;
    private static Context mGbContext;
    private static LinearColorBar mRamUsageBar;
    private static int mRamBarGravity;
    private static Handler mHandler;
    private static int[] mRamUsageBarPaddings;
    private static int mClearAllRecentsSizePx;
    private static int mRamUsageBarVerticalMargin;
    private static int mRamUsageBarHorizontalMargin;

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    private static BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) log("Broadcast received: " + intent.toString());
            if (intent.getAction().equals(GravityBoxSettings.ACTION_PREF_RECENTS_CHANGED)) {
                if (intent.hasExtra(GravityBoxSettings.EXTRA_RECENTS_CLEAR_ALL)) {
                    mButtonGravity = intent.getIntExtra(GravityBoxSettings.EXTRA_RECENTS_CLEAR_ALL, 0);
                    updateButtonLayout();
                    updateRamBarLayout();
                }
                if (intent.hasExtra(GravityBoxSettings.EXTRA_RECENTS_RAMBAR)) {
                    mRamBarGravity = intent.getIntExtra(GravityBoxSettings.EXTRA_RECENTS_RAMBAR, 0);
                    updateRamBarLayout();
                }
                if (intent.hasExtra(GravityBoxSettings.EXTRA_RECENTS_MARGIN_TOP)) {
                    mMarginTopPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                            intent.getIntExtra(GravityBoxSettings.EXTRA_RECENTS_MARGIN_TOP, 77),
                            context.getResources().getDisplayMetrics());
                    updateButtonLayout();
                    updateRamBarLayout();
                }
                if (intent.hasExtra(GravityBoxSettings.EXTRA_RECENTS_MARGIN_BOTTOM)) {
                    mMarginBottomPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                            intent.getIntExtra(GravityBoxSettings.EXTRA_RECENTS_MARGIN_BOTTOM, 50),
                            context.getResources().getDisplayMetrics());
                    updateButtonLayout();
                    updateRamBarLayout();
                }
            }
            if (intent.getAction().equals(ModHwKeys.ACTION_RECENTS_CLEAR_ALL_SINGLETAP)) {
                clearAll();
            }
        }
    };

    public static void init(final XSharedPreferences prefs, ClassLoader classLoader) {
        try {
            Class<?> recentActivityClass = XposedHelpers.findClass(CLASS_RECENT_ACTIVITY, classLoader);

            mButtonGravity = Integer.valueOf(prefs.getString(GravityBoxSettings.PREF_KEY_RECENTS_CLEAR_ALL, "53"));
            mRamBarGravity = Integer.valueOf(prefs.getString(GravityBoxSettings.PREF_KEY_RAMBAR, "0"));
            mNavbarLeftHanded = prefs.getBoolean(GravityBoxSettings.PREF_KEY_NAVBAR_OVERRIDE, false) &&
                    prefs.getBoolean(GravityBoxSettings.PREF_KEY_NAVBAR_ENABLE, false) &&
                    prefs.getBoolean(GravityBoxSettings.PREF_KEY_NAVBAR_LEFT_HANDED, false);
            mMemInfoReader = new MemInfoReader();

            XposedHelpers.findAndHookMethod(recentActivityClass, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                    Activity activity = (Activity) param.thisObject;
                    mGbContext = activity.createPackageContext(GravityBox.PACKAGE_NAME, Context.CONTEXT_IGNORE_SECURITY);
                    mHandler = new Handler();
                    mAm = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
                    mRecentsView = (ViewGroup) XposedHelpers.getObjectField(param.thisObject, "mRecentsView");

                    final Resources res = activity.getResources();

                    mMarginTopPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 
                            prefs.getInt(GravityBoxSettings.PREF_KEY_RECENTS_CLEAR_MARGIN_TOP, 77), 
                            res.getDisplayMetrics());
                    mMarginBottomPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 
                            prefs.getInt(GravityBoxSettings.PREF_KEY_RECENTS_CLEAR_MARGIN_BOTTOM, 50), 
                            res.getDisplayMetrics());

                    mRamUsageBarPaddings = new int[4];
                    mRamUsageBarPaddings[0] = mRamUsageBarPaddings[2] = (int) TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_DIP, 4, res.getDisplayMetrics());
                    mRamUsageBarPaddings[1] = mRamUsageBarPaddings[3] = (int) TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_DIP, 1, res.getDisplayMetrics());
                    mClearAllRecentsSizePx = (int) TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_DIP, 50, res.getDisplayMetrics());
                    mRamUsageBarVerticalMargin = (int) TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_DIP, 15, res.getDisplayMetrics());
                    mRamUsageBarHorizontalMargin = (int) TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_DIP, 10, res.getDisplayMetrics());

                    FrameLayout vg = (FrameLayout) activity.getWindow().getDecorView()
                            .findViewById(android.R.id.content);

                    // create and inject new ImageView and set onClick listener to handle action
                    mRecentsClearButton = new ImageView(vg.getContext());
                    mRecentsClearButton.setImageDrawable(mGbContext.getResources().getDrawable(
                            R.drawable.ic_recent_clear));
                    mRecentsClearButton.setBackground(mGbContext.getResources().getDrawable(
                            R.drawable.image_view_button_bg)); 
                    FrameLayout.LayoutParams lParams = new FrameLayout.LayoutParams(
                            mClearAllRecentsSizePx, mClearAllRecentsSizePx);
                    mRecentsClearButton.setLayoutParams(lParams);
                    mRecentsClearButton.setScaleType(ScaleType.CENTER);
                    mRecentsClearButton.setClickable(true);
                    mRecentsClearButton.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                           clearAll();
                        }
                    });
                    vg.addView(mRecentsClearButton);
                    mRecentsClearButton.setVisibility(View.GONE);
                    updateButtonLayout();
                    if (DEBUG) log("clearAllButton ImageView injected");

                    // create and inject RAM bar
                    mRamUsageBar = new LinearColorBar(vg.getContext(), null);
                    mRamUsageBar.setOrientation(LinearLayout.HORIZONTAL);
                    mRamUsageBar.setClipChildren(false);
                    mRamUsageBar.setClipToPadding(false);
                    mRamUsageBar.setPadding(mRamUsageBarPaddings[0], mRamUsageBarPaddings[1],
                            mRamUsageBarPaddings[2], mRamUsageBarPaddings[3]);
                    FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
                    mRamUsageBar.setLayoutParams(flp);
                    LayoutInflater inflater = LayoutInflater.from(mGbContext);
                    inflater.inflate(R.layout.linear_color_bar, mRamUsageBar, true);
                    vg.addView(mRamUsageBar);
                    mForegroundProcessText = (TextView) mRamUsageBar.findViewById(R.id.foregroundText);
                    mBackgroundProcessText = (TextView) mRamUsageBar.findViewById(R.id.backgroundText);
                    mRamUsageBar.setVisibility(View.GONE);
                    updateRamBarLayout();
                    if (DEBUG) log("RAM bar injected");

                    IntentFilter intentFilter = new IntentFilter();
                    intentFilter.addAction(GravityBoxSettings.ACTION_PREF_RECENTS_CHANGED);
                    intentFilter.addAction(ModHwKeys.ACTION_RECENTS_CLEAR_ALL_SINGLETAP);
                    activity.registerReceiver(mBroadcastReceiver, intentFilter);
                    if (DEBUG) log("Recents panel view constructed");
                }
            });

            XposedHelpers.findAndHookMethod(recentActivityClass, "onDestroy", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {
                    ((Activity)param.thisObject).unregisterReceiver(mBroadcastReceiver);
                }
            });

            XposedHelpers.findAndHookMethod(recentActivityClass, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                    Object config = XposedHelpers.getObjectField(param.thisObject, "mConfig");
                    boolean hasTasks = !XposedHelpers.getBooleanField(config, "launchedWithNoRecentTasks");
                    if (mRecentsClearButton != null) {
                        boolean visible = mButtonGravity != 0 && mButtonGravity != 1 && hasTasks;
                        mRecentsClearButton.setVisibility(visible ? View.VISIBLE : View.GONE);
                    }
                    if (mRamUsageBar != null) {
                        if (mRamBarGravity != 0) {
                            mRamUsageBar.setVisibility(View.VISIBLE);
                            updateRamBarLayout();
                            updateRamBarMemoryUsage();
                        } else {
                            mRamUsageBar.setVisibility(View.GONE);
                        }
                    }
                    if (mButtonGravity == GravityBoxSettings.RECENT_CLEAR_NAVIGATION_BAR && hasTasks) {
                        setRecentsClearAll(true, (Context)param.thisObject);
                    }
                }
            });

            XposedHelpers.findAndHookMethod(recentActivityClass, "onStop", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {
                    setRecentsClearAll(false, (Context)param.thisObject);
                }
            });

            // When to update RAM bar values
            XposedHelpers.findAndHookMethod(CLASS_SWIPE_HELPER, classLoader, "dismissChild", 
                    View.class, float.class, updateRambarHook);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static XC_MethodHook updateRambarHook = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
            updateRamBarMemoryUsage();
        }
    };

    private static void setRecentsClearAll(Boolean show, Context context) {
        ModNavigationBar.setRecentAlt(show);
        ModPieControls.setRecentAlt(show);
    }

    private static void updateButtonLayout() {
        if (mRecentsClearButton == null || mButtonGravity == GravityBoxSettings.RECENT_CLEAR_OFF || 
                mButtonGravity == GravityBoxSettings.RECENT_CLEAR_NAVIGATION_BAR) return;

        final Context context = mRecentsClearButton.getContext();
        final Resources res = mRecentsClearButton.getResources();
        final int orientation = res.getConfiguration().orientation;
        FrameLayout.LayoutParams lparams = 
                (FrameLayout.LayoutParams) mRecentsClearButton.getLayoutParams();
        lparams.gravity = mButtonGravity;
        if (mButtonGravity == 51 || mButtonGravity == 53) { 
            int gravityForNavbarPosition = mNavbarLeftHanded ? 51 : 53;
            int marginRight = (mButtonGravity == gravityForNavbarPosition && 
                    orientation == Configuration.ORIENTATION_LANDSCAPE && 
                    Utils.isPhoneUI(context)) ? mMarginBottomPx : 0;
            lparams.setMargins(mNavbarLeftHanded ? marginRight : 0, mMarginTopPx,
                    mNavbarLeftHanded ? marginRight : 0, 0);
        } else {
            int gravityForNavbarPosition = mNavbarLeftHanded ? 83 : 85;
            int marginBottom = (orientation == Configuration.ORIENTATION_PORTRAIT || 
                                    !Utils.isPhoneUI(context)) ? mMarginBottomPx : 0;
            int marginRight = (mButtonGravity == gravityForNavbarPosition && 
                    orientation == Configuration.ORIENTATION_LANDSCAPE && 
                    Utils.isPhoneUI(context)) ? mMarginBottomPx : 0;
            lparams.setMargins(mNavbarLeftHanded ? marginRight : 0, 0, 
                    mNavbarLeftHanded ? marginRight : 0, marginBottom);
        }
        mRecentsClearButton.setLayoutParams(lparams);
        if (DEBUG) log("Clear all recents button layout updated");
    }

    private static void updateRamBarLayout() {
        if (mRamUsageBar == null || mRamBarGravity == 0) return;

        final Context context = mRamUsageBar.getContext();
        final Resources res = mRamUsageBar.getResources();
        final int orientation = res.getConfiguration().orientation;
        final boolean caOnTop = (mButtonGravity & Gravity.TOP) == Gravity.TOP;
        final boolean caOnLeft = (mButtonGravity & Gravity.LEFT) == Gravity.LEFT;
        final boolean rbOnTop = (mRamBarGravity == Gravity.TOP);
        final boolean sibling = (mRecentsClearButton != null &&
                mRecentsClearButton.getVisibility() == View.VISIBLE) &&
                ((caOnTop && rbOnTop) || (!caOnTop && !rbOnTop));
        final int marginTop = rbOnTop ? mMarginTopPx : 0;
        final int marginBottom = (!rbOnTop && (orientation == Configuration.ORIENTATION_PORTRAIT ||
                                                !Utils.isPhoneUI(context))) ? mMarginBottomPx : 0;
        final int marginLeft = orientation == Configuration.ORIENTATION_LANDSCAPE && 
                Utils.isPhoneUI(context) & mNavbarLeftHanded ? mMarginBottomPx : 0;
        final int marginRight = orientation == Configuration.ORIENTATION_LANDSCAPE && 
                Utils.isPhoneUI(context) & !mNavbarLeftHanded ? mMarginBottomPx : 0;

        FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) mRamUsageBar.getLayoutParams();
        flp.gravity = mRamBarGravity;
        flp.setMargins(
            sibling && caOnLeft ? (mClearAllRecentsSizePx + marginLeft) : 
                (mRamUsageBarHorizontalMargin + marginLeft), 
            rbOnTop ? (mRamUsageBarVerticalMargin + marginTop) : 0, 
            sibling && !caOnLeft ? (mClearAllRecentsSizePx + marginRight) : 
                (mRamUsageBarHorizontalMargin + marginRight), 
            rbOnTop ? 0 : (mRamUsageBarVerticalMargin + marginBottom)
        );
        mRamUsageBar.setLayoutParams(flp);
        if (DEBUG) log("RAM bar layout updated");
    }

    private static void updateRamBarMemoryUsage() {
        if (mRamUsageBar != null && mRamBarGravity != 0 && mHandler != null) {
            mHandler.post(updateRamBarTask);
        }
    }

    private static final Runnable updateRamBarTask = new Runnable() {
        @Override
        public void run() {
            if (mRamUsageBar == null || mRamUsageBar.getVisibility() == View.GONE) {
                return;
            }

            ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
            mAm.getMemoryInfo(memInfo);
            long secServerMem = 0;//XposedHelpers.getLongField(memInfo, "secondaryServerThreshold");
            mMemInfoReader.readMemInfo();
            long availMem = mMemInfoReader.getFreeSize() + mMemInfoReader.getCachedSize() -
                    secServerMem;
            long totalMem = mMemInfoReader.getTotalSize();

            String sizeStr = Formatter.formatShortFileSize(mGbContext, totalMem-availMem);
            mForegroundProcessText.setText(mGbContext.getResources().getString(
                    R.string.service_foreground_processes, sizeStr));
            sizeStr = Formatter.formatShortFileSize(mGbContext, availMem);
            mBackgroundProcessText.setText(mGbContext.getResources().getString(
                    R.string.service_background_processes, sizeStr));

            float fTotalMem = totalMem;
            float fAvailMem = availMem;
            mRamUsageBar.setRatios((fTotalMem - fAvailMem) / fTotalMem, 0, 0);
            if (DEBUG) log("RAM bar values updated");
        }
    };

    private static final void clearAll() {
        if (mRecentsView == null) return;

        try {
            int childCount = mRecentsView.getChildCount();
            for (int i = 0; i < childCount; i++) {
                final View child = mRecentsView.getChildAt(i);
                if (child.getClass().getName().equals(CLASS_TASK_STACK_VIEW)) {
                    Object stack = XposedHelpers.getObjectField(child, "mStack");
                    clearStack(child, stack);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static final void clearStack(final Object stackView, Object stack) {
        final ArrayList<?> tasks = (ArrayList<?>) XposedHelpers.callMethod(stack, "getTasks");
        final int count = tasks.size();
        for (int i = 0; i < count; i++) {
            Object task = tasks.get(i);
            final Object taskView = XposedHelpers.callMethod(stackView,
                    "getChildViewForTask", task);
            if (taskView != null) {
                XposedHelpers.callMethod(taskView, "dismissTask");
            }
        }
    }
}
