package com.wtz.libvideomaker.egl;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.text.TextUtils;
import android.view.Surface;

import com.wtz.libvideomaker.utils.HexUtils;
import com.wtz.libvideomaker.utils.LogUtils;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import javax.microedition.khronos.egl.EGLContext;

public abstract class WeGLVideoPushEncoder {

    private static final String TAG = "WeGLVideoPushEncoder";
    private String mExternalTag;

    private WeakReference<WeGLVideoPushEncoder> mWeakReference;
    private OnEncodeDataListener mOnEncodeDataListener;

    // 渲染线程
    private WeGLThread mGLThread;
    private boolean isGLThreadExiting;
    private EGLContext mShareContext;
    private Surface mSurface;
    private WeGLRenderer mRenderer;
    private int mRenderMode = WeGLRenderer.RENDERMODE_CONTINUOUSLY;
    private int mRenderFps = 0;

    // 视频编码线程
    private MediaEncodeThread mVideoEncodeThread;
    private boolean isVideoEncThreadExiting;
    private static final int MEDIA_FRAME_RATE = 30;// 一般摄像头预览最大 30 帧每秒
    private static final int I_FRAME_INTERVAL = 1;// 设置关键帧间隔为 1 秒
    private MediaCodec mVideoEncoder;
    private MediaFormat mVideoFormat;
    private MediaCodec.BufferInfo mVideoBufInfo;
    private long mEncodeTimeMills;
    // for H.264(AVC)
    private byte[] mSPS;
    private byte[] mPPS;

    // 音频编码线程
    private MediaEncodeThread mAudioEncodeThread;
    private boolean isAudioEncThreadExiting;
    private boolean needEncodeAudio;
    private boolean isAudioEncoderStarted;
    private int mAudioSampleRate;
    private int mAudioChannelNums;
    private int mAudioBitsPerSample;
    private int mAudioBytesPerSecond;
    private int mPcmMaxBytesPerCallback;
    private long mAudioPts;
    private MediaCodec mAudioEncoder;
    private MediaFormat mAudioFormat;
    private MediaCodec.BufferInfo mAudioBufInfo;

    // 接口调度线程
    private Handler mWorkHandler;
    private HandlerThread mWorkThread;
    private boolean isRecording;
    private boolean isReleased;

    private static final int HANDLE_START_ENCODE = 0;
    private static final int HANDLE_STOP_ENCODE = 1;
    private static final int HANDLE_RELEASE = 2;
    private static final String PARAMS_EGL_CONTEXT = "egl_context";
    private static final String PARAMS_MIME_TYPE = "mime_type";
    private static final String PARAMS_VIDEO_WIDTH = "video_width";
    private static final String PARAMS_VIDEO_HEIGHT = "video_height";
    private static final String PARAMS_SAMPLE_RATE = "sample_rate";
    private static final String PARAMS_CHANNEL_NUMS = "channel_nums";
    private static final String PARAMS_BITS_PER_SAMPLE = "bits_per_sample";

    protected abstract WeGLRenderer getRenderer();

    protected abstract String getExternalLogTag();

