/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2019-2019. All rights reserved.
 */

#ifndef LOG_TAG
#define LOG_TAG "NATIVE-AUDIO"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#endif

#include <stdlib.h>
#include <assert.h>
#include <jni.h>
#include <string.h>
#include <pthread.h>


// for __android_log_print(ANDROID_LOG_INFO, "YourApp", "formatted message");
#include <android/log.h>

// for native audio
#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>

// for native asset manager
#include <sys/types.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <unistd.h>

#include <assert.h>

#ifdef DEBUG
#define ASSERT(f) assert(f)
#else
#define ASSERT(f) ((void)0)
#endif

// engine interfaces
static SLObjectItf g_engineObject = NULL;
static SLEngineItf g_engineEngine;

// output mix interfaces
static SLObjectItf g_outputMixObject = NULL;

// buffer queue player interfaces
static SLObjectItf g_bqPlayerObject = NULL;
static SLAndroidSimpleBufferQueueItf g_bqPlayerBufferQueue;
// a mutext to guard against re-entrance to record & playback
// as well as make recording and playing back to be mutually exclusive
// this is to avoid crash at situations like:
//    recording is in session [not finished]
//    user presses record button and another recording coming in
// The action: when recording/playing back is not finished, ignore the new request
static pthread_mutex_t  g_audioEngineLock = PTHREAD_MUTEX_INITIALIZER;

// URI player interfaces
static SLObjectItf g_uriPlayerObject = NULL;
static SLPlayItf g_uriPlayerPlay;
static SLSeekItf g_uriPlayerSeek;
static SLMuteSoloItf g_uriPlayerMuteSolo;
static SLVolumeItf g_uriPlayerVolume;

// recorder interfaces
static SLObjectItf g_recorderObject = NULL;
static SLRecordItf g_recorderRecord;
static SLAndroidSimpleBufferQueueItf g_recorderBufferQueue;

// 1 second of recorded audio at 16 kHz mono, 16-bit signed little endian
#define RECORDER_FRAMES (48000 * 1)
static short g_recorderBuffer[RECORDER_FRAMES];
static unsigned g_recorderSize = 0;
static const char* g_recordPath;

static const unsigned int G_RETRY_COUNT = 5;
static const unsigned int G_ONE_MILIISECONDS = 100000;
static const unsigned int G_WAV_HEADER_LENGTH = 44;
static const unsigned int G_WAV_HEADER_EXCLUDE_LENGTH = 36;
static const unsigned int G_BIT_PER_SAMPLE = 16;
static const unsigned int G_SAMPLE_RATE = 48000;
static const unsigned int G_BITS_ONE_BYTE = 8;
static const unsigned int G_BITS_TWO_BYTES = 16;
static const unsigned int G_BITS_THREE_BYTES = 24;
static const unsigned int G_ONE_BYTE_MASK = 0xFF;

// pointer and size of the next player buffer to enqueue, and number of remaining buffers
static short *g_nextBuffer;
static unsigned g_nextSize;

// this callback handler is called every time a buffer finishes playing
void BqPlayerCallback(SLAndroidSimpleBufferQueueItf bq, void *context)
{
    ASSERT(g_bqPlayerBufferQueue == bq);
    ASSERT(context == NULL);
    // for streaming playback, replace this test by logic to find and fill the next buffer
    if (g_nextBuffer != NULL && g_nextSize != 0) {
        SLresult result;
        // enqueue another buffer
        result = (*g_bqPlayerBufferQueue)->Enqueue(g_bqPlayerBufferQueue, g_nextBuffer, g_nextSize);
        // the most likely other result is SL_RESULT_BUFFER_INSUFFICIENT,
        // which for this code example would indicate a programming error
        if (result != SL_RESULT_SUCCESS) {
            pthread_mutex_unlock(&g_audioEngineLock);
        }
        (void)result;
    } else {
        pthread_mutex_unlock(&g_audioEngineLock);
    }
}

