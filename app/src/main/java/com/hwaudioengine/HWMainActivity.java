package com.hwaudioengine;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import com.huawei.multimedia.audiokit.config.ResultCode;
import com.huawei.multimedia.audiokit.interfaces.HwAudioKaraokeFeatureKit;
import com.huawei.multimedia.audiokit.interfaces.HwAudioKit;
import com.huawei.multimedia.audiokit.interfaces.IAudioKitCallback;
import com.huawei.multimedia.audiokit.utils.Constant;

import com.google.sample.oboe.manualtest.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class HWMainActivity extends Activity implements View.OnClickListener, IAudioKitCallback {
    private static final String TAG = "HWMainActivity";

    private static final String FEATURE_TYPE = " HWAUDIO_FEATURE_KARAOKE";

    private String MEDIA_RECORD_FILE_NAME;

    private String AUDIO_RECORD_FILE_NAME;

    private String OPENSLES_RECORD_FILE_NAME;

    private static final String[] PERMISSIONS = {Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE};

    private static final int INTENT_FLAG = 123;

    private static final int INIT_VOLUME = 50;

    private static final String CONNECT_INTENT_KEY_STATE = "state";

    private static final String CONNECT_INTENT_KEY_MICROPHONE = "microphone";

    private static final int DEFAULT_INT_VALUE = 0;

    private static final int HEADSET_STATE_ON = 1;

    private static final int HEADSET_STATE_OFF = 0;

    // record mode
    private static final int RECORDING_MODE_MEDIARECORDER = 0;

    private static final int RECORDING_MODE_OPENSLES = 1;

    private static final int RECORDING_MODE_AUDIORECORD = 2;

    // audio record parameter
    private static final int SAMPLING_RATE_IN_HZ = 48000;

    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;

    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    /**
     * Factor by that the minimum buffer size is multiplied. The bigger the factor is the less
     * likely it is that samples will be dropped, but more memory will be used. The minimum buffer
     * size is determined by {@link AudioRecord#getMinBufferSize(int, int, int)} and depends on the
     * recording settings.
     */
    private static final int BUFFER_SIZE_FACTOR = 2;

    /**
     * Size of the buffer where the audio data is stored by Android
     */
    private static final int BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLING_RATE_IN_HZ,
            CHANNEL_CONFIG, AUDIO_FORMAT) * BUFFER_SIZE_FACTOR;

    // view and widget
    private Button mStartRecordButton;

    private Button mStopRecordButton;

    private Button mPlayRecordButton;

    private Button mStopPlayRecordButton;

    private Button mInitButton;

    private Button mGetFeaturesButton;

    private Button mIsFeatureSupportButton;

    private Button mInitKaraokeButton;

    private Button mIsKaraokeSupportButton;

    private Button mEnableKaraokeButton;

    private Button mDisableKaraokeButton;

    private Button mDefaultReverberationMode;

    private Button mKtvReverberationMode;

    private Button mTheaterReverberationMode;

    private Button mConcertReverberationMode;

    private EditText mEditTextSetVolume;

    private Button mSetVolume;

    private Button mDefaultEqualizerMode;

    private Button mFullEqualizerMode;

    private Button mBrightEqualizerMode;

    private Button mGetLatencyButton;

    private TextView mInfoTextView;

    private TextView mRecordingTimeTextView;

    private TextView mRecordingPathTextView;

    private Spinner mSpinner;

    private SeekBar mSeekBar;

    // play and record
    private MediaPlayer mMediaPlayer;

    private boolean mIsPlaying = false;

    private MediaRecorder mMediaRecorder = null;

    private AudioRecord mAudioRecorder = null;

    private Thread mAudioRecordingThread = null;

    /**
     * Signals whether a recording is in progress (true) or not (false).
     */
    private final AtomicBoolean mAudioRecordingInProgress = new AtomicBoolean(false);

    private File mRecordFile;

    private FileOutputStream mRecordFileOutput;

    private int mTimerCount = 0;


    // Audio Kit
    private HwAudioKit mHwAudioKit;

    private HwAudioKaraokeFeatureKit mHwAudioKaraokeFeatureKit;

    private String mResultType = "";

    private boolean mIsAudiokitBindSuccess = false;

    private boolean mIsAudiokitKaraokeBindSuccess = false;

    private AudioTrackThread mAudioTrackThread;

    // protects recording
    private final Object mRecordingLock = new Object();

    private boolean mIsRecording = false;

    private int mRecordingMode = RECORDING_MODE_MEDIARECORDER;

    private int mCurrentHeadsetState = HEADSET_STATE_OFF;

    private int mCurrentVolume = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        checkPermissions();

        MEDIA_RECORD_FILE_NAME = new File(getCacheDir(), "mediarecorder.3gp").getAbsolutePath();
        AUDIO_RECORD_FILE_NAME = new File(getCacheDir(), "audiorecord.wav").getAbsolutePath();
        OPENSLES_RECORD_FILE_NAME = new File(getCacheDir(),"opensles.wav").getAbsolutePath();

        setContentView(R.layout.layout_main);
        initView();
        setHeadSetState();

        // initialize native audio system
        createEngine();
    }

    @Override
    protected void onResume() {
        super.onResume();
        recordingShow();
        mStartRecordButton.setEnabled(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopRecord();
        stopPlayRecord();

        // we suggest stop Karaoke if not using record
        enableKaraoke(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestroy");
        if (mHwAudioKit != null) {
            mHwAudioKit.destroy();
        }
        if (mHwAudioKaraokeFeatureKit != null) {
            mHwAudioKaraokeFeatureKit.destroy();
        }

        if (mRecordingMode == RECORDING_MODE_OPENSLES) {
            shutdown();
        }

        unregisterReceiver(mIntentReceiver);
    }

    private void initView() {

        mSpinner = (Spinner) findViewById(R.id.recordMode);
        mSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view,
                                       int pos, long id) {

                mRecordingMode = pos;
                recordingShow();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Another interface callback
            }
        });

        mRecordingTimeTextView = findViewById(R.id.recordingTime);
        mRecordingPathTextView = findViewById(R.id.defaultRecordPath);

        mSeekBar = (SeekBar) findViewById(R.id.audio_seekBar);

        mInitButton = findViewById(R.id.init);
        mInitButton.setOnClickListener(this);

        mStartRecordButton = findViewById(R.id.startRecordButton);
        mStartRecordButton.setOnClickListener(this);

        mStopRecordButton = findViewById(R.id.stopRecordButton);
        mStopRecordButton.setOnClickListener(this);

        mPlayRecordButton = findViewById(R.id.playRecordButton);
        mPlayRecordButton.setOnClickListener(this);

        mStopPlayRecordButton = findViewById(R.id.stopPlayRecordButton);
        mStopPlayRecordButton.setOnClickListener(this);

        mGetFeaturesButton = findViewById(R.id.getFeaturesButton);
        mGetFeaturesButton.setOnClickListener(this);

        mIsFeatureSupportButton = findViewById(R.id.isFeatureSupportButton);
        mIsFeatureSupportButton.setOnClickListener(this);

        mInitKaraokeButton = findViewById(R.id.initKaraoke);
        mInitKaraokeButton.setOnClickListener(this);

        mIsKaraokeSupportButton = findViewById(R.id.isKaraokeSupportButton);
        mIsKaraokeSupportButton.setOnClickListener(this);

        mEnableKaraokeButton = findViewById(R.id.enableKaraokeButton);
        mEnableKaraokeButton.setOnClickListener(this);

        mDisableKaraokeButton = findViewById(R.id.disableKaraokeButton);
        mDisableKaraokeButton.setOnClickListener(this);

        mDefaultReverberationMode = findViewById(R.id.setDefaultReverberationMode);
        mDefaultReverberationMode.setOnClickListener(this);

        mKtvReverberationMode = findViewById(R.id.setKtvReverberationMode);
        mKtvReverberationMode.setOnClickListener(this);

        mTheaterReverberationMode = findViewById(R.id.setTheaterReverberationMode);
        mTheaterReverberationMode.setOnClickListener(this);

        mConcertReverberationMode = findViewById(R.id.setConcertReverberationMode);
        mConcertReverberationMode.setOnClickListener(this);

        mDefaultEqualizerMode = findViewById(R.id.setDefaultEqualizer);
        mDefaultEqualizerMode.setOnClickListener(this);

        mFullEqualizerMode = findViewById(R.id.setFullEqualizer);
        mFullEqualizerMode.setOnClickListener(this);

        mBrightEqualizerMode = findViewById(R.id.setBrightEqualizer);
        mBrightEqualizerMode.setOnClickListener(this);

        mEditTextSetVolume = findViewById(R.id.setKaraokeAudioVolume);

        mSetVolume = findViewById(R.id.settingVolume);
        mSetVolume.setOnClickListener(this);

        mGetLatencyButton = findViewById(R.id.getKaraokeLatencyButton);
        mGetLatencyButton.setOnClickListener(this);

        mInfoTextView = findViewById(R.id.infoTextView);
    }


    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.i(TAG, "action received: " + action);
            if (Intent.ACTION_HEADSET_PLUG.equals(action)) {
                int state = intent.getIntExtra(CONNECT_INTENT_KEY_STATE, DEFAULT_INT_VALUE);
                int microphone = intent.getIntExtra(CONNECT_INTENT_KEY_MICROPHONE, DEFAULT_INT_VALUE);
                Log.i(TAG, "headset plug action, state= " + state + ", mic= " + microphone);
                if (mIsRecording) {
                    if (mCurrentHeadsetState == HEADSET_STATE_ON && state == HEADSET_STATE_OFF) {
                        // now we need mute something
                        setVolume(0);
                    }
                    if (mCurrentHeadsetState == HEADSET_STATE_OFF && state == HEADSET_STATE_ON) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        setVolume(mCurrentVolume);
                    }
                }
                mCurrentHeadsetState = state;
            }
        }
    };

    private void setHeadSetState() {
        IntentFilter filter = new IntentFilter(Intent.ACTION_HEADSET_PLUG);
        registerReceiver(mIntentReceiver, filter);
    }

    private void checkPermissions() {
        if (!hasPermission()) {
            startRequestPermission();
        }
    }

    private boolean hasPermission() {
        for (String permission : PERMISSIONS) {
            int result = ContextCompat.checkSelfPermission(this, permission);
            if (result != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void startRequestPermission() {
        ActivityCompat.requestPermissions(this, PERMISSIONS, INTENT_FLAG);
    }

    @Override
    public void onClick(View view) {
        mResultType = "";
        onClickPlayer(view);
        onClickParameter(view);
        switch (view.getId()) {
            case R.id.init:
                initAudioKit();
                break;
            case R.id.initKaraoke:
                initKaraokeFeature();
                break;
            case R.id.getFeaturesButton:
                getFeatures();
                break;
            case R.id.isFeatureSupportButton:
                testFeatureSupport(HwAudioKit.FeatureType.HWAUDIO_FEATURE_KARAOKE);
                break;
            case R.id.isKaraokeSupportButton:
                testKaraokeSupport();
                break;
            case R.id.enableKaraokeButton:
                enableKaraoke(true);
                break;
            case R.id.disableKaraokeButton:
                enableKaraoke(false);
                break;
            case R.id.getKaraokeLatencyButton:
                getLatency();
                break;
            default:
                break;
        }
    }

    private void onClickPlayer(View view) {
        switch (view.getId()) {
            case R.id.startRecordButton:
                startRecord();
                break;
            case R.id.stopRecordButton:
                stopRecord();
                break;
            case R.id.playRecordButton:
                playRecord();
                break;
            case R.id.stopPlayRecordButton:
                stopPlayRecord();
                break;
            default:
                break;
        }
    }

    private void onClickParameter(View view) {
        switch (view.getId()) {
            case R.id.setDefaultReverberationMode:
                setReverberation(Constant.REVERB_EFFECT_MODE_ORIGINAL);
                break;
            case R.id.setKtvReverberationMode:
                setReverberation(Constant.REVERB_EFFECT_MODE_KTV);
                break;
            case R.id.setTheaterReverberationMode:
                setReverberation(Constant.REVERB_EFFECT_MODE_THEATRE);
                break;
            case R.id.setConcertReverberationMode:
                setReverberation(Constant.REVERB_EFFECT_MODE_CONCERT);
                break;
            case R.id.settingVolume:
                String volume = mEditTextSetVolume.getText().toString().trim();
                if (!volume.isEmpty()) {
                    try {
                        setVolume(Integer.valueOf(volume));
                        mCurrentVolume = Integer.valueOf(volume);
                    } catch (NumberFormatException ex) {
                        Log.e(TAG, "NumberFormatException, ex = NumberFormatException");
                    }
                } else {
                    setVolume(INIT_VOLUME);
                }
                break;
            case R.id.setDefaultEqualizer:
                setEqualizer(Constant.EQUALIZER_MODE_DEFAULT);
                break;
            case R.id.setFullEqualizer:
                setEqualizer(Constant.EQUALIZER_MODE_FULL);
                break;
            case R.id.setBrightEqualizer:
                setEqualizer(Constant.EQUALIZER_MODE_BRIGHT);
                break;
            default:
                break;
        }
    }

    /***********************************************************************************************
     below is Record & Play sample code
     ***********************************************************************************************/

    private String recordingShow() {
        String display = "none";
        String recordPath = "none";

        switch (mRecordingMode) {
            case RECORDING_MODE_OPENSLES:
                display = mIsRecording ? getResources().getString(R.string.OpenSLESRecording)
                        : getResources().getString(R.string.OpenSLESRecord);
                recordPath = OPENSLES_RECORD_FILE_NAME;
                break;

            case RECORDING_MODE_MEDIARECORDER:
                display = mIsRecording ? getResources().getString(R.string.MediaRecording)
                        : getResources().getString(R.string.MediaRecorder);
                recordPath = MEDIA_RECORD_FILE_NAME;
                break;

            case RECORDING_MODE_AUDIORECORD:
                display = mIsRecording ? getResources().getString(R.string.AudioRecording)
                        : getResources().getString(R.string.AudioRecord);
                recordPath = AUDIO_RECORD_FILE_NAME;
                break;

            default:
                break;
        }

        Log.d(TAG, "recordPath " + recordPath);
        mInfoTextView.setText(display);
        mRecordingPathTextView.setText(recordPath);
        return display;
    }


    public String showTimeCount(long time) {
        String s = null;
        if (time <= 59){
            s ="00:";
            return time < 10 ? s + "0"+ time : s + time;
        } else {
            return (time % 60 < 10 ? s + "0" + time : s + time)
                    + ":" + (time / 60 < 10 ? s + "0" + time : s + time);
        }
    }

    void enableRecordModeSelect(boolean enable) {
        if (mSpinner != null) {
            mSpinner.setEnabled(enable);

            if (!enable) {
                recordingShow();
            }
        }
    }

    private void showRecordingTime() {
        mTimerCount = 0;

        final Handler handler = new Handler();
        final Runnable runnable= new Runnable() {
            @Override
            public void run() {
                if (mIsRecording) {
                    String str = showTimeCount((long) mTimerCount);
                    mRecordingTimeTextView.setText(str);
                    mTimerCount++;
                    handler.postDelayed(this, 1000);
                }
            }
        };
        runnable.run();
    }

    private void startOpenslesRecord() {

        mRecordFile = new File(OPENSLES_RECORD_FILE_NAME);
        if (mRecordFile.exists()) {
            mRecordFile.delete();
            Log.i(TAG, "delete old file");
        }
        createOpenSLESAudioRecorder(mRecordFile.getAbsolutePath());
        startOpenSLESRecording();
        showRecordingTime();

        // need for karaoke if no background music is running
        startAudioTrackThread();
    }

    private void startMediaRecord() {

        // if already created, just return
        if (mMediaRecorder != null) {
            Log.i(TAG, "already created record");
            return;
        }

        try {
            mMediaRecorder = new MediaRecorder();
            mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mRecordFile = new File(MEDIA_RECORD_FILE_NAME);
            if (mRecordFile.exists()) {
                mRecordFile.delete();
                Log.i(TAG, "delete old file");
            }
            mMediaRecorder.setOutputFile(mRecordFile.getCanonicalPath());
            mMediaRecorder.prepare();
            mMediaRecorder.start();
            mIsRecording = true;
            showRecordingTime();

            // need for karaoke if no background music is running
            startAudioTrackThread();
        } catch (IOException | IllegalStateException e) {
            Log.e(TAG, "startMediaRecord, Exception is IOException " + e);
            mIsRecording = false;
        }
    }


    private class RecordingRunnable implements Runnable {

        @Override
        public void run() {
            final ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);

            try {
                while (mAudioRecordingInProgress.get()) {
                    int result = mAudioRecorder.read(buffer, BUFFER_SIZE);
                    if (result < 0) {
                        throw new RuntimeException("Reading of audio buffer failed: " +
                                getBufferReadFailureReason(result));
                    }
                    mRecordFileOutput.write(buffer.array(), 0, BUFFER_SIZE);
                    buffer.clear();
                }
            } catch (IOException e) {
                throw new RuntimeException("Writing of recorded audio failed", e);
            }
        }

        private String getBufferReadFailureReason(int errorCode) {
            switch (errorCode) {
                case AudioRecord.ERROR_INVALID_OPERATION:
                    return "ERROR_INVALID_OPERATION";
                case AudioRecord.ERROR_BAD_VALUE:
                    return "ERROR_BAD_VALUE";
                case AudioRecord.ERROR_DEAD_OBJECT:
                    return "ERROR_DEAD_OBJECT";
                case AudioRecord.ERROR:
                    return "ERROR";
                default:
                    return "Unknown (" + errorCode + ")";
            }
        }
    }

    private void startAudioRecord() {

        mRecordFile = new File(AUDIO_RECORD_FILE_NAME);
        if (mRecordFile.exists()) {
            mRecordFile.delete();
            Log.i(TAG, "delete old file");
        }


        try {
            mRecordFileOutput = new FileOutputStream(mRecordFile);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        mAudioRecorder = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, SAMPLING_RATE_IN_HZ,
                CHANNEL_CONFIG, AUDIO_FORMAT, BUFFER_SIZE);
        mAudioRecorder.startRecording();

        mAudioRecordingInProgress.set(true);

        mAudioRecordingThread = new Thread(new RecordingRunnable(), "Recording Thread");
        mAudioRecordingThread.start();

        showRecordingTime();
    }


    private void startRecord() {
        if (!hasPermission()) {
            startRequestPermission();
            return;
        }

        recordingShow();
        mStartRecordButton.setEnabled(false);
        enableRecordModeSelect(false);

        synchronized (mRecordingLock) {
            if (mIsRecording) {
                Log.i(TAG, "already recording");
                return;
            }

            switch (mRecordingMode) {

                case RECORDING_MODE_OPENSLES:
                    mIsRecording = true;
                    startOpenslesRecord();
                    break;

                case RECORDING_MODE_MEDIARECORDER:
                    startMediaRecord();
                    break;

                case RECORDING_MODE_AUDIORECORD:
                    mIsRecording = true;
                    startAudioRecord();
                    break;

                default:
                    Log.d(TAG, "error recording mode");
                    break;
            }

            try {
                mRecordingPathTextView.setText(mRecordFile.getCanonicalPath());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }


    /**
     * stop recording entrance
     */
    private void stopRecord() {
        Log.i(TAG, "stopRecord");

        mInfoTextView.setText(getResources().getString(R.string.stopRecord));
        mStartRecordButton.setEnabled(true);
        enableRecordModeSelect(true);

        synchronized (mRecordingLock) {

            switch (mRecordingMode) {

                case RECORDING_MODE_OPENSLES:
                    stopOpenSLESRecording();
                    break;

                case RECORDING_MODE_MEDIARECORDER:
                    if (mMediaRecorder != null && mIsRecording) {
                        try {
                            mIsRecording = false;
                            stopAudioTrackThread();
                            mMediaRecorder.pause();
                            mMediaRecorder.stop();
                            mMediaRecorder.release();
                            mMediaRecorder = null;
                        } catch (IllegalStateException e) {
                            Log.e(TAG, "stopRecord(), IllegalStateException");
                        }
                    } else {
                        Log.i(TAG, "stopRecord, mMediaRecorder is null, mIsRecording " + mIsRecording);
                    }
                    break;

                case RECORDING_MODE_AUDIORECORD:
                    if (null == mAudioRecorder) {
                        return;
                    }

                    mAudioRecordingInProgress.set(false);
                    mAudioRecorder.stop();
                    mAudioRecorder.release();
                    mAudioRecorder = null;
                    mAudioRecordingThread = null;

                    writeRecordFile(AUDIO_RECORD_FILE_NAME, mRecordFileOutput);
                    break;

                default:
                    Log.d(TAG, "error recording mode");
                    break;
            }

            mIsRecording = false;
        }
    }


    private byte[] writeWavHeader(long totalAudioLen, int format, int longSampleRate, int channelMask) {

        byte bitsPerSample = 16;
        switch (format) {
            case AudioFormat.ENCODING_PCM_FLOAT:
                bitsPerSample = 32;
                break;
            case AudioFormat.ENCODING_PCM_8BIT:
                bitsPerSample = 8;
                break;
            case AudioFormat.ENCODING_PCM_16BIT:
                bitsPerSample = 16;
                break;
            default:
                break;

        }

        int channelCount = Integer.bitCount(channelMask);

        byte[] header = new byte[44];

        long totalDataLen = totalAudioLen + 36; // 36 means header length
        int byteRate = bitsPerSample * longSampleRate * channelCount / 8; // BIT_PER_SAMPLE * SAMPLE_RATE * channels / BITS_ONE_BYTE;
        header[0] = 'R';  // RIFF/WAVE header
        header[1] = 'I';
        header[2] = 'F';
        header[3] = 'F';
        header[4] = (byte) (totalDataLen & 0xff);
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        header[8] = 'W';
        header[9] = 'A';
        header[10] = 'V';
        header[11] = 'E';
        header[12] = 'f';  // 'fmt ' chunk
        header[13] = 'm';
        header[14] = 't';
        header[15] = ' ';
        header[16] = 16;  // 4 bytes: size of 'fmt ' chunk
        header[17] = 0;
        header[18] = 0;
        header[19] = 0;
        header[20] = 1;  // format = 1
        header[21] = 0;
        header[22] = (byte) channelCount; // channel
        header[23] = 0;
        header[24] = (byte) (longSampleRate & 0xff);
        header[25] = (byte) ((longSampleRate >> 8) & 0xff);
        header[26] = (byte) ((longSampleRate >> 16) & 0xff);
        header[27] = (byte) ((longSampleRate >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = (byte) (2 * 16 / 8);  // block align
        header[33] = 0;
        header[34] = bitsPerSample;  // bits per sample
        header[35] = 0;
        header[36] = 'd';
        header[37] = 'a';
        header[38] = 't';
        header[39] = 'a';
        header[40] = (byte) (totalAudioLen & 0xff);
        header[41] = (byte) ((totalAudioLen >> 8) & 0xff);
        header[42] = (byte) ((totalAudioLen >> 16) & 0xff);
        header[43] = (byte) ((totalAudioLen >> 24) & 0xff);

        return header;
    }

    private void writeRecordFile(String path, FileOutputStream fileOutput) {

        long totalAudioLen = 0x1234;

        // out file
        try {
            totalAudioLen = fileOutput.getChannel().size();
            Log.d(TAG, "fileOutput totalAudioLen = " + totalAudioLen
                    + ", fileOutput.getFD()) " + fileOutput.getFD());

            byte[] bytes = new byte[(int) totalAudioLen];
            FileInputStream fileInputStream = new FileInputStream(path);
            fileInputStream.read(bytes);
            fileInputStream.close();

            byte[] header = writeWavHeader(totalAudioLen, AUDIO_FORMAT, SAMPLING_RATE_IN_HZ, CHANNEL_CONFIG);

            File recordFile2 = new File(path + "_tmp");
            if (recordFile2.exists()) {
                recordFile2.delete();
                Log.i(TAG, "delete old file");
            }
            File recordFileold = new File(path);
            FileOutputStream fileTempOutput = new FileOutputStream(recordFile2, true);
            fileTempOutput.write(header, 0, header.length);
            fileTempOutput.write(bytes);
            recordFile2.renameTo(recordFileold);
            fileTempOutput.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }


    /**
     * play recording entrance
     */
    private void playRecord() {
        if (!hasPermission()) {
            startRequestPermission();
            return;
        }

        if ((mRecordFile == null) || !mRecordFile.exists()) {
            Log.i(TAG, "mRecordFile not exists ");
            return;
        }

        String recordPath = null;
        try {
            recordPath = mRecordFile.getCanonicalPath();
            Log.i(TAG, "playRecord " + recordPath);
            mRecordingPathTextView.setText(recordPath);
        } catch (IOException e) {
            e.printStackTrace();
        }

        switch (mRecordingMode) {

            case RECORDING_MODE_OPENSLES:
                createUriAudioPlayer(recordPath);
                setPlayingUriAudioPlayer(true);
                mIsPlaying = true;

                break;

            case RECORDING_MODE_MEDIARECORDER:
            case RECORDING_MODE_AUDIORECORD:

                if (mMediaPlayer != null || mIsPlaying == true) {
                    Log.i(TAG, "mMediaPlayer not null or mIsPlaying");
                    return;
                }

                try {
                    mMediaPlayer = new MediaPlayer();
                    mMediaPlayer.setDataSource(mRecordFile.getAbsolutePath());
                    mMediaPlayer.prepareAsync();
                    mMediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                        @Override
                        public void onPrepared(MediaPlayer mp) {
                            Log.i(TAG, "prepare completely");
                            mMediaPlayer.start();
                            mMediaPlayer.seekTo(0);
                            mSeekBar.setMax(mMediaPlayer.getDuration());
                            new Thread() {

                                @Override
                                public void run() {
                                    try {
                                        mIsPlaying = true;
                                        while (mIsPlaying) {
                                            int current = mMediaPlayer.getCurrentPosition();
                                            mSeekBar.setProgress(current);
                                            sleep(20);
                                        }
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                }
                            }.start();

                            mPlayRecordButton.setEnabled(false);
                        }
                    });
                    mMediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {

                        @Override
                        public void onCompletion(MediaPlayer mp) {
                            mMediaPlayer.seekTo(0);
                            mIsPlaying = false;
                            mMediaPlayer.release();
                            mMediaPlayer = null;
                            mPlayRecordButton.setEnabled(true);
                        }
                    });

                    mMediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {

                        @Override
                        public boolean onError(MediaPlayer mp, int what, int extra) {
                            Log.e(TAG, "playing error");
                            mMediaPlayer.stop();
                            mMediaPlayer.release();
                            mMediaPlayer = null;
                            mPlayRecordButton.setEnabled(true);
                            mIsPlaying = false;
                            return false;
                        }
                    });

                    Thread.sleep(100);
                    mMediaPlayer.start();
                } catch (IOException  | InterruptedException e) {
                    if (mMediaPlayer != null) {
                        mMediaPlayer.reset();
                    }
                    mIsPlaying = false;
                    Log.e(TAG, "playRecord(), IOException");
                }

                break;

            default:
                Log.d(TAG, "error recording mode");
                break;
        }
    }

    private void stopPlayRecord() {
        Log.i(TAG, "stopPlayRecord");
        switch (mRecordingMode) {

            case RECORDING_MODE_OPENSLES:
                setPlayingUriAudioPlayer(false);
                break;

            case RECORDING_MODE_MEDIARECORDER:
            case RECORDING_MODE_AUDIORECORD:
                if (mMediaPlayer != null) {
                    mMediaPlayer.stop();
                    mMediaPlayer.release();
                    mMediaPlayer = null;
                    mIsPlaying = false;
                    mPlayRecordButton.setEnabled(true);
                } else {
                    mIsPlaying = false;
                    Log.i(TAG, "stopPlayRecord(), mMediaPlayer is null");
                }
                break;


            default:
                Log.d(TAG, "error recording mode");
                break;
        }

    }

    /***********************************************************************************************
     below is Audio Kit sample code
     ***********************************************************************************************/

    /**
     * init Audio Kit
     */
    private void initAudioKit() {
        Log.i(TAG, "initAudioKit");
        mHwAudioKit = new HwAudioKit(this, this);
        mHwAudioKit.initialize();
    }

    /**
     * init Audio Karaoke Feature
     */
    private void initKaraokeFeature() {
        Log.i(TAG, "initKaraokeFeature");
        if (mHwAudioKit != null) {
            mHwAudioKaraokeFeatureKit = mHwAudioKit.createFeature(HwAudioKit.FeatureType.HWAUDIO_FEATURE_KARAOKE);
        } else {
            mInfoTextView.setText(R.string.isInit);
        }
    }

    @Override
    public void onResult(int resultType) {
        Log.i(TAG, "resultType = " + resultType);
        mResultType = "";
        setResultType(resultType);
        switch (resultType) {
            case ResultCode.VENDOR_NOT_SUPPORTED:
                mResultType = getResources().getString(R.string.notInstallAudioKitService);
                break;
            case ResultCode.AUDIO_KIT_SERVICE_DISCONNECTED:
                mResultType = getResources().getString(R.string.audioKitServiceDisconnect);
                break;
            case ResultCode.AUDIO_KIT_SERVICE_DIED:
                mResultType = getResources().getString(R.string.audioKitServiceDied);
                break;
            case ResultCode.KARAOKE_SERVICE_DISCONNECTED:
                mResultType = getResources().getString(R.string.karaokeServiceDisconnect);
                break;
            case ResultCode.KARAOKE_SERVICE_DIED:
                mResultType = getResources().getString(R.string.karaokeServiceDied);
                break;
            default :
                break;
        }
        mResultType = mResultType + resultType;
        mInfoTextView.setText(mResultType);
    }

    private void setResultType(int resultType) {
        switch (resultType) {
            case ResultCode.AUDIO_KIT_SUCCESS:
                mIsAudiokitBindSuccess = true;
                mResultType = getResources().getString(R.string.kitServiceSucess);
                break;
            case ResultCode.KARAOKE_SUCCESS:
                mIsAudiokitKaraokeBindSuccess = true;
                mResultType = getResources().getString(R.string.karaokeServiceSucess);
                break;
            default :
                break;
        }
    }


    private void getFeatures() {
        if (mHwAudioKit != null) {
            setFeaturesText();
        } else {
            mInfoTextView.setText(R.string.isInit);
        }
    }

    private void setFeaturesText() {
        List<Integer> arrayList = mHwAudioKit.getSupportedFeatures();
        String str = getResources().getString(R.string.features);
        StringBuffer strBuffer = new StringBuffer(str);
        if ((arrayList != null) && (arrayList.size() > 0)) {
            for (Integer array : arrayList) {
                strBuffer.append(getFeatureName(array));
            }
            mInfoTextView.setText(strBuffer.toString());
        } else if (mIsAudiokitBindSuccess) {
            mInfoTextView.setText(R.string.noFeatures);
        } else {
            Log.i(TAG, "setFeaturesText");
        }
    }

    private String getFeatureName(int type) {
        if (type == Constant.HWAUDIO_FEATURE_KARAOKE) {
            return FEATURE_TYPE;
        }
        return null;
    }

    private void testFeatureSupport(HwAudioKit.FeatureType type) {
        boolean isSupport = false;
        if (mHwAudioKit != null) {
            isSupport = mHwAudioKit.isFeatureSupported(type);
        } else {
            mInfoTextView.setText(R.string.isInit);
            return;
        }
        if (isSupport) {
            String str = getResources().getString(R.string.support);
            StringBuffer strBuffer = new StringBuffer(str);
            strBuffer.append(getFeatureName(type.getFeatureType()));
            mInfoTextView.setText(strBuffer.toString());
        } else {
            mInfoTextView.setText(R.string.notSupportFeature);
        }
    }

    private void testKaraokeSupport() {
        boolean isSupport = false;
        if ((mHwAudioKaraokeFeatureKit != null) && mIsAudiokitKaraokeBindSuccess) {
            isSupport = mHwAudioKaraokeFeatureKit.isKaraokeFeatureSupport();
        } else {
            mInfoTextView.setText(R.string.isInit);
        }
        if (isSupport) {
            String str = getResources().getString(R.string.supportKaraoke);
            mInfoTextView.setText(str);
        }
    }

    private void enableKaraoke(boolean enable) {
        int enableSuccess = -1;
        if ((mHwAudioKaraokeFeatureKit != null) && mIsAudiokitKaraokeBindSuccess) {
            enableSuccess = mHwAudioKaraokeFeatureKit.enableKaraokeFeature(enable);
        } else {
            mInfoTextView.setText(R.string.isInit);
            return;
        }
  /*      if (enableSuccess == ResultCode.KARAOKE_WIRED_HEADSET_NOT_PLUG_IN) {
            mInfoTextView.setText(R.string.notHeadset);
        } */
        if (enableSuccess == ResultCode.PLATEFORM_NOT_SUPPORT) {
            mInfoTextView.setText(R.string.notSupportFeature);
        }
        if (enable && (enableSuccess == 0)) {
            mInfoTextView.setText(R.string.openSuccess);
        }
        setEnableKaraokeView(enable, enableSuccess == 0);
    }

    private void setEnableKaraokeView(boolean enable, boolean enableSuccess) {
        if (!enable && enableSuccess) {
            mInfoTextView.setText(R.string.closeSuccess);
        }
    }

    private void setReverberation(int value) {
        if ((mHwAudioKaraokeFeatureKit != null) && mIsAudiokitKaraokeBindSuccess) {
            int success = mHwAudioKaraokeFeatureKit
                    .setParameter(HwAudioKaraokeFeatureKit.ParameName.CMD_SET_AUDIO_EFFECT_MODE_BASE, value);
            if (success == 0) {
                mInfoTextView.setText(R.string.success);
            }
            if (success == ResultCode.PLATEFORM_NOT_SUPPORT) {
                mInfoTextView.setText(R.string.notSupportFeature);
            }
            if (success == ResultCode.PARAME_VALUE_ERROR) {
                mInfoTextView.setText(R.string.paramValueError);
            }
        } else {
            mInfoTextView.setText(R.string.isInit);
        }
    }

    private void setVolume(int value) {
        if ((mHwAudioKaraokeFeatureKit != null) && mIsAudiokitKaraokeBindSuccess) {
            int success = mHwAudioKaraokeFeatureKit
                    .setParameter(HwAudioKaraokeFeatureKit.ParameName.CMD_SET_VOCAL_VOLUME_BASE, value);
            if (success == 0) {
                mInfoTextView.setText(R.string.success);
            }
            if (success == ResultCode.PLATEFORM_NOT_SUPPORT) {
                mInfoTextView.setText(R.string.notSupportFeature);
            }
            if (success == ResultCode.PARAME_VALUE_ERROR) {
                mInfoTextView.setText(R.string.paramValueError);
            }
        } else {
            mInfoTextView.setText(R.string.isInit);
        }
    }

    private void setEqualizer(int value) {
        if ((mHwAudioKaraokeFeatureKit != null) && mIsAudiokitKaraokeBindSuccess) {
            int success = mHwAudioKaraokeFeatureKit
                    .setParameter(HwAudioKaraokeFeatureKit.ParameName.CMD_SET_VOCAL_EQUALIZER_MODE, value);
            if (success == 0) {
                mInfoTextView.setText(R.string.success);
            }
            if (success == ResultCode.PLATEFORM_NOT_SUPPORT) {
                mInfoTextView.setText(R.string.notSupportFeature);
            }
            if (success == ResultCode.PARAME_VALUE_ERROR) {
                mInfoTextView.setText(R.string.paramValueError);
            }
        } else {
            mInfoTextView.setText(R.string.isInit);
        }
    }

    private void getLatency() {
        if ((mHwAudioKaraokeFeatureKit != null) && mIsAudiokitKaraokeBindSuccess) {
            setLatencyView();
        } else {
            mInfoTextView.setText(R.string.isInit);
        }
    }

    private void setLatencyView() {
        int latency = mHwAudioKaraokeFeatureKit.getKaraokeLatency();
        String str = getResources().getString(R.string.latency);
        StringBuffer strBuffer = new StringBuffer(str);
        strBuffer.append(latency);
        if (latency != ResultCode.GET_LATENCY_FAIL) {
            mInfoTextView.setText(strBuffer.toString());
            ((TextView)findViewById(R.id.KaraokeLatency)).setText(String.valueOf(latency));
        } else {
            mInfoTextView.setText(R.string.latencyFail);
        }
    }

    private void startAudioTrackThread() {
        try {
            if (null != mAudioTrackThread) {
                mAudioTrackThread.destroy();
                mAudioTrackThread = null;
            }
            mAudioTrackThread = new AudioTrackThread();
            mAudioTrackThread.start();
            Log.i(TAG, "startAudioTrackThread  start...");
        } catch (IllegalThreadStateException e) {
            Log.e(TAG, "startAudioTrackThread IllegalThreadStateException");
        }
    }

    private void stopAudioTrackThread() {
        try {
            if (null != mAudioTrackThread) {
                mAudioTrackThread.destroy();
                mAudioTrackThread = null;
            }
        } catch (IllegalThreadStateException e) {
            Log.e(TAG, "stopAudioTrackThread, IllegalThreadStateException");
        }
    }

    /** Native methods, implemented in jni folder */
    public static native void createEngine();
    public static native void createBufferQueueAudioPlayer(int sampleRate, int samplesPerBuf);
    public static native boolean createUriAudioPlayer(String uri);
    public static native void setPlayingUriAudioPlayer(boolean isPlaying);
    public static native boolean createOpenSLESAudioRecorder(String path);
    public static native void startOpenSLESRecording();
    public static native void stopOpenSLESRecording();
    public static native void shutdown();

    /** Load jni .so on initialization */
    static {
        System.loadLibrary("native-audio-jni");
    }
}
