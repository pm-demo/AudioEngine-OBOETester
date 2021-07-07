package com.hwaudioengine;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

public class AudioTrackThread extends Thread {
    private static final String TAG = "karaoke.AudioTrThread";

    private static final int DEFAULT_INT_TYPE = 0;

    private static final int SAMPLE_RATE = 44100;

    private boolean mIsRunning = true;

    private AudioTrack mAudioTrack = null;

    @Override
    public void run() {
        setPriority(Thread.MAX_PRIORITY);
        int buffsize = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat
                .CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        try {
            mAudioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT, buffsize, AudioTrack.MODE_STREAM);
        } catch (IllegalThreadStateException e) {
            Log.e(TAG, "new Track IllegalThreadStateException");
        }
        if (mAudioTrack == null) {
            Log.e(TAG, "mAudioTrack is null");
            return;
        }
        audioTrackWrite(buffsize);
        mAudioTrack.release();
    }

    private void audioTrackWrite(int buffsize) {
        short[] samples = new short[buffsize];
        if (mAudioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "mAudioTrack uninitialized");
        } else {
            mAudioTrack.play();
            try {
                while (mIsRunning) {
                    mAudioTrack.write(samples, DEFAULT_INT_TYPE, buffsize);
                }
            } catch (IllegalThreadStateException e) {
                Log.e(TAG, "running IllegalThreadStateException");
            }
            mAudioTrack.stop();
        }
    }

    /**
     * thread destroy
     *
     */
    public void destroy() {
        if (mAudioTrack != null) {
            mAudioTrack.stop();
        }
        mIsRunning = false;
    }
}