// this callback handler is called every time a buffer finishes recording
void BqRecorderCallback(SLAndroidSimpleBufferQueueItf bq, void *context)
{
    ASSERT(g_recorderBufferQueue == bq);
    ASSERT(context == NULL);
    // for streaming recording, here we would call Enqueue to give recorder the next buffer to fill
    // but instead, this is a one-time buffer so we stop recording
    SLresult result;

    g_recorderSize += RECORDER_FRAMES * sizeof(short);

    // save recorded file
    FILE *fp = fopen(g_recordPath, "aw");
    if (fp == NULL) {
        LOGE("bqRecorderCallback pcm file %s open fail", g_recordPath);
        return;
    }
    fseek(fp, 0, SEEK_END);
    fwrite(g_recorderBuffer, 1, RECORDER_FRAMES * sizeof(short), fp);
    fclose(fp);
    // enqueue an empty buffer to be filled by the recorder
    // (for streaming recording, we would enqueue at least 2 empty buffers to start things off)
    result = (*g_recorderBufferQueue)->Enqueue(g_recorderBufferQueue, g_recorderBuffer,
                                                 RECORDER_FRAMES * sizeof(short));

    // the most likely other result is SL_RESULT_BUFFER_INSUFFICIENT,
    // which for this code example would indicate a programming error
    ASSERT(result == SL_RESULT_SUCCESS);
    (void)result;
    pthread_mutex_unlock(&g_audioEngineLock);
}


// create the engine and output mix objects
// void Java_com_huawei_audiokitdemo_MainActivity_createEngine(JNIEnv* env, jclass clazz)
void Java_com_hwaudioengine_HWMainActivity_createEngine(JNIEnv* env, jclass clazz)
{
    SLresult result;

    // create engine
    result = slCreateEngine(&g_engineObject, 0, NULL, 0, NULL, NULL);
    ASSERT(result == SL_RESULT_SUCCESS);
    (void)result;

    // realize the engine
    result = (*g_engineObject)->Realize(g_engineObject, SL_BOOLEAN_FALSE);
    ASSERT(result == SL_RESULT_SUCCESS);
    (void)result;

    // get the engine interface, which is needed in order to create other objects
    result = (*g_engineObject)->GetInterface(g_engineObject, SL_IID_ENGINE, &g_engineEngine);
    ASSERT(result == SL_RESULT_SUCCESS);
    (void)result;

    // create output mix, with environmental reverb specified as a non-required interface
    const SLInterfaceID IDS[] = {SL_IID_ENVIRONMENTALREVERB};
    const SLboolean REQ[] = {SL_BOOLEAN_FALSE};
    result = (*g_engineEngine)->CreateOutputMix(g_engineEngine, &g_outputMixObject, 1, IDS, REQ);
    ASSERT(result == SL_RESULT_SUCCESS);
    (void)result;

    // realize the output mix
    result = (*g_outputMixObject)->Realize(g_outputMixObject, SL_BOOLEAN_FALSE);
    ASSERT(result == SL_RESULT_SUCCESS);
    (void)result;
}