    public WeGLVideoPushEncoder() {
        mExternalTag = getExternalLogTag() + ": ";
        mWorkThread = new HandlerThread("WeGLVideoPushEncoder");
        mWorkThread.start();
        mWorkHandler = new Handler(mWorkThread.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                int msgType = msg.what;
                LogUtils.d(TAG, "mWorkHandler handleMessage: " + msgType);
                switch (msgType) {
                    case HANDLE_START_ENCODE:
                        handleStartEncode(msg);
                        break;

                    case HANDLE_STOP_ENCODE:
                        handleStopEncode();
                        break;

                    case HANDLE_RELEASE:
                        handleRelease();
                        break;
                }
            }
        };
        LogUtils.w(TAG, mExternalTag + "WeGLVideoPushEncoder created");
    }

    public void setOnEncodeDataListener(OnEncodeDataListener listener) {
        this.mOnEncodeDataListener = listener;
    }

    public void setRenderMode(int renderMode) {
        if ((WeGLRenderer.RENDERMODE_WHEN_DIRTY != renderMode)
                && (renderMode != WeGLRenderer.RENDERMODE_CONTINUOUSLY)) {
            throw new IllegalArgumentException(
                    exceptionPrefix() + "illegal argument: renderMode " + renderMode);
        }
        mRenderMode = renderMode;
    }

    public void setRenderFps(int fps) {
        mRenderFps = fps;
        if (mGLThread != null) {
            mGLThread.setRenderFps(mRenderFps);
        }
    }

    public void requestRender() {
        if (mGLThread == null) {
            LogUtils.e(TAG, mExternalTag + exceptionPrefix()
                    + "GLThread is null! You can't call requestRender before onEGLContextCreated.");
            return;
        }
        mGLThread.requestRender();
    }

    public void setAudioParams(int sampleRate, int channelNums, int bitsPerSample, int pcmMaxBytesPerCallback) {
        this.mAudioSampleRate = sampleRate;
        this.mAudioChannelNums = channelNums;
        this.mAudioBitsPerSample = bitsPerSample;
        mAudioBytesPerSecond = mAudioSampleRate * mAudioChannelNums * mAudioBitsPerSample / 8;
        this.mPcmMaxBytesPerCallback = pcmMaxBytesPerCallback;
    }

    protected void startEncode(EGLContext context, String mimeType, int videoWidth, int videoHeight) {
        if (isReleased) {
            LogUtils.e(TAG, mExternalTag + "startEncode but this encoder is already released!");
            return;
        }
        mWorkHandler.removeMessages(HANDLE_START_ENCODE);// 以最新设置为准

        Map<String, Object> params = new HashMap<>();
        params.put(PARAMS_EGL_CONTEXT, context);
        params.put(PARAMS_MIME_TYPE, mimeType);
        params.put(PARAMS_VIDEO_WIDTH, videoWidth);
        params.put(PARAMS_VIDEO_HEIGHT, videoHeight);
        // 音频参数没有不影响视频录制，也就是无音视频
        params.put(PARAMS_SAMPLE_RATE, mAudioSampleRate);
        params.put(PARAMS_CHANNEL_NUMS, mAudioChannelNums);
        params.put(PARAMS_BITS_PER_SAMPLE, mAudioBitsPerSample);

        Message msg = mWorkHandler.obtainMessage(HANDLE_START_ENCODE);
        msg.obj = params;
        mWorkHandler.sendMessage(msg);
    }

    private void handleStartEncode(Message msg) {
        if (isRecording) {
            LogUtils.e(TAG, "Can't start encoder again: it's already recording!");
            return;
        }
        isRecording = true;

        Map<String, Object> params = (Map<String, Object>) msg.obj;
        EGLContext context = (EGLContext) params.get(PARAMS_EGL_CONTEXT);
        String mimeType = (String) params.get(PARAMS_MIME_TYPE);
        int videoWidth = (int) params.get(PARAMS_VIDEO_WIDTH);
        int videoHeight = (int) params.get(PARAMS_VIDEO_HEIGHT);
        int sampleRate = (int) params.get(PARAMS_SAMPLE_RATE);
        int channelNums = (int) params.get(PARAMS_CHANNEL_NUMS);
        int bitsPerSample = (int) params.get(PARAMS_BITS_PER_SAMPLE);
        handleStartEncode(context, mimeType, videoWidth, videoHeight,
                sampleRate, channelNums, bitsPerSample);
    }

    private void handleStartEncode(EGLContext context, String mimeType,
                                   int videoWidth, int videoHeight, int sampleRate,
                                   int channelNums, int bitsPerSample) {
        LogUtils.w(TAG, mExternalTag + "handleStartEncode mimeType=" + mimeType +
                ", video size=" + videoWidth + "x" + videoHeight);
        this.mShareContext = context;
        if (mShareContext == null) {
            throw new IllegalArgumentException("EGLContext can't be null!");
        }

        if (TextUtils.isEmpty(mimeType) || videoWidth <= 0 || videoHeight <= 0) {
            throw new IllegalArgumentException("startEncode video arguments is illegal");
        }

        mRenderer = getRenderer();
        if (mRenderer == null) {
            throw new RuntimeException("The render from getRenderer can't be null!");
        }

        if (!initVideoEncoder(mimeType, videoWidth, videoHeight)) {
            return;
        }

        needEncodeAudio = initAudioEncoder();
        LogUtils.w(TAG, "needEncodeAudio " + needEncodeAudio);

        mWeakReference = new WeakReference<>(this);
        mGLThread = new GLThread(mWeakReference, getExternalLogTag());
        if (mRenderFps > 0) {
            mGLThread.setRenderFps(mRenderFps);
        }
        mGLThread.onWindowResize(videoWidth, videoHeight);
        mGLThread.start();

        mVideoEncodeThread = new MediaEncodeThread(mWeakReference, MediaEncodeThread.TYPE_VIDEO, "VideoEncodeThread");
        mVideoEncodeThread.start();

        if (needEncodeAudio) {
            mAudioEncodeThread = new MediaEncodeThread(mWeakReference, MediaEncodeThread.TYPE_AUDIO, "AudioEncodeThread");
            mAudioEncodeThread.start();
        }
    }

    private boolean initVideoEncoder(String mimeType, int videoWidth, int videoHeight) {
        mVideoFormat = MediaFormat.createVideoFormat(mimeType, videoWidth, videoHeight);
        mVideoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        mVideoFormat.setInteger(MediaFormat.KEY_BIT_RATE, videoWidth * videoHeight * 4);// 设置码率
        mVideoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, MEDIA_FRAME_RATE);
        mVideoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);

        try {
            mVideoEncoder = MediaCodec.createEncoderByType(mimeType);
            mVideoEncoder.configure(mVideoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mSurface = mVideoEncoder.createInputSurface();
        } catch (Exception e) {
            e.printStackTrace();
            mVideoFormat = null;
            mVideoEncoder = null;
            return false;
        }

        mVideoBufInfo = new MediaCodec.BufferInfo();
        mEncodeTimeMills = 0;
        return true;
    }

    private boolean initAudioEncoder() {
        if (mAudioSampleRate == 0 || mAudioChannelNums == 0
                || mAudioBitsPerSample == 0 || mPcmMaxBytesPerCallback == 0) {
            LogUtils.e(TAG, mExternalTag + "initAudioEncoder but audio params is not set!");
            return false;
        }

        String mimeType = MediaFormat.MIMETYPE_AUDIO_AAC;
        mAudioFormat = MediaFormat.createAudioFormat(mimeType, mAudioSampleRate, mAudioChannelNums);
        mAudioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 96000);
        mAudioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        mAudioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, mPcmMaxBytesPerCallback);

        try {
            mAudioEncoder = MediaCodec.createEncoderByType(mimeType);
            mAudioEncoder.configure(mAudioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        } catch (IOException e) {
            e.printStackTrace();
            mAudioFormat = null;
            mAudioEncoder = null;
            return false;
        }

        mAudioBufInfo = new MediaCodec.BufferInfo();
        mAudioPts = 0;
        return true;
    }

    public void onVideoSizeChanged(int width, int height) {
        LogUtils.w(TAG, mExternalTag + "onVideoSizeChanged " + width + "x" + height);
        if (mGLThread == null) {
            return;
        }
        mGLThread.onWindowResize(width, height);
        mGLThread.requestRender();
    }

    /**
     * 获取当前已编码时长，单位：毫秒
     */
    public long getEncodeTimeMills() {
        return mEncodeTimeMills;
    }

    public byte[] getSPS() {
        return mSPS;
    }

    public byte[] getPPS() {
        return mPPS;
    }

    protected void stopEncode() {
        if (isReleased) {
            LogUtils.e(TAG, mExternalTag + "stopEncode but this encoder is already released!");
            return;
        }
        mWorkHandler.removeMessages(HANDLE_START_ENCODE);
        Message msg = mWorkHandler.obtainMessage(HANDLE_STOP_ENCODE);
        mWorkHandler.sendMessage(msg);
    }

    private void handleStopEncode() {
        LogUtils.w(TAG, mExternalTag + "handleStopEncode");
        if (!isRecording) {
            LogUtils.w(TAG, "No need to stop encoder again: it's already stopped!");
            return;
        }
        isRecording = false;

        isGLThreadExiting = false;
        if (mGLThread != null) {
            isGLThreadExiting = true;
            mGLThread.requestExit(new WeGLThread.OnExitedListener() {
                @Override
                public void onExited(WeGLThread glThread) {
                    LogUtils.w(TAG, mExternalTag + "mGLThread onExited: " + glThread.hashCode());
                    isGLThreadExiting = false;
                }
            });
        }

        isVideoEncThreadExiting = false;
        if (mVideoEncodeThread != null) {
            isVideoEncThreadExiting = true;
            mVideoEncodeThread.requestExit(new OnThreadExitedListener() {
                @Override
                public void onExited(Thread thread) {
                    LogUtils.w(TAG, mExternalTag + "mVideoEncodeThread onExited: " + thread.hashCode());
                    isVideoEncThreadExiting = false;
                }
            });
        }

        isAudioEncThreadExiting = false;
        if (mAudioEncodeThread != null) {
            isAudioEncThreadExiting = true;
            mAudioEncodeThread.requestExit(new OnThreadExitedListener() {
                @Override
                public void onExited(Thread thread) {
                    LogUtils.w(TAG, mExternalTag + "mAudioEncodeThread onExited: " + thread.hashCode());
                    isAudioEncThreadExiting = false;
                }
            });
        }

        while (isGLThreadExiting || isVideoEncThreadExiting || isAudioEncThreadExiting) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        releaseOnGLThreadExit();
        releaseOnMediaEncThreadExit();
    }

    private void releaseOnGLThreadExit() {
        LogUtils.w(TAG, mExternalTag + "releaseOnGLThreadExit");
        mShareContext = null;
        mRenderer = null;
        mGLThread = null;
        mSurface = null;
    }

    private void releaseOnMediaEncThreadExit() {
        LogUtils.w(TAG, mExternalTag + "releaseOnMediaEncThreadExit");
        mVideoEncodeThread = null;
        mAudioEncodeThread = null;

        if (mVideoEncoder != null) {
            try {
                mVideoEncoder.stop();
                mVideoEncoder.release();
            } catch (Exception e) {
                e.printStackTrace();
            }
            mVideoEncoder = null;
        }
        if (mAudioEncoder != null) {
            try {
                mAudioEncoder.stop();
                mAudioEncoder.release();
            } catch (Exception e) {
                e.printStackTrace();
            }
            mAudioEncoder = null;
        }
        mVideoFormat = null;
        mVideoBufInfo = null;
        mAudioFormat = null;
        mAudioBufInfo = null;

        needEncodeAudio = false;
        isAudioEncoderStarted = false;

        mAudioSampleRate = 0;
        mAudioChannelNums = 0;
        mAudioBitsPerSample = 0;
        mPcmMaxBytesPerCallback = 0;
        mEncodeTimeMills = 0;
    }

    protected void release() {
        if (isReleased) {
            return;
        }
        // 首先置总的标志位，阻止消息队列的正常消费
        isReleased = true;

        // 然后抛到工作线程做释放工作
        mWorkHandler.removeCallbacksAndMessages(null);
        Message msg = mWorkHandler.obtainMessage(HANDLE_RELEASE);
        mWorkHandler.sendMessage(msg);
    }

    private void handleRelease() {
        handleStopEncode();

        mWorkHandler.removeCallbacksAndMessages(null);
        try {
            mWorkThread.quit();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String exceptionPrefix() {
        return TAG + " tid=" + android.os.Process.myTid() + " ";
    }

    public interface OnThreadExitedListener {
        void onExited(Thread thread);
    }

    public interface OnEncodeDataListener {
        void onVideoDataCall(byte[] data, int size, boolean isKeyframe);

        void onAudioDataCall(byte[] data, int size);
    }

    /**
     * 要编码的视频内容 GLES 渲染线程
     */
    static class GLThread extends WeGLThread {

        private WeakReference<WeGLVideoPushEncoder> mWeakReference;

        public GLThread(WeakReference<WeGLVideoPushEncoder> reference, String externalTag) {
            super(externalTag);
            this.mWeakReference = reference;
        }

        @Override
        protected EGLContext getEGLContext() {
            WeGLVideoPushEncoder encoder = mWeakReference.get();
            if (encoder == null) {
                LogUtils.e(TAG, mExternalTag + "GLThread getEGLContext failed: WeGLVideoPushEncoder got from mWeakReference is null");
                return null;
            }
            return encoder.mShareContext;
        }

        @Override
        protected Surface getSurface() {
            WeGLVideoPushEncoder encoder = mWeakReference.get();
            if (encoder == null) {
                LogUtils.e(TAG, mExternalTag + "GLThread getSurface failed: WeGLVideoPushEncoder got from mWeakReference is null");
                return null;
            }
            return encoder.mSurface;
        }

        @Override
        protected int getRenderMode() {
            WeGLVideoPushEncoder encoder = mWeakReference.get();
            if (encoder == null) {
                LogUtils.e(TAG, mExternalTag + "GLThread getRenderMode failed: WeGLVideoPushEncoder got from mWeakReference is null");
                return WeGLRenderer.RENDERMODE_CONTINUOUSLY;
            }
            return encoder.mRenderMode;
        }

        @Override
        protected WeGLRenderer getRenderer() {
            WeGLVideoPushEncoder encoder = mWeakReference.get();
            if (encoder == null) {
                LogUtils.e(TAG, mExternalTag + "GLThread getRenderer failed: WeGLVideoPushEncoder got from mWeakReference is null");
                return null;
            }
            return encoder.mRenderer;
        }

    }

    /**
     * 音视频编码线程
     */
    static class MediaEncodeThread extends Thread {
        private String mTag;

        public static final int TYPE_VIDEO = 0;
        public static final int TYPE_AUDIO = 1;
        private int mMediaType;

        private WeakReference<WeGLVideoPushEncoder> mWeakReference;
        private MediaCodec mEncoder;
        private MediaCodec.BufferInfo mBufInfo;

        private int mOutputBufIndex;
        private long mStartPts = 0;

        private boolean isShouldExit;
        private boolean isExited;
        // For video avc
        private boolean isKeyframe;

        private byte[] mEncodeBytes;
        private int mEncodeSize;

        private OnThreadExitedListener mOnExitedListener;
        private OnEncodeDataListener mOnEncodeDataListener;

        public MediaEncodeThread(WeakReference<WeGLVideoPushEncoder> weakReference, int mediaType, String tag) {
            this.mWeakReference = weakReference;
            this.mMediaType = mediaType;
            this.mTag = tag;

            if (mMediaType == TYPE_VIDEO) {
                mEncoder = mWeakReference.get().mVideoEncoder;
                mBufInfo = mWeakReference.get().mVideoBufInfo;
            } else {
                mEncoder = mWeakReference.get().mAudioEncoder;
                mBufInfo = mWeakReference.get().mAudioBufInfo;
            }
        }

        @Override
        public void run() {
            setName(mTag + " " + android.os.Process.myTid());
            LogUtils.w(mTag, "encode thread starting tid=" + android.os.Process.myTid());
            try {
                guardedRun();
            } catch (Throwable e) {
                LogUtils.e(mTag, "catch exception: " + e.toString());
                if (isShouldExit) {
                    LogUtils.e(mTag, "Because isShouldExit = true, so ignore this exception");
                } else {
                    throw e;
                }
            } finally {
                release();
            }
            LogUtils.w(mTag, "encode thread end tid=" + android.os.Process.myTid());
        }

        private void guardedRun() {
            mEncoder.start();
            if (mMediaType == TYPE_AUDIO) {
                WeGLVideoPushEncoder master = mWeakReference.get();
                if (master == null) {
                    LogUtils.e(mTag, "WeGLVideoPushEncoder got from mWeakReference is null, so exit!");
                    return;
                }
                master.isAudioEncoderStarted = true;
            } else {
                isKeyframe = false;
            }
            while (!isShouldExit) {
                WeGLVideoPushEncoder master = mWeakReference.get();
                if (master == null) {
                    // 主类已被回收，因为某种原因没有来得及置退出标志，这里就直接退出线程
                    LogUtils.e(mTag, "WeGLVideoPushEncoder got from mWeakReference is null, so exit!");
                    return;
                }

                if (mEncoder == null || mBufInfo == null) {
                    LogUtils.e(mTag, "mEncoder or mBufInfo got from mWeakReference is null!");
                    return;
                }

                try {
                    mOutputBufIndex = mEncoder.dequeueOutputBuffer(mBufInfo, 0);
                    if (mOutputBufIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        LogUtils.w(mTag, "mOutputBufIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED");
                        if (mMediaType == TYPE_VIDEO) {
                            ByteBuffer spsBuf = mEncoder.getOutputFormat().getByteBuffer("csd-0");
                            master.mSPS = new byte[spsBuf.remaining()];
                            spsBuf.get(master.mSPS, 0, master.mSPS.length);
                            LogUtils.d(mTag, "SPS-->:" + HexUtils.byteToHex(master.mSPS, 20));

                            ByteBuffer ppsBuf = mEncoder.getOutputFormat().getByteBuffer("csd-1");
                            master.mPPS = new byte[ppsBuf.remaining()];
                            ppsBuf.get(master.mPPS, 0, master.mPPS.length);
                            LogUtils.d(mTag, "PPS-->:" + HexUtils.byteToHex(master.mPPS, 20));
                        }
                    } else {
                        while (mOutputBufIndex >= 0) {
                            ByteBuffer outBuffer = mEncoder.getOutputBuffers()[mOutputBufIndex];
                            outBuffer.position(mBufInfo.offset);
                            outBuffer.limit(mBufInfo.offset + mBufInfo.size);

                            if (mMediaType == TYPE_VIDEO) {
                                isKeyframe = mBufInfo.flags == MediaCodec.BUFFER_FLAG_KEY_FRAME;
                            }

                            if (mStartPts == 0) {
                                mStartPts = mBufInfo.presentationTimeUs;
                            }
                            mBufInfo.presentationTimeUs = mBufInfo.presentationTimeUs - mStartPts;
                            if (mMediaType == TYPE_VIDEO) {
                                // 只针对一个主 track 写时间就够了
                                master.mEncodeTimeMills = mBufInfo.presentationTimeUs / 1000;
                            }

                            mEncodeSize = outBuffer.remaining();
                            if (mEncodeBytes == null || mEncodeBytes.length < mEncodeSize) {
                                mEncodeBytes = new byte[mEncodeSize];
                            }
                            outBuffer.get(mEncodeBytes, 0, mEncodeSize);
                            mOnEncodeDataListener = master.mOnEncodeDataListener;
                            if (mOnEncodeDataListener != null) {
                                if (mMediaType == TYPE_VIDEO) {
                                    mOnEncodeDataListener.onVideoDataCall(mEncodeBytes, mEncodeSize, isKeyframe);
                                } else {
                                    mOnEncodeDataListener.onAudioDataCall(mEncodeBytes, mEncodeSize);
                                }
                            }

                            mEncoder.releaseOutputBuffer(mOutputBufIndex, false);
                            mOutputBufIndex = mEncoder.dequeueOutputBuffer(mBufInfo, 0);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        public void requestExit(OnThreadExitedListener listener) {
            this.mOnExitedListener = listener;
            if (isExited) {
                if (mOnExitedListener != null) {
                    mOnExitedListener.onExited(this);
                }
            } else {
                isShouldExit = true;
            }
        }

        private void release() {
            // mEncoder 系列在这里只置空，具体回收交给外部主类释放
            mEncoder = null;
            mBufInfo = null;
            mWeakReference = null;

            mEncodeBytes = null;
            mOnEncodeDataListener = null;

            isExited = true;
            if (mOnExitedListener != null) {
                mOnExitedListener.onExited(this);
            }
        }

    }

    protected void onAudioPCMDataCall(byte[] pcmData, int size) {
        if (!needEncodeAudio || !isRecording || mAudioEncoder == null || !isAudioEncoderStarted
                || pcmData == null || size <= 0) {
            return;
        }
        try {
            // 获取输入 buffer，不超时等待
            int inputBufferIndex = mAudioEncoder.dequeueInputBuffer(10 * 1000);
            if (inputBufferIndex < 0) {
                LogUtils.e(TAG, "onAudioPCMDataCall dequeueInputBuffer failed ret=" + inputBufferIndex);
                return;
            }

            // 成功获取输入 buffer后，填入要处理的数据
            ByteBuffer inputBuffer = mAudioEncoder.getInputBuffers()[inputBufferIndex];
            inputBuffer.clear();
            inputBuffer.put(pcmData);

            // 提交数据并释放输入 buffer
            mAudioPts += (long) (1.0f * size / mAudioBytesPerSecond * 1000000);
            mAudioEncoder.queueInputBuffer(
                    inputBufferIndex, 0, size, mAudioPts, 0);
        } catch (Exception e) {
            LogUtils.e(TAG, "onAudioPCMDataCall exception: " + e.toString());
            e.printStackTrace();
        }
    }

}
