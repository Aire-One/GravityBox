/*
 * Copyright (C) 2013 Peter Gregus for GravityBox Project (C3C076@xda)
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

package com.ceco.gm2.gravitybox.quicksettings;

import java.io.File;
import java.io.IOException;

import com.ceco.gm2.gravitybox.GravityBoxSettings;
import com.ceco.gm2.gravitybox.R;
import com.ceco.gm2.gravitybox.RecordingService;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.os.Handler;
import android.view.View;

public class QuickRecordTile extends BasicTile {
    private static final String TAG = "GB:QuickRecordTile";
    private static final boolean DEBUG = false;

    private static final int STATE_IDLE = 0;
    private static final int STATE_PLAYING = 1;
    private static final int STATE_RECORDING = 2;
    private static final int STATE_JUST_RECORDED = 3;
    private static final int STATE_NO_RECORDING = 4;

    private String mAudioFileName;
    private int mRecordingState = STATE_IDLE;
    private MediaPlayer mPlayer;
    private Handler mHandler;
    private int mAudioQuality;
    private long mAutoStopDelay;

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    public QuickRecordTile(Context context, Context gbContext, Object statusBar, Object panelBar) {
        super(context, gbContext, statusBar, panelBar);

        mSupportsHideOnChange = false;
        mHandler = new Handler();

        mOnClick = new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                if (mAudioFileName == null) {
                    mRecordingState = STATE_NO_RECORDING;
                } else {
                    File file = new File(mAudioFileName);
                    if (!file.exists()) {
                        mRecordingState = STATE_NO_RECORDING;
                    }
                }

                switch (mRecordingState) {
                    case STATE_RECORDING:
                        stopRecording();
                        break;
                    case STATE_NO_RECORDING:
                        return;
                    case STATE_IDLE:
                    case STATE_JUST_RECORDED:
                        startPlaying();
                        break;
                    case STATE_PLAYING:
                        stopPlaying();
                        break;
                }

            }
        };

        mOnLongClick = new View.OnLongClickListener() {

            @Override
            public boolean onLongClick(View v) {
                switch (mRecordingState) {
                    case STATE_NO_RECORDING:
                    case STATE_IDLE:
                    case STATE_JUST_RECORDED:
                        startRecording();
                        break;
                }
                return true;
            }
        };
    }

    @Override
    protected void onPreferenceInitialize(XSharedPreferences prefs) {
        super.onPreferenceInitialize(prefs);

        mAudioQuality = Integer.valueOf(prefs.getString(GravityBoxSettings.PREF_KEY_QUICKRECORD_QUALITY,
                String.valueOf(RecordingService.DEFAULT_SAMPLING_RATE)));
        mAutoStopDelay = prefs.getInt(GravityBoxSettings.PREF_KEY_QUICKRECORD_AUTOSTOP, 1) * 3600000;
    }

    @Override
    public void onBroadcastReceived(Context context, Intent intent) {
        super.onBroadcastReceived(context, intent);

        if (intent.getAction().equals(GravityBoxSettings.ACTION_PREF_QUICKSETTINGS_CHANGED)) {
            if (intent.hasExtra(GravityBoxSettings.EXTRA_QR_QUALITY)) {
                mAudioQuality = intent.getIntExtra(GravityBoxSettings.EXTRA_QR_QUALITY,
                        RecordingService.DEFAULT_SAMPLING_RATE);
            }
            if (intent.hasExtra(GravityBoxSettings.EXTRA_QR_AUTOSTOP)) {
                mAutoStopDelay = intent.getIntExtra(GravityBoxSettings.EXTRA_QR_AUTOSTOP, 1) * 3600000;
            }
        } else if (intent.getAction().equals(RecordingService.ACTION_RECORDING_STATUS_CHANGED) &&
                intent.hasExtra(RecordingService.EXTRA_RECORDING_STATUS)) {
            int recordingStatus = intent.getIntExtra(
                    RecordingService.EXTRA_RECORDING_STATUS, RecordingService.RECORDING_STATUS_IDLE);
            if (DEBUG) log("Broadcast received: recordingStatus = " + recordingStatus);
            switch (recordingStatus) {
                case RecordingService.RECORDING_STATUS_IDLE:
                    mRecordingState = STATE_IDLE;
                    mHandler.removeCallbacks(autoStopRecord);
                    break;
                case RecordingService.RECORDING_STATUS_STARTED:
                    mRecordingState = STATE_RECORDING;
                    mAudioFileName = intent.getStringExtra(RecordingService.EXTRA_AUDIO_FILENAME);
                    if (mAutoStopDelay > 0) {
                        mHandler.postDelayed(autoStopRecord, mAutoStopDelay);
                    }
                    if (DEBUG) log("Audio recording started");
                    break;
                case RecordingService.RECORDING_STATUS_STOPPED:
                    mRecordingState = STATE_JUST_RECORDED;
                    mHandler.removeCallbacks(autoStopRecord);
                    if (DEBUG) log("Audio recording stopped");
                    break;
                case RecordingService.RECORDING_STATUS_ERROR:
                default:
                    mRecordingState = STATE_NO_RECORDING;
                    mHandler.removeCallbacks(autoStopRecord);
                    String statusMessage = intent.getStringExtra(RecordingService.EXTRA_STATUS_MESSAGE);
                    log("Audio recording error: " + statusMessage);
                    break;
            }
            updateResources();
        }
    }

    @Override
    protected int onGetLayoutId() {
        return R.layout.quick_settings_tile_quickrecord;
    }

    @Override
    protected void updateTile() {
        final Resources res = mGbContext.getResources();

        if (mAudioFileName == null) {
            mRecordingState = STATE_NO_RECORDING;
        } else {
            File file = new File(mAudioFileName);
            if (!file.exists()) {
                mRecordingState = STATE_NO_RECORDING;
            }
        }

        switch (mRecordingState) {
            case STATE_PLAYING:
                mLabel = res.getString(R.string.quick_settings_qr_playing);
                mDrawableId = R.drawable.ic_qs_qr_playing;
                break;
            case STATE_RECORDING:
                mLabel = res.getString(R.string.quick_settings_qr_recording);
                mDrawableId = R.drawable.ic_qs_qr_recording;
                break;
            case STATE_JUST_RECORDED:
                mLabel = res.getString(R.string.quick_settings_qr_recorded);
                mDrawableId = R.drawable.ic_qs_qr_recorded;
                break;
            case STATE_NO_RECORDING:
                mLabel = res.getString(R.string.quick_settings_qr_record);
                mDrawableId = R.drawable.ic_qs_qr_record;
                break;
            case STATE_IDLE:
            default:
                mLabel = res.getString(R.string.qs_tile_quickrecord);
                mDrawableId = R.drawable.ic_qs_qr_record;
                break;
        }
        mTileColor = KK_COLOR_ON;

        super.updateTile();
    }

    final Runnable autoStopRecord = new Runnable() {
        public void run() {
            if (mRecordingState == STATE_RECORDING) {
                stopRecording();
            }
        }
    };

    final OnCompletionListener stoppedPlaying = new OnCompletionListener(){
        public void onCompletion(MediaPlayer mp) {
            mRecordingState = STATE_IDLE;
            updateResources();
        }
    };

    private void startPlaying() {
        mPlayer = new MediaPlayer();
        try {
            mPlayer.setDataSource(mAudioFileName);
            mPlayer.prepare();
            mPlayer.start();
            mRecordingState = STATE_PLAYING;
            updateResources();
            mPlayer.setOnCompletionListener(stoppedPlaying);
        } catch (IOException e) {
            log("startPlaying failed: " + e.getMessage());
        }
    }

    private void stopPlaying() {
        mPlayer.release();
        mPlayer = null;
        mRecordingState = STATE_IDLE;
        updateResources();
    }

    private void startRecording() {
        Intent si = new Intent(mGbContext, RecordingService.class);
        si.setAction(RecordingService.ACTION_RECORDING_START);
        si.putExtra(RecordingService.EXTRA_SAMPLING_RATE, mAudioQuality);
        mGbContext.startService(si);
    }

    private void stopRecording() {
        Intent si = new Intent(mGbContext, RecordingService.class);
        si.setAction(RecordingService.ACTION_RECORDING_STOP);
        mGbContext.startService(si);
    }
}