// create URI audio player
// jboolean Java_com_huawei_audiokitdemo_MainActivity_createUriAudioPlayer(JNIEnv* env, jclass clazz, jstring uri)
jboolean Java_com_hwaudioengine_HWMainActivity_createUriAudioPlayer(JNIEnv* env, jclass clazz, jstring uri)
{
    SLresult result;

    // convert Java string to UTF-8
    const char *utf8 = (*env)->GetStringUTFChars(env, uri, NULL);
    ASSERT(utf8 != NULL);
    LOGD("play uri %s", utf8);

    // configure audio source
    // (requires the INTERNET permission depending on the uri parameter)
    SLDataLocator_URI loc_uri = {SL_DATALOCATOR_URI, (SLchar *) utf8};
    SLDataFormat_MIME format_mime = {SL_DATAFORMAT_MIME, NULL, SL_CONTAINERTYPE_UNSPECIFIED};
    SLDataSource audioSrc = {&loc_uri, &format_mime};

    // configure audio sink
    SLDataLocator_OutputMix loc_outmix = {SL_DATALOCATOR_OUTPUTMIX, g_outputMixObject};
    SLDataSink audioSnk = {&loc_outmix, NULL};

    // create audio player
    const SLInterfaceID IDS[] = {SL_IID_SEEK, SL_IID_MUTESOLO, SL_IID_VOLUME};
    const SLboolean REQ[] = {SL_BOOLEAN_TRUE, SL_BOOLEAN_TRUE, SL_BOOLEAN_TRUE};
    result = (*g_engineEngine)->CreateAudioPlayer(g_engineEngine, &g_uriPlayerObject, &audioSrc,
            &audioSnk, 3, IDS, REQ);
    // note that an invalid URI is not detected here, but during prepare/prefetch on Android,
    // or possibly during Realize on other platforms
    ASSERT(result == SL_RESULT_SUCCESS);
    (void)result;

    // release the Java string and UTF-8
    (*env)->ReleaseStringUTFChars(env, uri, utf8);

    // realize the player
    result = (*g_uriPlayerObject)->Realize(g_uriPlayerObject, SL_BOOLEAN_FALSE);
    // this will always succeed on Android, but we check result for portability to other platforms
    if (result != SL_RESULT_SUCCESS) {
        (*g_uriPlayerObject)->Destroy(g_uriPlayerObject);
        g_uriPlayerObject = NULL;
        return JNI_FALSE;
    }

    // get the play interface
    result = (*g_uriPlayerObject)->GetInterface(g_uriPlayerObject, SL_IID_PLAY, &g_uriPlayerPlay);
    ASSERT(result == SL_RESULT_SUCCESS);
    (void)result;

    // get the seek interface
    result = (*g_uriPlayerObject)->GetInterface(g_uriPlayerObject, SL_IID_SEEK, &g_uriPlayerSeek);
    ASSERT(result == SL_RESULT_SUCCESS);
    (void)result;

    // get the mute/solo interface
    result = (*g_uriPlayerObject)->GetInterface(g_uriPlayerObject, SL_IID_MUTESOLO, &g_uriPlayerMuteSolo);
    ASSERT(result == SL_RESULT_SUCCESS);
    (void)result;

    // get the volume interface
    result = (*g_uriPlayerObject)->GetInterface(g_uriPlayerObject, SL_IID_VOLUME, &g_uriPlayerVolume);
    ASSERT(result == SL_RESULT_SUCCESS);
    (void)result;

    return JNI_TRUE;
}


// set the playing state for the URI audio player
// to PLAYING (true) or PAUSED (false)
/* void Java_com_huawei_audiokitdemo_MainActivity_setPlayingUriAudioPlayer(JNIEnv* env,
    jclass clazz, jboolean isPlaying) */
void Java_com_hwaudioengine_HWMainActivity_setPlayingUriAudioPlayer(JNIEnv* env,
        jclass clazz, jboolean isPlaying)
{
    SLresult result;

    // make sure the URI audio player was created
    if (g_uriPlayerPlay != NULL) {

        // set the player's state
        result = (*g_uriPlayerPlay)->SetPlayState(g_uriPlayerPlay, isPlaying ?
            SL_PLAYSTATE_PLAYING : SL_PLAYSTATE_PAUSED);
        ASSERT(result == SL_RESULT_SUCCESS);
        (void)result;
    }
}


