/*
 * Copyright (c) 2021, the hapjs-platform Project Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.hapjs.features.video;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.util.Range;
import android.view.Surface;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Vector;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.hapjs.bridge.Request;
import org.hapjs.bridge.Response;
import org.hapjs.common.executors.Executors;
import org.hapjs.features.video.gles.InputSurface;
import org.hapjs.features.video.gles.OutputSurface;
import org.hapjs.logging.RuntimeLogManager;

public class VideoConverter {
    private static final String TAG = "VideoConverter";
    private static final String VIDEO_MIME_TYPE = "video/avc";
    private static final String AUDIO_MIME_TYPE = "audio/mp4a-latm";
    private static final String AUDIO_PREFIX = "audio/";
    private static final int OUTPUT_AUDIO_SAMPLE_RATE_HZ = 48000;
    private static final int OUTPUT_AUDIO_CHANNEL_COUNT = 2;
    private static final int OUTPUT_AUDIO_BIT_RATE = 128000;
    private static final String VIDEO_PREFIX = "video/";
    private static final String VIDEO_DECODE_THREAD_NAME = "video-decode-handlerthread";
    private static final String VIDEO_ENCODE_THREAD_NAME = "video-encode-handlerthread";
    private static final String AUDIO_DECODE_THREAD_NAME = "audio-decode-handlerthread";
    private static final String AUDIO_ENCODE_THREAD_NAME = "audio-encode-handlerthread";

    // ?????????????????????????????????or??????????????????
    private static final int NO_TRACT = -1;
    private static final int DEFAULT_I_FRAME_INTERVAL = 1;
    private static final int CACHE_BUFFER_SIZE = 100;

    private static final ArrayBlockingQueue<MediaFrame> AUDIO_DATA_QUEUE =
            new ArrayBlockingQueue<MediaFrame>(CACHE_BUFFER_SIZE);

    private MediaMuxer mMuxer;
    private int mOutputVideoTrack = -1;
    private int mOutputAudioTrack = -1;
    private MediaCodec mVideoEncoder;
    private MediaCodec mAudioEncoder;
    private CodecThread mVideoDecodeThread;
    private Handler mVideoDecodeThreadHandler;
    private CallbackHandler mVideoDecodeThreadHandlerFor21;
    private CodecThread mVideoEncodeThread;
    private Handler mVideoEncodeThreadHandler;
    private CallbackHandler mVideoEncodeThreadHandlerFor21;
    private CodecThread mAudioDecodeThread;
    private Handler mAudioDecodeThreadHandler;
    private CallbackHandler mAudioDecodeThreadHandlerFor21;
    private CodecThread mAudioEncodeThread;
    private Handler mAudioEncodeThreadHandler;
    private CallbackHandler mAudioEncodeThreadHandlerFor21;
    private MediaExtractor mVideoExtractor;
    private MediaExtractor mAudioExtractor;
    private MediaCodec mVideoDecoder = null;
    private MediaCodec mAudioDecoder = null;
    private MediaCodec.Callback mVideoDecodeCallback = null;
    private MediaCodec.Callback mVideoEncodeCallback = null;
    private MediaCodec.Callback mAudioDecodeCallback = null;
    private MediaCodec.Callback mAudioEncodeCallback = null;
    private InputSurface mInputSurface = null;
    private OutputSurface mOutputSurface = null;

    private MediaFormat mVideoInputFormat = null;
    private MediaFormat mAudioInputFormat = null;

    private Vector<MediaCodecFrame> mVideoDeCodeFrameList;
    private int mPreparedVideoPtsMs = -1;
    private long mLastFramePtsUs = -1;
    private boolean isVideoDecodeOutputEos = false;
    private int mVideoEncodeFrameIndex = 0;
    private int mVideoEncodePtsMs = 0;

    private volatile boolean isAudioEncoderStarted = false;
    private volatile boolean isAudioDecodeEnd = false;
    private volatile boolean isMuxerStart = false;
    private volatile boolean isReleaseAll = false;
    private volatile boolean isVideoEnd = false;
    private volatile boolean isAudioEnd = false;
    private volatile boolean isAllFrameEnd = false;
    private volatile boolean isAudioExist = false;
    private volatile boolean isVideoExist = false;
    // ????????????MP4?????????????????????????????????????????????
    private long nLastVideoPts = 0x7fffffff;
    private long nLastAudioPts = 0x7fffffff;
    private long nCurrentPositionMs = 0;
    // ??????????????????????????????
    private int mExportDuration = 0;
    // ??????????????????????????????
    private int mProgressPercent = 0;
    private VideoCompressTask mVideoCompressTask;
    private Lock mMuxerLock = new ReentrantLock();
    private Lock mPercentLock = new ReentrantLock();
    private volatile boolean isReleaseMuxer = false;
    private VideoCompressCallback mVideoCompressCallback;

    public VideoConverter(VideoCompressTask videoCompressTask, VideoCompressCallback callback) {
        mVideoCompressTask = videoCompressTask;
        mVideoCompressCallback = callback;
    }

    public void stopConvertTask() {
        stopAndRelease();
    }

    public void startConvertTask() {
        Log.d(TAG, "startConvertTask begin" + Thread.currentThread().getName());
        mVideoCompressTask.notifyTaskProgress(0);
        Request request = mVideoCompressTask.getCompressRequest();
        int height = mVideoCompressTask.getHeight();
        int width = mVideoCompressTask.getWidth();
        int bitrate = mVideoCompressTask.getBps();
        int framerate = mVideoCompressTask.getFps();
        Uri sourcePath = mVideoCompressTask.getSourceUrl();
        String targetPath = mVideoCompressTask.getTargetPath();
        mExportDuration = mVideoCompressTask.getExportDuration();
        Context context = request.getNativeInterface().getActivity();
        // ??????????????????????????????????????????????????????
        mVideoExtractor = new MediaExtractor();
        mAudioExtractor = new MediaExtractor();
        try {
            mVideoExtractor.setDataSource(context, sourcePath, null);
            mAudioExtractor.setDataSource(context, sourcePath, null);
            mMuxer = new MediaMuxer(targetPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } catch (IOException e) {
            Response response = new Response(Response.CODE_IO_ERROR, "target file error");
            request.getCallback().callback(response);
            RuntimeLogManager.getDefault()
                    .logVideoFeature(
                            request, Integer.toString(Response.CODE_IO_ERROR),
                            "extractor.setDataSource");
            if (mVideoCompressCallback != null) {
                mVideoCompressCallback.notifyAbort(mVideoCompressTask);
            }
            return;
        } catch (Exception e) {
            Log.e(TAG, "setDataSource error:" + e);
            Response response =
                    new Response(Response.CODE_GENERIC_ERROR,
                            "failed to initialize extractor or muxer");
            request.getCallback().callback(response);
            RuntimeLogManager.getDefault()
                    .logVideoFeature(
                            request, Integer.toString(Response.CODE_GENERIC_ERROR),
                            "extractor.setDataSource");
            if (mVideoCompressCallback != null) {
                mVideoCompressCallback.notifyAbort(mVideoCompressTask);
            }
            return;
        }
        // ???????????????????????????????????????????????????????????? ??? ??????extractor????????????
        int videoIndex = selectTrack(mVideoExtractor, false);
        // ???????????????????????????
        int audioIndex = selectTrack(mAudioExtractor, true);
        isVideoExist = videoIndex != NO_TRACT;
        isAudioExist = audioIndex != NO_TRACT;
        if (!isVideoExist && !isAudioExist) {
            request
                    .getCallback()
                    .callback(
                            new Response(
                                    Response.CODE_GENERIC_ERROR,
                                    "no video track or audio track can be founded"));
            RuntimeLogManager.getDefault()
                    .logVideoFeature(request, Integer.toString(Response.CODE_GENERIC_ERROR),
                            "no track");
            if (mVideoCompressCallback != null) {
                mVideoCompressCallback.notifyAbort(mVideoCompressTask);
            }
            return;
        }
        // ????????????????????????????????????????????????????????????
        if (isVideoExist) {
            mVideoInputFormat = getMediaFormat(mVideoExtractor, videoIndex);
            int rotation = mVideoCompressTask.getRotation();
            if (rotation == 90 || rotation == 270) {
                int temp = width;
                width = height;
                height = temp;
            }
        }
        if (isAudioExist) {
            mAudioInputFormat = getMediaFormat(mAudioExtractor, audioIndex);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { // ?????????api>=21???
            // ????????????????????????????????????????????????MediaCodec.Callback??????
            createMediaCoderCallback(mVideoExtractor, mAudioExtractor);
            // ????????????
            createThread();
            // ??????api>=23
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    createMediaCoder();
                } catch (Exception e) {
                    Log.e(TAG, "fail to create codec:" + e);
                    request
                            .getCallback()
                            .callback(
                                    new Response(Response.CODE_GENERIC_ERROR,
                                            "fail to create decoder or encoder "));
                    RuntimeLogManager.getDefault()
                            .logVideoFeature(
                                    request, Integer.toString(Response.CODE_GENERIC_ERROR),
                                    "createMediaCoder ");
                    if (mVideoCompressCallback != null) {
                        mVideoCompressCallback.notifyAbort(mVideoCompressTask);
                    }
                    return;
                }
                // ??????????????????????????????
                if (isVideoExist) {
                    mVideoEncodeThreadHandler = new Handler(mVideoEncodeThread.getLooper());
                    mVideoDecodeThreadHandler = new Handler(mVideoDecodeThread.getLooper());
                    mVideoEncoder.setCallback(mVideoEncodeCallback, mVideoEncodeThreadHandler);
                    mVideoDecoder.setCallback(mVideoDecodeCallback, mVideoDecodeThreadHandler);
                }
                if (isAudioExist) {
                    mAudioDecodeThreadHandler = new Handler(this.mAudioDecodeThread.getLooper());
                    mAudioEncodeThreadHandler = new Handler(this.mAudioEncodeThread.getLooper());
                    mAudioEncoder.setCallback(mAudioEncodeCallback, mAudioEncodeThreadHandler);
                    mAudioDecoder.setCallback(mAudioDecodeCallback, mAudioDecodeThreadHandler);
                }
            } else {
                // ??????api[21,23)
                setCallbackForApi21(mVideoInputFormat, mAudioInputFormat); // ??????????????????????????????????????????
            }
        } else {
            request
                    .getCallback()
                    .callback(
                            new Response(Response.CODE_GENERIC_ERROR,
                                    "the android version is not supported"));
            if (mVideoCompressCallback != null) {
                mVideoCompressCallback.notifyAbort(mVideoCompressTask);
            }
            return;
        }
        // ???????????????????????????????????????
        if (isVideoExist) {
            MediaCodecInfo mediaCodecInfo = mVideoEncoder.getCodecInfo();
            if (mediaCodecInfo != null) {
                String[] types = mediaCodecInfo.getSupportedTypes();
                for (int j = 0; j < types.length; j++) {
                    if (VIDEO_MIME_TYPE.equalsIgnoreCase(types[j])) {
                        MediaCodecInfo.CodecCapabilities codecCaps =
                                mediaCodecInfo.getCapabilitiesForType(VIDEO_MIME_TYPE);
                        MediaCodecInfo.VideoCapabilities vidCaps = codecCaps.getVideoCapabilities();
                        Range<Integer> framerates = vidCaps.getSupportedFrameRates();
                        Range<Integer> widths = vidCaps.getSupportedWidths();
                        Range<Integer> heights = vidCaps.getSupportedHeights();
                        Range<Integer> bitrates = vidCaps.getBitrateRange();
                        Log.d(
                                TAG,
                                "Found encoder with\n"
                                        + widths
                                        + " x "
                                        + heights
                                        + " framerates??? "
                                        + framerates
                                        + "bps: "
                                        + bitrates);
                        if (!framerates.contains(framerate)
                                || width < widths.getLower()
                                || height < heights.getLower()
                                || !bitrates.contains(bitrate)) {
                            Response response =
                                    new Response(
                                            Response.CODE_ILLEGAL_ARGUMENT,
                                            "params values are out of range of device support");
                            request.getCallback().callback(response);
                            if (mVideoCompressCallback != null) {
                                mVideoCompressCallback.notifyAbort(mVideoCompressTask);
                            }
                            return;
                        }
                    }
                }
            }
        }
        // ?????????????????????
        if (isVideoExist) {
            // ?????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
            // ?????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????

            // ??????video?????????
            MediaFormat videoOutputFormat =
                    MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, width, height);
            videoOutputFormat.setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            videoOutputFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            videoOutputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, framerate);
            videoOutputFormat
                    .setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, DEFAULT_I_FRAME_INTERVAL);
            if (!configureMediaCoder(
                    request, videoOutputFormat, mVideoEncoder, null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE)) {
                return;
            }
            // ??????video?????????
            Surface surface = null;
            try {
                surface = mVideoEncoder.createInputSurface();
                mInputSurface = new InputSurface(surface);
                mInputSurface.makeCurrent();
                // ??????video?????????
                mOutputSurface = new OutputSurface();
                if (!configureMediaCoder(
                        request, mVideoInputFormat, mVideoDecoder, mOutputSurface.getSurface(),
                        0)) {
                    return;
                }
                mInputSurface.releaseEGLContext();
            } catch (Exception e) {
                Log.e(TAG, "init surface error:" + e);
                Response response =
                        new Response(Response.CODE_GENERIC_ERROR, "failed to init surface");
                request.getCallback().callback(response);
                if (mVideoCompressCallback != null) {
                    mVideoCompressCallback.notifyAbort(mVideoCompressTask);
                }
                return;
            }
        }
        if (isAudioExist) {
            // ??????audio?????????
            if (!configureMediaCoder(request, mAudioInputFormat, mAudioDecoder, null, 0)) {
                return;
            }
        }
        // ?????????????????????
        if (isVideoExist) {
            // ?????????????????????
            try {
                mVideoEncoder.start();
                mVideoDecoder.start();
            } catch (Exception e) {
                Log.e(TAG, "start video error:" + e);
                Response response =
                        new Response(Response.CODE_GENERIC_ERROR,
                                "failed to start video transcoding ");
                request.getCallback().callback(response);
                RuntimeLogManager.getDefault()
                        .logVideoFeature(
                                request, Integer.toString(Response.CODE_GENERIC_ERROR),
                                "video start fail");
                if (mVideoCompressCallback != null) {
                    mVideoCompressCallback.notifyAbort(mVideoCompressTask);
                }
                return;
            }

            mVideoDeCodeFrameList = new Vector();
            int videoDurationMs =
                    (int)
                            (mVideoInputFormat.containsKey(MediaFormat.KEY_DURATION)
                                    ? mVideoInputFormat.getLong(MediaFormat.KEY_DURATION) / 1000L
                                    : mExportDuration * 1000);
            // ????????????????????????????????????????????????????????????????????????
            Executors.io()
                    .execute(
                            () -> {
                                do {
                                    Log.d(TAG, "video renderFrame start");
                                    Lock localLock = mVideoDecodeThread.mCodecLock;
                                    try {
                                        localLock.lock();
                                        if (!mVideoDecodeThread.isRelease) {
                                            mVideoEncodePtsMs =
                                                    (int)
                                                            ((long) mVideoEncodeFrameIndex
                                                                    * 1000L
                                                                    / (long) mVideoCompressTask
                                                                    .getFps());
                                            if (mVideoEncodePtsMs > videoDurationMs) {
                                                // ?????????????????????????????????????????????output?????????????????????
                                                mVideoEncoder.signalEndOfInputStream();
                                                return;
                                            }
                                            boolean isSuccess =
                                                    onPrepareVideoFrame(mVideoEncodePtsMs);
                                            if (isSuccess) {
                                                mVideoEncodeFrameIndex++;
                                            }
                                        }
                                    } catch (Exception e) {
                                        Log.e(TAG, "transcode error:", e);
                                        Response response =
                                                new Response(Response.CODE_GENERIC_ERROR,
                                                        "transcode error");
                                        request.getCallback().callback(response);
                                        if (mVideoCompressCallback != null) {
                                            mVideoCompressCallback.notifyAbort(mVideoCompressTask);
                                        }
                                    } finally {
                                        localLock.unlock();
                                        Log.d(TAG, "video renderFrame done");
                                    }
                                } while (!mVideoDecodeThread.isRelease);
                            });
            Log.i(TAG, "video start ");
        }
        if (isAudioExist) {
            // ?????????????????????
            try {
                mAudioDecoder.start();
            } catch (Exception e) {
                Log.e(TAG, "start audio error:" + e);
                Response response =
                        new Response(Response.CODE_GENERIC_ERROR,
                                "failed to start audio transcoding ");
                request.getCallback().callback(response);
                RuntimeLogManager.getDefault()
                        .logVideoFeature(
                                request, Integer.toString(Response.CODE_GENERIC_ERROR),
                                "audio start fail");
                if (mVideoCompressCallback != null) {
                    mVideoCompressCallback.notifyAbort(mVideoCompressTask);
                }
                return;
            }
            Log.i(TAG, "audio start ");
        }
    }

    private void createThread() {
        if (isVideoExist) {
            mVideoDecodeThread = new CodecThread(VIDEO_DECODE_THREAD_NAME);
            mVideoDecodeThread.start();
            mVideoEncodeThread = new CodecThread(VIDEO_ENCODE_THREAD_NAME);
            mVideoEncodeThread.start();
        }
        if (isAudioExist) {
            mAudioDecodeThread = new CodecThread(AUDIO_DECODE_THREAD_NAME);
            mAudioDecodeThread.start();
            mAudioEncodeThread = new CodecThread(AUDIO_ENCODE_THREAD_NAME);
            mAudioEncodeThread.start();
        }
    }

    // ???????????????-??????????????????????????????onInputBufferAvailable?????????????????????mediacodec????????????????????????????????????onOutputBufferAvailable???????????????????????????
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void createMediaCoderCallback(
            MediaExtractor videoExtractor, MediaExtractor audioExtractor) {
        Request request = mVideoCompressTask.getCompressRequest();
        if (isVideoExist) {
            mVideoDecodeCallback =
                    new MediaCodec.Callback() {
                        @Override
                        public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
                            Log.d(TAG, "video decode onInputBufferAvailable  " + " index " + index);
                            ByteBuffer inputBuffer = null;
                            Lock localLock = mVideoDecodeThread.mCodecLock;
                            try {
                                localLock.lock();
                                if (!mVideoDecodeThread.isRelease) {
                                    // ?????????????????????
                                    inputBuffer = codec.getInputBuffer(index);
                                    // ???????????????????????????????????????
                                    int sampleSize = videoExtractor.readSampleData(inputBuffer, 0);
                                    if (sampleSize < 0) {
                                        codec.queueInputBuffer(index, 0, 0, 0,
                                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                    } else {
                                        long sampleTime = videoExtractor.getSampleTime();
                                        codec.queueInputBuffer(index, 0, sampleSize, sampleTime, 0);
                                        // extractor???????????????????????????????????????????????????????????????????????????????????????
                                        videoExtractor.advance();
                                    }
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "transcode error:", e);
                                Response response = new Response(Response.CODE_GENERIC_ERROR,
                                        "transcode error");
                                request.getCallback().callback(response);
                                if (mVideoCompressCallback != null) {
                                    mVideoCompressCallback.notifyAbort(mVideoCompressTask);
                                }
                            } finally {
                                localLock.unlock();
                                Log.d(TAG, "video decode onInputBufferAvailable done");
                            }
                        }

                        @Override
                        public void onOutputBufferAvailable(
                                @NonNull MediaCodec codec, int index,
                                @NonNull MediaCodec.BufferInfo info) {
                            Log.d(
                                    TAG,
                                    "video decode onOutputBufferAvailable"
                                            + " index "
                                            + index
                                            + " pts "
                                            + info.presentationTimeUs
                                            + " size "
                                            + info.size
                                            + " flags "
                                            + info.flags);
                            Lock localLock = mVideoDecodeThread.mCodecLock;
                            try {
                                localLock.lock();
                                if (!mVideoDecodeThread.isRelease) {
                                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                        codec.releaseOutputBuffer(index, false);
                                        return;
                                    }
                                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                        isVideoDecodeOutputEos = true;
                                    }
                                    if (info.size <= 0) {
                                        codec.releaseOutputBuffer(index, false);
                                        return;
                                    }
                                    mVideoDeCodeFrameList.add(new MediaCodecFrame(index, info));
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "transcode error:", e);
                                Response response = new Response(Response.CODE_GENERIC_ERROR,
                                        "transcode error");
                                request.getCallback().callback(response);
                                if (mVideoCompressCallback != null) {
                                    mVideoCompressCallback.notifyAbort(mVideoCompressTask);
                                }
                            } finally {
                                localLock.unlock();
                                Log.d(TAG, "video decode onOutputBufferAvailable done");
                            }
                        }

                        @Override
                        public void onError(@NonNull MediaCodec codec,
                                            @NonNull MediaCodec.CodecException e) {
                            Log.e(TAG, "transcode error:", e);
                            Response response =
                                    new Response(Response.CODE_GENERIC_ERROR, "transcode error");
                            request.getCallback().callback(response);
                            RuntimeLogManager.getDefault()
                                    .logVideoFeature(
                                            request,
                                            Integer.toString(Response.CODE_GENERIC_ERROR),
                                            "video decode onError");
                            if (mVideoCompressCallback != null) {
                                mVideoCompressCallback.notifyAbort(mVideoCompressTask);
                            }
                        }

                        @Override
                        public void onOutputFormatChanged(
                                @NonNull MediaCodec codec, @NonNull MediaFormat format) {
                            String usingSwRenderer = "using-sw-renderer";
                            if (format.containsKey(usingSwRenderer)) {
                                int flag = format.getInteger(usingSwRenderer);
                                if (flag == 1) {
                                    Lock localLock = mVideoDecodeThread.mCodecLock;
                                    try {
                                        localLock.lock();
                                        if (!mVideoDecodeThread.isRelease) {
                                            mOutputSurface.makeSWRenderMatrix(format,
                                                    mVideoCompressTask.getRotation());
                                        }
                                    } finally {
                                        localLock.unlock();
                                        Log.d(TAG, "video decode onOutputBufferAvailable done");
                                    }
                                }
                            }
                        }
                    };
            mVideoEncodeCallback =
                    new MediaCodec.Callback() {
                        @Override
                        public void onInputBufferAvailable(@NonNull MediaCodec mediaCodec, int id) {
                        }

                        @Override
                        public void onOutputBufferAvailable(
                                @NonNull MediaCodec codec, int index,
                                @NonNull MediaCodec.BufferInfo info) {
                            Log.d(
                                    TAG,
                                    "video encode onOutputBufferAvailable"
                                            + " index "
                                            + index
                                            + " pts "
                                            + info.presentationTimeUs
                                            + " size "
                                            + info.size
                                            + " flags "
                                            + info.flags);
                            ByteBuffer buffer = null;
                            Lock localLock = mVideoEncodeThread.mCodecLock;
                            try {
                                localLock.lock();
                                if (!mVideoEncodeThread.isRelease) {
                                    buffer = codec.getOutputBuffer(index);
                                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                        codec.releaseOutputBuffer(index, false);
                                        return;
                                    }
                                    // ??????buffer?????????mp4??????
                                    if (buffer != null && info.size > 0) {
                                        // muxer???????????????mp4?????????????????????muxer.start??????????????????????????????
                                        Object lock = new Object();
                                        while (!isMuxerStart && !isReleaseMuxer) {
                                            Log.i(TAG, "video encode wait muxer start");
                                            synchronized (lock) {
                                                try {
                                                    lock.wait(50);
                                                } catch (InterruptedException e) {
                                                    Log.e(TAG, "InterruptedException: ", e);
                                                    break;
                                                }
                                            }
                                        }
                                        nLastVideoPts = info.presentationTimeUs;
                                        try {
                                            mMuxerLock.lock();
                                            if (!isReleaseMuxer) {
                                                mMuxer.writeSampleData(mOutputVideoTrack, buffer,
                                                        info);
                                            }
                                        } finally {
                                            mMuxerLock.unlock();
                                        }
                                    }
                                    codec.releaseOutputBuffer(index, false);
                                    mPercentLock.lock();
                                    try {
                                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                                != 0) {
                                            isVideoEnd = true;
                                            markFrameEnd();
                                        }
                                        updateProgress();
                                        // ????????????????????????????????????
                                        if (isAllFrameEnd) {
                                            if (mVideoCompressCallback != null) {
                                                mVideoCompressCallback
                                                        .notifyAbort(mVideoCompressTask);
                                            }
                                        }
                                    } finally {
                                        mPercentLock.unlock();
                                    }
                                }
                            } catch (RuntimeException e) {
                                Log.e(TAG, "transcode error:", e);
                                Response response = new Response(Response.CODE_GENERIC_ERROR,
                                        "transcode error");
                                request.getCallback().callback(response);
                                if (mVideoCompressCallback != null) {
                                    mVideoCompressCallback.notifyAbort(mVideoCompressTask);
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "transcode error:", e);
                                Response response = new Response(Response.CODE_GENERIC_ERROR,
                                        "transcode error");
                                request.getCallback().callback(response);
                                if (mVideoCompressCallback != null) {
                                    mVideoCompressCallback.notifyAbort(mVideoCompressTask);
                                }
                            } finally {
                                localLock.unlock();
                                Log.d(TAG, "video encode onOutputBufferAvailable done");
                            }
                        }

                        @Override
                        public void onError(@NonNull MediaCodec codec,
                                            @NonNull MediaCodec.CodecException e) {
                            Log.e(TAG, "transcode error:", e);
                            Response response =
                                    new Response(Response.CODE_GENERIC_ERROR, "transcode error");
                            request.getCallback().callback(response);
                            RuntimeLogManager.getDefault()
                                    .logVideoFeature(
                                            request,
                                            Integer.toString(Response.CODE_GENERIC_ERROR),
                                            "video encode onError");
                            if (mVideoCompressCallback != null) {
                                mVideoCompressCallback.notifyAbort(mVideoCompressTask);
                            }
                        }

                        @Override
                        public void onOutputFormatChanged(
                                @NonNull MediaCodec codec, @NonNull MediaFormat format) {
                            try {
                                mMuxerLock.lock();
                                if (!isReleaseMuxer) {
                                    mOutputVideoTrack = mMuxer.addTrack(format);
                                    startMuxer();
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "transcode error:", e);
                                Response response = new Response(Response.CODE_GENERIC_ERROR,
                                        "transcode error");
                                request.getCallback().callback(response);
                                if (mVideoCompressCallback != null) {
                                    mVideoCompressCallback.notifyAbort(mVideoCompressTask);
                                }
                            } finally {
                                mMuxerLock.unlock();
                            }
                        }
                    };
        }
        if (isAudioExist) {
            mAudioDecodeCallback =
                    new MediaCodec.Callback() {
                        @Override
                        public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
                            Log.d(TAG, "audio decode onInputBufferAvailable" + " index " + index);
                            ByteBuffer inputBuffer = null;
                            Lock localLock = mAudioDecodeThread.mCodecLock;
                            try {
                                localLock.lock();
                                if (!mAudioDecodeThread.isRelease) {
                                    inputBuffer = codec.getInputBuffer(index);
                                    int sampleSize = audioExtractor.readSampleData(inputBuffer, 0);
                                    Log.d(
                                            TAG,
                                            "audio decode onInputBufferAvailable mAudioExtractor.getSampleFlags():"
                                                    + audioExtractor.getSampleFlags()
                                                    + " sampleSize:"
                                                    + sampleSize);
                                    if (sampleSize < 0) {
                                        codec.queueInputBuffer(index, 0, 0, 0,
                                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                    } else {
                                        long sampleTime = audioExtractor.getSampleTime();
                                        codec.queueInputBuffer(
                                                index, 0, sampleSize, sampleTime,
                                                audioExtractor.getSampleFlags());
                                        audioExtractor.advance();
                                    }
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "transcode error:", e);
                                Response response = new Response(Response.CODE_GENERIC_ERROR,
                                        "transcode error");
                                request.getCallback().callback(response);
                                if (mVideoCompressCallback != null) {
                                    mVideoCompressCallback.notifyAbort(mVideoCompressTask);
                                }
                            } finally {
                                localLock.unlock();
                                Log.d(TAG, "audio decode onInputBufferAvailable done");
                            }
                        }

                        @Override
                        public void onOutputBufferAvailable(
                                @NonNull MediaCodec codec, int id,
                                @NonNull MediaCodec.BufferInfo info) {
                            Log.d(
                                    TAG,
                                    "audio decode onOutputBufferAvailable  "
                                            + " index "
                                            + id
                                            + " pts "
                                            + info.presentationTimeUs
                                            + " size "
                                            + info.size
                                            + " flags "
                                            + info.flags);
                            ByteBuffer outputBuffer = null;
                            Lock localLock = mAudioDecodeThread.mCodecLock;
                            try {
                                localLock.lock();
                                if (!mAudioDecodeThread.isRelease) {
                                    outputBuffer = codec.getOutputBuffer(id);
                                    int originalBufferSize = 4096;
                                    if (outputBuffer != null && info.size > 0) {
                                        byte[] buffer = new byte[outputBuffer.remaining()];
                                        originalBufferSize = buffer.length;
                                        outputBuffer.get(buffer);
                                        ByteBuffer buf = ByteBuffer.wrap(buffer);
                                        MediaFrame newFrame = new MediaFrame(buf);
                                        newFrame.set(buf.remaining(), info.presentationTimeUs,
                                                info.flags);
                                        try {
                                            // ?????????????????????????????????????????????????????????????????????
                                            AUDIO_DATA_QUEUE.put(newFrame);
                                        } catch (InterruptedException e) {
                                            Log.d(TAG, "AUDIO_DATA_QUEUE put???" + e.getMessage());
                                        }
                                    }
                                    if (!isAudioEncoderStarted) {
                                        isAudioEncoderStarted = true;
                                        int bufferSize;
                                        if (originalBufferSize % 4096 == 0) {
                                            bufferSize = originalBufferSize;
                                        } else {
                                            bufferSize = (originalBufferSize / 4096 + 1) * 4096;
                                        }
                                        int channelCount =
                                                mAudioInputFormat
                                                        .containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                                                        ? mAudioInputFormat
                                                        .getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                                        : OUTPUT_AUDIO_CHANNEL_COUNT;
                                        int sampleRate =
                                                mAudioInputFormat
                                                        .containsKey(MediaFormat.KEY_SAMPLE_RATE)
                                                        ? mAudioInputFormat
                                                        .getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                                        : OUTPUT_AUDIO_SAMPLE_RATE_HZ;
                                        sampleRate =
                                                sampleRate > OUTPUT_AUDIO_SAMPLE_RATE_HZ
                                                        ? OUTPUT_AUDIO_SAMPLE_RATE_HZ
                                                        : sampleRate;
                                        int audioBitRate =
                                                mAudioInputFormat
                                                        .containsKey(MediaFormat.KEY_BIT_RATE)
                                                        ? mAudioInputFormat
                                                        .getInteger(MediaFormat.KEY_BIT_RATE)
                                                        : OUTPUT_AUDIO_BIT_RATE;
                                        audioBitRate =
                                                audioBitRate > OUTPUT_AUDIO_BIT_RATE
                                                        ? OUTPUT_AUDIO_BIT_RATE : audioBitRate;
                                        MediaFormat audioOutputFormat =
                                                MediaFormat.createAudioFormat(AUDIO_MIME_TYPE,
                                                        sampleRate, channelCount);
                                        audioOutputFormat
                                                .setInteger(MediaFormat.KEY_BIT_RATE, audioBitRate);
                                        audioOutputFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,
                                                bufferSize);
                                        if (!configureMediaCoder(
                                                request,
                                                audioOutputFormat,
                                                mAudioEncoder,
                                                null,
                                                MediaCodec.CONFIGURE_FLAG_ENCODE)) {
                                            return;
                                        }
                                        mAudioEncoder.start();
                                    }
                                    codec.releaseOutputBuffer(id, false);
                                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                        Log.i(TAG,
                                                "audio decodeCallback BUFFER_FLAG_END_OF_STREAM");
                                        isAudioDecodeEnd = true;
                                    }
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "transcode error:", e);
                                Response response = new Response(Response.CODE_GENERIC_ERROR,
                                        "transcode error");
                                request.getCallback().callback(response);
                                if (mVideoCompressCallback != null) {
                                    mVideoCompressCallback.notifyAbort(mVideoCompressTask);
                                }
                            } finally {
                                localLock.unlock();
                                Log.d(TAG, "audio decode onOutputBufferAvailable done");
                            }
                        }

                        @Override
                        public void onError(@NonNull MediaCodec codec,
                                            @NonNull MediaCodec.CodecException e) {
                            Log.e(TAG, "transcode error:", e);
                            Response response =
                                    new Response(Response.CODE_GENERIC_ERROR, "transcode error");
                            RuntimeLogManager.getDefault()
                                    .logVideoFeature(
                                            request,
                                            Integer.toString(Response.CODE_GENERIC_ERROR),
                                            "audio decode onError");
                            request.getCallback().callback(response);
                            if (mVideoCompressCallback != null) {
                                mVideoCompressCallback.notifyAbort(mVideoCompressTask);
                            }
                        }

                        @Override
                        public void onOutputFormatChanged(
                                @NonNull MediaCodec codec, @NonNull MediaFormat format) {
                        }
                    };
            mAudioEncodeCallback =
                    new MediaCodec.Callback() {
                        @Override
                        public void onInputBufferAvailable(@NonNull MediaCodec mediaCodec,
                                                           int index) {
                            Log.d(TAG, "audio encode onInputBufferAvailable" + " index " + index);
                            MediaFrame dataSources = null;
                            Lock localLock = mAudioEncodeThread.mCodecLock;
                            try {
                                localLock.lock();
                                if (!mAudioEncodeThread.isRelease) {
                                    dataSources = AUDIO_DATA_QUEUE.poll();
                                    int length = 0;
                                    if (dataSources != null && dataSources.size > 0) {
                                        ByteBuffer inputBuffer = mediaCodec.getInputBuffer(index);
                                        inputBuffer.put((ByteBuffer) dataSources.mediaBuffer);
                                        length = dataSources.size;
                                        inputBuffer.position(0);
                                        Log.d(
                                                TAG,
                                                "audioencodeCallback  onInputBufferAvailable queue audio buffer pts "
                                                        + dataSources.presentationTimeUs
                                                        + " size "
                                                        + dataSources.size
                                                        + " flags "
                                                        + dataSources.flags);
                                        mediaCodec.queueInputBuffer(
                                                index, 0, length, dataSources.presentationTimeUs,
                                                dataSources.flags);

                                    } else if (isAudioDecodeEnd) {
                                        mediaCodec.queueInputBuffer(
                                                index, 0, 0, 0,
                                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                    } else {
                                        // ???????????????????????????queueInputBuffer?????????mediac??????????????????????????????????????????
                                        Log.d(TAG,
                                                "audioencodeCallback  onInputBufferAvailable  nodatasorces ");
                                        mediaCodec.queueInputBuffer(index, 0, 0, 0, 0);
                                    }
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "transcode error:", e);
                                Response response = new Response(Response.CODE_GENERIC_ERROR,
                                        "transcode error");
                                request.getCallback().callback(response);
                                if (mVideoCompressCallback != null) {
                                    mVideoCompressCallback.notifyAbort(mVideoCompressTask);
                                }
                            } finally {
                                localLock.unlock();
                                Log.d(TAG, "audio encode onInputBufferAvailable done");
                            }
                        }

                        @Override
                        public void onOutputBufferAvailable(
                                @NonNull MediaCodec codec, int index,
                                @NonNull MediaCodec.BufferInfo info) {
                            Log.d(
                                    TAG,
                                    "audio encode onOutputBufferAvailable isVideo "
                                            + " index "
                                            + index
                                            + " pts "
                                            + info.presentationTimeUs
                                            + " size "
                                            + info.size
                                            + " flags "
                                            + info.flags);
                            ByteBuffer outputBuffer = null;
                            Lock localLock = mAudioEncodeThread.mCodecLock;
                            try {
                                localLock.lock();
                                if (!mAudioEncodeThread.isRelease) {
                                    outputBuffer = codec.getOutputBuffer(index);
                                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                        codec.releaseOutputBuffer(index, false);
                                        return;
                                    }
                                    if (info.size != 0) {
                                        Object lock = new Object();
                                        while (!isMuxerStart && !isReleaseAll) {
                                            Log.i(TAG, "audio encode wait muxer start");
                                            synchronized (lock) {
                                                try {
                                                    lock.wait(50);
                                                } catch (InterruptedException e) {
                                                    Log.e(TAG, "InterruptedException: ", e);
                                                    break;
                                                }
                                            }
                                        }
                                        // ?????????????????????????????????????????????????????????
                                        nLastAudioPts = info.presentationTimeUs;
                                        try {
                                            mMuxerLock.lock();
                                            if (!isReleaseMuxer) {
                                                mMuxer.writeSampleData(mOutputAudioTrack,
                                                        outputBuffer, info);
                                            }

                                        } finally {
                                            mMuxerLock.unlock();
                                        }
                                        codec.releaseOutputBuffer(index, false);
                                    }
                                    try {
                                        mPercentLock.lock();
                                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                                != 0) {
                                            if (info.presentationTimeUs == 0 && info.size > 0) {
                                                Log.d(TAG, "invalid frame info");
                                                info.size = 0;
                                            }
                                            Log.d(TAG,
                                                    "audio BUFFER_FLAG_END_OF_STREAM want to  mMuxer.stop() ");
                                            isAudioEnd = true;
                                            markFrameEnd();
                                        }

                                        updateProgress();
                                        if (isAllFrameEnd) {
                                            if (mVideoCompressCallback != null) {
                                                mVideoCompressCallback
                                                        .notifyAbort(mVideoCompressTask);
                                            }
                                        }
                                    } finally {
                                        mPercentLock.unlock();
                                    }
                                }
                            } catch (RuntimeException e) {
                                Log.e(TAG, "transcode error:", e);
                                Response response = new Response(Response.CODE_GENERIC_ERROR,
                                        "transcode error");
                                request.getCallback().callback(response);
                                if (mVideoCompressCallback != null) {
                                    mVideoCompressCallback.notifyAbort(mVideoCompressTask);
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "transcode error:", e);
                                Response response = new Response(Response.CODE_GENERIC_ERROR,
                                        "transcode error");
                                request.getCallback().callback(response);
                                if (mVideoCompressCallback != null) {
                                    mVideoCompressCallback.notifyAbort(mVideoCompressTask);
                                }
                            } finally {
                                localLock.unlock();
                                Log.d(TAG, "audio encode onOutputBufferAvailable done");
                            }
                        }

                        @Override
                        public void onError(@NonNull MediaCodec codec,
                                            @NonNull MediaCodec.CodecException e) {
                            Log.e(TAG, "transcode error:", e);
                            Response response =
                                    new Response(Response.CODE_GENERIC_ERROR, "transcode error");
                            RuntimeLogManager.getDefault()
                                    .logVideoFeature(
                                            request,
                                            Integer.toString(Response.CODE_GENERIC_ERROR),
                                            "audio encode onError");
                            request.getCallback().callback(response);
                            if (mVideoCompressCallback != null) {
                                mVideoCompressCallback.notifyAbort(mVideoCompressTask);
                            }
                        }

                        @Override
                        public void onOutputFormatChanged(
                                @NonNull MediaCodec codec, @NonNull MediaFormat format) {
                            try {
                                mMuxerLock.lock();
                                if (!isReleaseMuxer && mMuxer != null) {
                                    mOutputAudioTrack = mMuxer.addTrack(format);
                                    startMuxer();
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "transcode error:", e);
                                Response response = new Response(Response.CODE_GENERIC_ERROR,
                                        "transcode error");
                                request.getCallback().callback(response);
                                if (mVideoCompressCallback != null) {
                                    mVideoCompressCallback.notifyAbort(mVideoCompressTask);
                                }
                            } finally {
                                mMuxerLock.unlock();
                            }
                        }
                    };
        }
    }

    // ?????????????????????????????????????????????????????????????????????????????????
    boolean onPrepareVideoFrame(int ptsMs) throws Exception {
        Log.d(
                TAG,
                "onPrepareVideoFrame "
                        + ptsMs
                        + ", mPreparedVideoPtsMs "
                        + mPreparedVideoPtsMs
                        + ", and mLastFramePtsUs "
                        + mLastFramePtsUs);

        // case 1, ???????????????????????????????????????????????????????????????????????????
        // case 2, ????????????????????????????????????????????????????????????
        final int UsePreviousFrame = 1;
        // case 3, ?????????????????????????????????????????????????????????
        // case 4, ???????????????????????????????????????????????????????????????????????????
        // case 5, ??????????????????????????????????????????????????????????????????????????????
        final int UseFirstFrame = 2;
        // case 6, ???????????????????????????????????????????????????????????????????????????????????????????????????????????????
        // case 7, ??????????????????????????????????????????????????????????????????????????????
        final int UseSecondFrame = 3;
        // case 8, ????????????????????????????????????????????????????????????????????????????????????

        int ret = 0;
        MediaCodecFrame frame0 = null;
        MediaCodecFrame frame1 = null;
        do {
            int bufferCount = mVideoDeCodeFrameList.size();

            // case 1, ???????????????????????????????????????????????????????????????????????????
            if (mPreparedVideoPtsMs >= ptsMs || mLastFramePtsUs >= ptsMs * 1000L) {
                ret = UsePreviousFrame;
                break;
            }
            // case 2, ??????????????????????????????????????????????????????????????????????????????
            if (bufferCount == 0) {

                if (isVideoDecodeOutputEos) {
                    ret = UsePreviousFrame;
                }
                break;
            }

            // ????????????????????????????????????
            frame0 = mVideoDeCodeFrameList.get(0);
            // case 3, ????????????????????????????????????0ms??????????????????????????????
            // case 4, ???????????????????????????????????????????????????????????????????????????
            if (mPreparedVideoPtsMs == -1 || frame0.bufferInfo.presentationTimeUs / 1000 >= ptsMs) {
                ret = UseFirstFrame;
                break;
            }

            // case 5, ??????????????????????????????????????????????????????????????????????????????
            if (bufferCount == 1) {
                if (isVideoDecodeOutputEos) {
                    ret = UseFirstFrame;
                }
                break;
            }

            // ???????????????????????????????????????????????????????????????????????????
            frame1 = mVideoDeCodeFrameList.get(1);

            // case 6, ???????????????????????????????????????????????????????????????????????????????????????????????????????????????
            if (frame1.bufferInfo.presentationTimeUs / 1000 > ptsMs) {
                ret = UseFirstFrame;
                break;
            } else {
                // ???????????????????????????????????????????????????????????????????????????????????????
                mVideoDeCodeFrameList.remove(0);
                mVideoDecoder.releaseOutputBuffer(frame0.bufferIndex, false);
                if (frame1.bufferInfo.presentationTimeUs / 1000 == ptsMs) {
                    // case 7, ??????????????????????????????????????????????????????????????????????????????
                    ret = UseSecondFrame;
                    break;
                }
            }

            // case 8, ????????????????????????????????????????????????????????????????????????????????????
            Log.d(TAG, "onPrepareVideoFrame check next loop");
        } while (mVideoDeCodeFrameList.size() > 0);

        if (ret == UsePreviousFrame) {
            Log.d(
                    TAG,
                    "onPrepareVideoFrame UsePreviousFrame mPreparedVideoPtsMs "
                            + mPreparedVideoPtsMs
                            + " mLastFramePtsUs "
                            + mLastFramePtsUs);
            mPreparedVideoPtsMs = ptsMs;
            mInputSurface.makeCurrent();
            updateInputSurface(ptsMs);
        } else if (ret == UseFirstFrame) {
            Log.d(TAG, "onPrepareVideoFrame UseFirstFrame " + frame0.bufferInfo.presentationTimeUs);
            mVideoDeCodeFrameList.remove(0);
            mVideoDecoder.releaseOutputBuffer(frame0.bufferIndex, true);
            mLastFramePtsUs = frame0.bufferInfo.presentationTimeUs;
            mInputSurface.makeCurrent();
            Log.d(TAG, "output surface: await new image");
            mOutputSurface.awaitNewImage();
            updateInputSurface(ptsMs);
        } else if (ret == UseSecondFrame) {
            Log.d(TAG,
                    "onPrepareVideoFrame UserSecondFrame " + frame1.bufferInfo.presentationTimeUs);
            mVideoDeCodeFrameList.remove(0);
            mVideoDecoder.releaseOutputBuffer(frame1.bufferIndex, true);
            mLastFramePtsUs = frame1.bufferInfo.presentationTimeUs;
            mInputSurface.makeCurrent();
            Log.d(TAG, "output surface: await new image");
            mOutputSurface.awaitNewImage();
            updateInputSurface(ptsMs);
        } else {
            Log.d(TAG, "onPrepareVideoFrame frame not found");
            return false;
        }
        return true;
    }

    private void updateInputSurface(long ptsMs) {
        Log.d(TAG, "output surface: draw image");
        mOutputSurface.drawImage();
        mInputSurface.setPresentationTime(ptsMs * 1000000L);
        Log.d(TAG, "input surface: swap buffers");
        mInputSurface.swapBuffers();
        Log.d(TAG, "input surface: notified of new frame");
        mInputSurface.releaseEGLContext();
    }

    private void setCallbackForApi21(MediaFormat videoInputFormat, MediaFormat audioInputFormat) {
        if (isVideoExist && mVideoEncodeThread != null && mVideoDecodeThread != null) {
            mVideoEncodeThreadHandlerFor21 = new CallbackHandler(mVideoEncodeThread.getLooper());
            // ???????????????????????????codec ??????codec??????setcallback
            mVideoEncodeThreadHandlerFor21.create(true, VIDEO_MIME_TYPE, mVideoEncodeCallback);
            mVideoEncoder = mVideoEncodeThreadHandlerFor21.getCodec();

            mVideoDecodeThreadHandlerFor21 = new CallbackHandler(mVideoDecodeThread.getLooper());
            mVideoDecodeThreadHandlerFor21.create(
                    false, videoInputFormat.getString(MediaFormat.KEY_MIME), mVideoDecodeCallback);
            mVideoDecoder = mVideoDecodeThreadHandlerFor21.getCodec();
            mVideoDecodeThread.setCodec(mVideoDecoder);
            mVideoEncodeThread.setCodec(mVideoEncoder);
        }
        if (isAudioExist && mAudioEncodeThread != null && mAudioDecodeThread != null) {
            mAudioEncodeThreadHandlerFor21 = new CallbackHandler(mAudioEncodeThread.getLooper());
            mAudioEncodeThreadHandlerFor21.create(true, AUDIO_MIME_TYPE, mAudioEncodeCallback);
            mAudioEncoder = mAudioEncodeThreadHandlerFor21.getCodec();
            mAudioDecodeThreadHandlerFor21 = new CallbackHandler(mAudioDecodeThread.getLooper());
            mAudioDecodeThreadHandlerFor21.create(
                    false, audioInputFormat.getString(MediaFormat.KEY_MIME), mAudioDecodeCallback);
            mAudioDecoder = mAudioDecodeThreadHandlerFor21.getCodec();
            mAudioDecodeThread.setCodec(mAudioDecoder);
            mAudioEncodeThread.setCodec(mAudioEncoder);
        }
    }

    // ??????????????????????????????????????????????????????
    private int selectTrack(MediaExtractor extractor, boolean isAudio) {
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (isAudio) {
                if (mime.startsWith(AUDIO_PREFIX)) {
                    return i;
                }
            } else {
                if (mime.startsWith(VIDEO_PREFIX)) {
                    return i;
                }
            }
        }
        return NO_TRACT;
    }

    // ????????????????????????????????????or?????????????????????
    @NonNull
    private MediaFormat getMediaFormat(MediaExtractor extractor, int index) {
        extractor.selectTrack(index);
        return extractor.getTrackFormat(index);
    }

    // ??????????????????
    private void createMediaCoder() throws Exception {
        String audioDecoderType = "";
        String videoDecoderType = "";

        if (isAudioExist) {
            audioDecoderType = mAudioInputFormat.getString(MediaFormat.KEY_MIME);
            mAudioDecoder = MediaCodec.createDecoderByType(audioDecoderType);
            mAudioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME_TYPE);
            mAudioDecodeThread.setCodec(mAudioDecoder);
            mAudioEncodeThread.setCodec(mAudioEncoder);
        }
        if (isVideoExist) {
            videoDecoderType = mVideoInputFormat.getString(MediaFormat.KEY_MIME);
            mVideoDecoder = MediaCodec.createDecoderByType(videoDecoderType);
            mVideoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE);
            mVideoDecodeThread.setCodec(mVideoDecoder);
            mVideoEncodeThread.setCodec(mVideoEncoder);
        }
    }

    // ??????????????????
    private boolean configureMediaCoder(
            Request request, MediaFormat mediaFormat, MediaCodec codec, Surface surface, int flag) {
        try {
            codec.configure(mediaFormat, surface, null, flag);
        } catch (Exception e) {
            Log.e(TAG, "configure error:", e);
            Response response =
                    new Response(Response.CODE_GENERIC_ERROR, "failed to configure mediacoder");
            RuntimeLogManager.getDefault()
                    .logVideoFeature(
                            request, Integer.toString(Response.CODE_GENERIC_ERROR),
                            "configure mediacoder");
            request.getCallback().callback(response);
            if (mVideoCompressCallback != null) {
                mVideoCompressCallback.notifyAbort(mVideoCompressTask);
            }
            return false;
        }
        return true;
    }

    // ???????????????mp4
    private void startMuxer() {
        if (!isMuxerStart
                && (!isAudioExist || mOutputAudioTrack != -1)
                && (!isVideoExist || mOutputVideoTrack != -1)) {
            mMuxer.start();
            Log.i(TAG, "start muxer");
            isMuxerStart = true;
        }
    }

    // ???????????????????????????????????????????????????
    private void markFrameEnd() {
        if (!isAllFrameEnd && (!isAudioExist || isAudioEnd) && (!isVideoExist || isVideoEnd)) {
            isAllFrameEnd = true;
        }
    }

    // ????????????????????????????????????
    private void updateProgress() {
        int temp;
        // ??????????????????
        temp = (int) (Math.min(nLastAudioPts, nLastVideoPts) / 1000000);
        if (isAllFrameEnd) {
            mProgressPercent = 100;
            mVideoCompressTask.notifyTaskProgress(mProgressPercent);
            return;
        }
        // ???????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
        if (temp <= nCurrentPositionMs) {
            return;
        }
        nCurrentPositionMs = temp;
        if (mExportDuration > 0) {
            int percent = 0;
            percent = (int) (nCurrentPositionMs * 100 / mExportDuration);
            if (percent >= 100) {
                percent = 100;
            }
            if (percent > mProgressPercent) {
                mProgressPercent = percent;
                mVideoCompressTask.notifyTaskProgress(mProgressPercent);
            }
        }
    }

    // 1.??????????????????????????????????????? 2.???????????????task???????????????????????????????????? 3.????????????????????????????????????
    private void stopAndRelease() {
        Log.i(TAG, "stopAndRelease begin in" + Thread.currentThread().getName());
        if (isReleaseAll) {
            return;
        } else {
            isReleaseAll = true;
        }
        try {
            mMuxerLock.lock();
            if (mMuxer != null) {
                if (isMuxerStart) {
                    mMuxer.stop();
                }
                mMuxer.release();
            }
        } catch (Exception e) {
            Log.e(TAG, "Muxer stop error:", e);
        } finally {
            isReleaseMuxer = true;
            mMuxerLock.unlock();
        }
        if (isVideoExist) {
            mVideoDecodeThread.stopAndRelease();
            mVideoEncodeThread.stopAndRelease();
        }
        if (isAudioExist) {
            AUDIO_DATA_QUEUE.clear();
            mAudioDecodeThread.stopAndRelease();
            mAudioEncodeThread.stopAndRelease();
        }
        try {
            if (mVideoExtractor != null) {
                mVideoExtractor.release();
            }
        } catch (Exception e) {
            Log.e(TAG, "VideoExtractor release error:", e);
        }
        try {
            if (mAudioExtractor != null) {
                mAudioExtractor.release();
            }
        } catch (Exception e) {
            Log.e(TAG, "AudioExtractor release error:", e);
        }
        try {
            if (mOutputSurface != null) {
                mOutputSurface.release();
            }
        } catch (Exception e) {
            Log.e(TAG, "OutputSurface release error:", e);
        }
        try {
            if (mInputSurface != null) {
                mInputSurface.release();
            }
        } catch (Exception e) {
            Log.e(TAG, "InputSurface release error:", e);
        }

        mMuxer = null;
        mVideoDecoder = null;
        mVideoEncoder = null;
        mAudioDecoder = null;
        mAudioEncoder = null;
        mVideoExtractor = null;
        mAudioExtractor = null;
        mOutputSurface = null;
        mInputSurface = null;
        if (mVideoCompressCallback != null) {
            mVideoCompressCallback.notifyComplete(mVideoCompressTask, isAllFrameEnd);
        }
        Log.i(TAG, "stopAndRelease end");
    }

    private static class MediaCodecFrame {
        public int bufferIndex;
        public MediaCodec.BufferInfo bufferInfo;

        public MediaCodecFrame(int index, MediaCodec.BufferInfo info) {
            this.bufferIndex = index;
            this.bufferInfo = info;
        }
    }

    // ??????api[21,23)??????mediacodec??????
    private static class CallbackHandler extends Handler {
        ConditionVariable conditionVariable = new ConditionVariable();
        private MediaCodec mCodec;
        private boolean isEncoder;
        private MediaCodec.Callback mCallback;
        private String mMime;

        CallbackHandler(Looper l) {
            super(l);
        }

        @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
        @Override
        public void handleMessage(Message msg) {
            try {
                mCodec =
                        isEncoder
                                ? MediaCodec.createEncoderByType(mMime)
                                : MediaCodec.createDecoderByType(mMime);
            } catch (IOException ioe) {
                Log.e(TAG, "callback handler error", ioe);
            }
            mCodec.setCallback(mCallback);
            conditionVariable.open();
        }

        void create(boolean encoder, String mime, MediaCodec.Callback callback) {
            isEncoder = encoder;
            mMime = mime;
            mCallback = callback;
            // ???????????????????????????codec???setcallback??????start
            conditionVariable.close();
            sendEmptyMessage(0);
            conditionVariable.block();
        }

        MediaCodec getCodec() {
            return mCodec;
        }
    }

    // ??????????????????????????????????????????
    private static class MediaFrame {
        private Object mediaBuffer;
        private int size;
        private long presentationTimeUs;
        private int flags;

        public MediaFrame(Object buffer) {
            mediaBuffer = buffer;
        }

        public void set(int newSize, long newTimeUs, int newFlags) {
            size = newSize;
            presentationTimeUs = newTimeUs;
            flags = newFlags;
        }
    }

    private static class CodecThread extends HandlerThread {
        MediaCodec mCodec;
        volatile boolean isRelease;
        ReentrantLock mCodecLock = new ReentrantLock();

        CodecThread(String name) {
            super(name);
        }

        public void setCodec(MediaCodec codec) {
            this.mCodec = codec;
        }

        void stopAndRelease() {
            mCodecLock.lock();
            try {
                this.quit();
                if (mCodec != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        mCodec.reset();
                    }
                    mCodec.stop();
                    mCodec.release();
                    mCodec = null;
                }
            } catch (Exception e) {
                Log.e(TAG, "stopAndRelease error:", e);
            } finally {
                isRelease = true;
                mCodecLock.unlock();
            }
        }
    }
}