// create audio recorder: recorder is not in fast path
//    like to avoid excessive re-sampling while playing back from Hello & Android clip
// jboolean Java_com_huawei_audiokitdemo_MainActivity_createOpenSLESAudioRecorder(JNIEnv* env, jclass clazz, jstring uri)
jboolean Java_com_hwaudioengine_HWMainActivity_createOpenSLESAudioRecorder(JNIEnv* env, jclass clazz, jstring uri)
{
    SLresult result;

    // convert Java string to UTF-8
    g_recordPath = (*env)->GetStringUTFChars(env, uri, NULL);
    ASSERT(g_recordPath != NULL);
    LOGD("record path %s", g_recordPath);

    // configure audio source
    SLDataLocator_IODevice loc_dev = {SL_DATALOCATOR_IODEVICE, SL_IODEVICE_AUDIOINPUT,
                                      SL_DEFAULTDEVICEID_AUDIOINPUT, NULL};
    SLDataSource audioSrc = {&loc_dev, NULL};

    // configure audio sink
    SLDataLocator_AndroidSimpleBufferQueue loc_bq = {SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE, 2};
    SLDataFormat_PCM format_pcm = {SL_DATAFORMAT_PCM, 1, SL_SAMPLINGRATE_48,
        SL_PCMSAMPLEFORMAT_FIXED_16, SL_PCMSAMPLEFORMAT_FIXED_16,
        SL_SPEAKER_FRONT_CENTER, SL_BYTEORDER_LITTLEENDIAN};
    SLDataSink audioSnk = {&loc_bq, &format_pcm};

    // create audio recorder
    // (requires the RECORD_AUDIO permission)
    const SLInterfaceID ID[] = {SL_IID_ANDROIDSIMPLEBUFFERQUEUE};
    const SLboolean REQ[] = {SL_BOOLEAN_TRUE};
    result = (*g_engineEngine)->CreateAudioRecorder(g_engineEngine, &g_recorderObject, &audioSrc,
            &audioSnk, 1, ID, REQ);
    if (result != SL_RESULT_SUCCESS) {
        return JNI_FALSE;
    }

    // realize the audio recorder
    result = (*g_recorderObject)->Realize(g_recorderObject, SL_BOOLEAN_FALSE);
    if (result != SL_RESULT_SUCCESS) {
        return JNI_FALSE;
    }

    // get the record interface
    result = (*g_recorderObject)->GetInterface(g_recorderObject, SL_IID_RECORD, &g_recorderRecord);
    ASSERT(result == SL_RESULT_SUCCESS);
    (void)result;

    // get the buffer queue interface
    result = (*g_recorderObject)->GetInterface(g_recorderObject, SL_IID_ANDROIDSIMPLEBUFFERQUEUE,
            &g_recorderBufferQueue);
    ASSERT(result == SL_RESULT_SUCCESS);
    (void)result;

    // register callback on the buffer queue
    result = (*g_recorderBufferQueue)->RegisterCallback(g_recorderBufferQueue, BqRecorderCallback,
            NULL);
    ASSERT(result == SL_RESULT_SUCCESS);
    (void)result;

    return JNI_TRUE;
}


// set the recording state for the audio recorder
// void Java_com_huawei_audiokitdemo_MainActivity_startOpenSLESRecording(JNIEnv* env, jclass clazz)
void Java_com_hwaudioengine_HWMainActivity_startOpenSLESRecording(JNIEnv* env, jclass clazz)
{
    SLresult result;

    unsigned char try = G_RETRY_COUNT;
    do {
        if (pthread_mutex_trylock(&g_audioEngineLock)) {
            break;
        }
        usleep(G_ONE_MILIISECONDS); // 0.1 s
    } while (try--);

    if (g_recorderRecord == NULL) {
        LOGD("startRecording, not init");
        return;;
    }
    // in case already recording, stop recording and clear buffer queue
    result = (*g_recorderRecord)->SetRecordState(g_recorderRecord, SL_RECORDSTATE_STOPPED);
    ASSERT(result == SL_RESULT_SUCCESS);
    (void)result;
    result = (*g_recorderBufferQueue)->Clear(g_recorderBufferQueue);
    ASSERT(result == SL_RESULT_SUCCESS);
    (void)result;

    // the buffer is not valid for playback yet
    g_recorderSize = 0;

    // enqueue an empty buffer to be filled by the recorder
    // (for streaming recording, we would enqueue at least 2 empty buffers to start things off)
    result = (*g_recorderBufferQueue)->Enqueue(g_recorderBufferQueue, g_recorderBuffer,
            RECORDER_FRAMES * sizeof(short));
    // the most likely other result is SL_RESULT_BUFFER_INSUFFICIENT,
    // which for this code example would indicate a programming error
    ASSERT(result == SL_RESULT_SUCCESS);
    (void)result;

    // start recording
    result = (*g_recorderRecord)->SetRecordState(g_recorderRecord, SL_RECORDSTATE_RECORDING);
    ASSERT(result == SL_RESULT_SUCCESS);
    (void)result;
}


void WriteWaveFileHeader(unsigned char *out, long totalAudioLen,
                         long totalDataLen, long longSampleRate, int channels, long byteRate)
{
    unsigned char header[G_WAV_HEADER_LENGTH];
    header[0] = 'R'; // RIFF/WAVE header
    header[1] = 'I';
    header[2] = 'F';
    header[3] = 'F';
    header[4] = (unsigned char) (totalDataLen & 0xff);
    header[5] = (unsigned char) ((totalDataLen >> 8) & 0xff);
    header[6] = (unsigned char) ((totalDataLen >> 16) & 0xff);
    header[7] = (unsigned char) ((totalDataLen >> 24) & 0xff);
    header[8] = 'W';
    header[9] = 'A';
    header[10] = 'V';
    header[11] = 'E';
    header[12] = 'f'; // 'fmt ' chunk
    header[13] = 'm';
    header[14] = 't';
    header[15] = ' ';
    header[16] = G_BIT_PER_SAMPLE; // 4 bytes: size of 'fmt ' chunk
    header[17] = 0;
    header[18] = 0;
    header[19] = 0;
    header[20] = 1; // format = 1
    header[21] = 0;
    header[22] = (unsigned char) channels;
    header[23] = 0;
    header[24] = (unsigned char) (longSampleRate & G_ONE_BYTE_MASK);
    header[25] = (unsigned char) ((longSampleRate >> G_BITS_ONE_BYTE) & G_ONE_BYTE_MASK);
    header[26] = (unsigned char) ((longSampleRate >> G_BITS_TWO_BYTES) & G_ONE_BYTE_MASK);
    header[27] = (unsigned char) ((longSampleRate >> G_BITS_THREE_BYTES) & G_ONE_BYTE_MASK);
    header[28] = (unsigned char) (byteRate & G_ONE_BYTE_MASK);
    header[29] = (unsigned char) ((byteRate >> G_BITS_ONE_BYTE) & G_ONE_BYTE_MASK);
    header[30] = (unsigned char) ((byteRate >> G_BITS_TWO_BYTES) & G_ONE_BYTE_MASK);
    header[31] = (unsigned char) ((byteRate >> G_BITS_THREE_BYTES) & G_ONE_BYTE_MASK);
    header[32] = (unsigned char) (2 * G_BITS_TWO_BYTES / G_BITS_ONE_BYTE); // block align
    header[33] = 0;
    header[34] = G_BIT_PER_SAMPLE; // bits per sample
    header[35] = 0;
    header[36] = 'd';
    header[37] = 'a';
    header[38] = 't';
    header[39] = 'a';
    header[40] = (unsigned char) (totalAudioLen & G_ONE_BYTE_MASK);
    header[41] = (unsigned char) ((totalAudioLen >> G_BITS_ONE_BYTE) & G_ONE_BYTE_MASK);
    header[42] = (unsigned char) ((totalAudioLen >> G_BITS_TWO_BYTES) & G_ONE_BYTE_MASK);
    header[43] = (unsigned char) ((totalAudioLen >> G_BITS_THREE_BYTES) & G_ONE_BYTE_MASK);
    memcpy(out, header, sizeof(header));
}


int SaveWavFile(const char *fileName)
{
    int totalAudioLen;
    int totalDataLen;
    int longSampleRate = G_SAMPLE_RATE;
    int channels = 1;
    int byteRate = G_BIT_PER_SAMPLE * G_SAMPLE_RATE * channels / G_BITS_ONE_BYTE;
    char *buffer;

    FILE *fp = fopen(fileName, "rb+");
    if (fp == NULL) {
        LOGE("pcm file %s open fail", g_recordPath);
        return -1;
    }
    fseek(fp, 0, SEEK_END);
    totalAudioLen = ftell(fp);
    totalDataLen = totalAudioLen + G_WAV_HEADER_EXCLUDE_LENGTH; // 36 means header length
    LOGD("totalAudioLen %d, totalDataLen %d", totalAudioLen, totalDataLen);

    // memory allocation
    buffer = (char *)malloc(totalAudioLen * sizeof(char));
    if (buffer == NULL) {
        LOGE("buffer malloc fail");
        return -1;
    }

    // save raw content
    fseek(fp, 0, SEEK_SET);
    fread(buffer, sizeof(char), totalAudioLen, fp);
    fclose(fp);

    // open file again to write header
    fp = fopen(fileName, "wb+");
    if (fp == NULL) {
        LOGE("pcm file %s open fail", g_recordPath);
        return -1;
    }
    // build wav header
    unsigned char header[G_WAV_HEADER_LENGTH];
    WriteWaveFileHeader(header, totalAudioLen, totalDataLen,
                        longSampleRate, channels, byteRate);

    // write header
    fseek(fp, 0, SEEK_SET);
    fwrite(header, sizeof(char), sizeof(header), fp);

    // copy original raw data
    fwrite(buffer, sizeof(char), totalAudioLen, fp);
    if (buffer) {
        free(buffer);
    }

    fclose(fp);
    return 0;
}

// set the recording state for the audio recorder
// void Java_com_huawei_audiokitdemo_MainActivity_stopOpenSLESRecording(JNIEnv* env, jclass clazz)
void Java_com_hwaudioengine_HWMainActivity_stopOpenSLESRecording(JNIEnv* env, jclass clazz)
{
    SLresult result;

    unsigned char try = G_RETRY_COUNT;
    do {
        if (pthread_mutex_trylock(&g_audioEngineLock)) {
            break;
        }
        usleep(G_ONE_MILIISECONDS); // 0.1 s
    } while (try--);

    LOGD("stopRecording");
    if (g_recorderRecord == NULL) {
        LOGD("stopRecording, not init");
        return;;
    }
    result = (*g_recorderRecord)->SetRecordState(g_recorderRecord, SL_RECORDSTATE_STOPPED);
    ASSERT(result == SL_RESULT_SUCCESS);
    (void)result;
    result = (*g_recorderBufferQueue)->Clear(g_recorderBufferQueue);
    ASSERT(result == SL_RESULT_SUCCESS);
    (void)result;

    int ret = SaveWavFile(g_recordPath);
    if (ret != 0) {
        LOGE("save file fail");
    }
}

// shut down the native audio system
//void Java_com_huawei_audiokitdemo_MainActivity_shutdown(JNIEnv* env, jclass clazz)
void Java_com_hwaudioengine_HWMainActivity_shutdown(JNIEnv* env, jclass clazz)
{

    // destroy buffer queue audio player object, and invalidate all associated interfaces
    if (g_bqPlayerObject != NULL) {
        (*g_bqPlayerObject)->Destroy(g_bqPlayerObject);
        g_bqPlayerObject = NULL;
        g_bqPlayerBufferQueue = NULL;
    }

    // destroy URI audio player object, and invalidate all associated interfaces
    if (g_uriPlayerObject != NULL) {
        (*g_uriPlayerObject)->Destroy(g_uriPlayerObject);
        g_uriPlayerObject = NULL;
        g_uriPlayerPlay = NULL;
        g_uriPlayerSeek = NULL;
        g_uriPlayerMuteSolo = NULL;
        g_uriPlayerVolume = NULL;
    }

    // destroy audio recorder object, and invalidate all associated interfaces
    if (g_recorderObject != NULL) {
        (*g_recorderObject)->Destroy(g_recorderObject);
        g_recorderObject = NULL;
        g_recorderRecord = NULL;
        g_recorderBufferQueue = NULL;
    }

    // destroy output mix object, and invalidate all associated interfaces
    if (g_outputMixObject != NULL) {
        (*g_outputMixObject)->Destroy(g_outputMixObject);
        g_outputMixObject = NULL;
    }

    // destroy engine object, and invalidate all associated interfaces
    if (g_engineObject != NULL) {
        (*g_engineObject)->Destroy(g_engineObject);
        g_engineObject = NULL;
        g_engineEngine = NULL;
    }

    pthread_mutex_destroy(&g_audioEngineLock);
}
