package com.wtz.videomaker;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.view.Display;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.suke.widget.SwitchButton;
import com.wtz.ffmpegapi.WePlayer;
import com.wtz.libvideomaker.imagevideo.WeImageVideoView;
import com.wtz.libvideomaker.recorder.WeVideoRecorder;
import com.wtz.libvideomaker.renderer.OnScreenRenderer;
import com.wtz.libvideomaker.renderer.filters.WatermarkRenderer;
import com.wtz.libvideomaker.utils.LogUtils;
import com.wtz.libvideomaker.utils.ScreenUtils;
import com.wtz.videomaker.utils.DateTimeUtil;
import com.wtz.videomaker.utils.FileChooser;
import com.wtz.videomaker.utils.ImageChooser;
import com.wtz.videomaker.utils.PermissionChecker;
import com.wtz.videomaker.utils.PermissionHandler;

import java.io.File;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;


public class ImageVideoActivity extends AppCompatActivity implements PermissionHandler.PermissionHandleListener,
        View.OnClickListener, RadioGroup.OnCheckedChangeListener, OnScreenRenderer.ScreenTextureChangeListener,
        WePlayer.OnPCMDataCallListener {
    private static final String TAG = ImageVideoActivity.class.getSimpleName();

    private PermissionHandler mPermissionHandler;

    private Display mDisplay;

    private View mContentView;
    private int mContentHeight;
    private boolean needHideNav;

    private WeImageVideoView mWeImageVideoView;
    private Button mSelectImageButton;
    private Button mLastImageButton;
    private Button mNextImageButton;
    private SwitchButton mAutoPlayImageSwitch;
    private EditText mPlayImageIntevalET;
    private Spinner mPlayImageIntevalSP;
    private TextView mCurrentIndexTV;
    private List<String> mImageList;
    private int mImageIndex;
    private boolean isAutoPlayImage = true;
    private static final String[] TIME_UNIT_ARRAY = new String[]{
            "秒", "毫秒"
    };
    private static final int INDEX_TIME_UNIT_SECOND = 0;
    private static final int INDEX_TIME_UNIT_MILLIS = 1;
    private int mTimeUnitIndex = INDEX_TIME_UNIT_SECOND;
    private static final int DEFAULT_IMAGE_INTERVAL_VALUE = 2;
    private static final int MIN_IMAGE_INTERVAL_MILLIS = 40;// 小于40毫秒会严重掉帧
    private int mImageIntervalValue = DEFAULT_IMAGE_INTERVAL_VALUE;
    private int mImageIntervalMills;
    // request_code 不能与其它重复
    private static final int REQUEST_CODE_SELECT_IMG = 1;

    private WeVideoRecorder mWeVideoRecorder;
    private String mSaveVideoDir;
    private boolean isRecording;
    private Button mRecordButton;
    private View mIndicatorLayout;
    private View mIndicatorLight;
    private TextView mIndicatorTime;

    private WePlayer mWePlayer;
    private int mSelectMusicRequestCode;
    private String mMusicUrl;
    private int mMusicDuration;
    private int mSeekPosition;
    private int mSampleRate;
    private int mChannelNums;
    private int mBitsPerSample;
    private int mPcmMaxBytesPerCallback;
    private boolean isMusicPrepared;
    private boolean startEncodeOnPrepared;
    private boolean isMusicLoading;
    private boolean isMusicSeeking;
    private TextView mMusicNameView;
    private TextView mMusicPlayTimeView;
    private TextView mMusicTotalTimeView;
    private SeekBar mMusicSeekBar;
    private Button mSelectMusicButton;
    private Button mControlMusicButton;

    private int mTextMarkCorner = WatermarkRenderer.CORNER_RIGHT_TOP;
    private static final int TEXT_MARK_SIZE_RESID = R.dimen.sp_12;
    private static final int TEXT_MARK_PADDING_X = R.dimen.dp_10;
    private static final int TEXT_MARK_PADDING_Y = R.dimen.dp_6;
    private static final int TEXT_MARK_MARIN = R.dimen.dp_5;

    private static final int MSG_UPDATE_RECORD_INFO = 0;
    private static final int MSG_UPDATE_MUSIC_TIME = 1;
    private static final int MSG_AUTO_PLAY_IMAGE = 2;
    private static final int UPDATE_RECORD_INFO_INTERVAL = 500;
    private static final int UPDATE_MUSIC_TIME_INTERVAL = 500;
    private WeakHandler mUIHandler = new WeakHandler(this);

    static class WeakHandler extends Handler {
        private final WeakReference<ImageVideoActivity> weakReference;

        // 为了避免非静态的内部类和匿名内部类隐式持有外部类的引用，改用静态类
        // 又因为内部类是静态类，所以不能直接操作宿主类中的方法了，
        // 于是需要传入宿主类实例的弱引用来操作宿主类中的方法
        public WeakHandler(ImageVideoActivity host) {
            super(Looper.getMainLooper());
            this.weakReference = new WeakReference(host);
        }

        @Override
        public void handleMessage(Message msg) {
            ImageVideoActivity host = weakReference.get();
            if (host == null) {
                return;
            }

            switch (msg.what) {
                case MSG_UPDATE_RECORD_INFO:
                    host.updateRecordInfo();
                    removeMessages(MSG_UPDATE_RECORD_INFO);
                    sendEmptyMessageDelayed(MSG_UPDATE_RECORD_INFO, UPDATE_RECORD_INFO_INTERVAL);
                    break;
                case MSG_UPDATE_MUSIC_TIME:
                    host.updateMusicTime();
                    removeMessages(MSG_UPDATE_MUSIC_TIME);
                    sendEmptyMessageDelayed(MSG_UPDATE_MUSIC_TIME, UPDATE_MUSIC_TIME_INTERVAL);
                    break;
                case MSG_AUTO_PLAY_IMAGE:
                    host.playNextImage();
                    removeMessages(MSG_AUTO_PLAY_IMAGE);
                    sendEmptyMessageDelayed(MSG_AUTO_PLAY_IMAGE, host.mImageIntervalMills);
                    break;
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        LogUtils.d(TAG, "onCreate");
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        ScreenUtils.hideNavigationBar(ImageVideoActivity.this); // 隐藏导航栏
        needHideNav = false;

        setContentView(R.layout.activity_image_video);
        initViews();

        File savePath = new File(Environment.getExternalStorageDirectory(), "WePhotos");
        mSaveVideoDir = savePath.getAbsolutePath();
        mWeVideoRecorder = new WeVideoRecorder(this);
        mWeVideoRecorder.setSaveVideoDir(mSaveVideoDir);

        mPermissionHandler = new PermissionHandler(this, this);
        mPermissionHandler.handleCommonPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE);

        LocalBroadcastManager.getInstance(this).registerReceiver(
                mBroadcastReceiver, new IntentFilter(FileChooser.ACTION_FILE_CHOOSE_RESULT));
    }

    private void initViews() {
        mContentView = findViewById(android.R.id.content);
        //监听 content 视图树的变化
        mContentView.getViewTreeObserver().addOnGlobalLayoutListener(mOnGlobalLayoutListener);

        mWeImageVideoView = findViewById(R.id.we_image_video_view);
        mWeImageVideoView.setScreenTextureChangeListener(this);
        String date = new SimpleDateFormat("yyyy/MM/dd").format(new Date());
        int textColor = Color.parseColor("#FFFF00");
        int textBgColor = Color.parseColor("#33DEDEDE");
        int textSize = (int) (getResources().getDimension(TEXT_MARK_SIZE_RESID) + 0.5f);
        int textPaddingX = (int) (getResources().getDimension(TEXT_MARK_PADDING_X) + 0.5f);
        int textPaddingY = (int) (getResources().getDimension(TEXT_MARK_PADDING_Y) + 0.5f);
        int textMargin = (int) (getResources().getDimension(TEXT_MARK_MARIN) + 0.5f);
        mWeImageVideoView.setTextMark("WeCamera " + date, textSize, textPaddingX,
                textPaddingX, textPaddingY, textPaddingY, textColor, textBgColor,
                mTextMarkCorner, textMargin, textMargin);

        mSelectImageButton = findViewById(R.id.btn_select_image);
        mSelectImageButton.setOnClickListener(this);
        mLastImageButton = findViewById(R.id.btn_last_image);
        mLastImageButton.setOnClickListener(this);
        mNextImageButton = findViewById(R.id.btn_next_image);
        mNextImageButton.setOnClickListener(this);

        mAutoPlayImageSwitch = findViewById(R.id.switch_auto_play_image);
        mAutoPlayImageSwitch.setChecked(isAutoPlayImage);
        mAutoPlayImageSwitch.setOnCheckedChangeListener(new SwitchButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(SwitchButton view, boolean isChecked) {
                LogUtils.d(TAG, "mAutoPlayImageSwitch onCheckedChanged " + isChecked);
                isAutoPlayImage = isChecked;
                if (isAutoPlayImage) {
                    if (isRecording) {
                        startAutoPlayImage();
                    }
                } else {
                    stopAutoPlayImage();
                }
            }
        });

        mPlayImageIntevalET = findViewById(R.id.et_image_interval);
        mPlayImageIntevalET.setText("" + DEFAULT_IMAGE_INTERVAL_VALUE);

        mPlayImageIntevalSP = findViewById(R.id.spinner_time_unit);
        mPlayImageIntevalSP.setSelection(INDEX_TIME_UNIT_SECOND);
        mPlayImageIntevalSP.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                LogUtils.d(TAG, "mPlayImageIntevalSP onItemSelected " + position);
                mTimeUnitIndex = position;
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<String>(
                this, R.layout.item_spinner_time_unit, TIME_UNIT_ARRAY);
        mPlayImageIntevalSP.setAdapter(spinnerAdapter);

        mRecordButton = findViewById(R.id.btn_record);
        mRecordButton.setOnClickListener(this);

        mIndicatorLayout = findViewById(R.id.ll_indicator_layout);
        mIndicatorLight = findViewById(R.id.v_record_indicator_light);
        mIndicatorTime = findViewById(R.id.tv_record_time);

        mSelectMusicButton = findViewById(R.id.btn_select_music);
        mSelectMusicButton.setOnClickListener(this);
        mMusicNameView = findViewById(R.id.tv_music_name);

        mControlMusicButton = findViewById(R.id.btn_music_play_control);
        mControlMusicButton.setOnClickListener(this);
        mMusicPlayTimeView = findViewById(R.id.tv_music_play_time);
        mMusicTotalTimeView = findViewById(R.id.tv_music_total_time);
        mMusicSeekBar = findViewById(R.id.seek_bar_music);
        mMusicSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                if (isMusicSeeking) {
                    // 因为主动 seek 导致的 seekbar 变化，此时只需要更新时间
                    updateMusicTime();
                } else {
                    // 因为实际播放时间变化而设置 seekbar 导致变化，什么都不用做
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                LogUtils.d(TAG, "mMusicSeekBar onStartTrackingTouch");
                isMusicSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                LogUtils.d(TAG, "mMusicSeekBar onStopTrackingTouch");
                if (mWePlayer != null && isMusicPrepared) {
                    mWePlayer.seekTo(seekBar.getProgress());
                } else {
                    mSeekPosition = seekBar.getProgress();
                    isMusicSeeking = false;
                }
            }
        });

        mCurrentIndexTV = findViewById(R.id.tv_current_index);
    }

    private ViewTreeObserver.OnGlobalLayoutListener mOnGlobalLayoutListener = new
            ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    if (mContentView.getHeight() != mContentHeight) {
                        needHideNav = true;
                        mContentHeight = mContentView.getHeight();
                    }
                }
            };

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        LogUtils.d(TAG, "onRequestPermissionsResult requestCode=" + requestCode);
        mPermissionHandler.handleActivityRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        LogUtils.d(TAG, "onActivityResult requestCode=" + requestCode + ", resultCode=" + resultCode
                + ", data=" + data);
        mPermissionHandler.handleActivityResult(requestCode);
        if (resultCode == RESULT_OK && requestCode == REQUEST_CODE_SELECT_IMG) {
            mImageList = data.getStringArrayListExtra(ImageChooser.KEY_IMAGE_LIST);
            playFirstImage();
        }
    }

    @Override
    public void onPermissionResult(String permission, PermissionChecker.PermissionState state) {
        LogUtils.w(TAG, "onPermissionResult " + permission + " state is " + state);
        if (Manifest.permission.WRITE_EXTERNAL_STORAGE.equals(permission)) {
            if (state == PermissionChecker.PermissionState.USER_NOT_GRANTED) {
                LogUtils.e(TAG, "onPermissionResult " + permission + " state is USER_NOT_GRANTED!");
                finish();
            }
        }
    }

    @Override
    protected void onStart() {
        LogUtils.d(TAG, "onStart");
        super.onStart();
    }

    @Override
    protected void onResume() {
        LogUtils.d(TAG, "onResume");
        super.onResume();
        // 熄屏再恢复后，导航栏会出来
        if (needHideNav) {
            ScreenUtils.hideNavigationBar(this);
            needHideNav = false;
        }
        mWeImageVideoView.onActivityResume();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        LogUtils.d(TAG, "onWindowFocusChanged hasFocus=" + hasFocus);
        if (hasFocus) {
            if (needHideNav) {
                ScreenUtils.hideNavigationBar(this);
                needHideNav = false;
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (MotionEvent.ACTION_DOWN == event.getAction()) {
            if (needHideNav) {
                ScreenUtils.hideNavigationBar(this);
                needHideNav = false;
            }
        }
        return super.onTouchEvent(event);
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.btn_select_image:
                selectImage();
                break;

            case R.id.btn_last_image:
                playLastImage();
                break;

            case R.id.btn_next_image:
                playNextImage();
                break;

            case R.id.btn_record:
                record();
                break;

            case R.id.btn_select_music:
                mSelectMusicRequestCode = FileChooser.chooseAudio(this);
                break;

            case R.id.btn_music_play_control:
                playPauseMusic();
                break;

        }
    }

    private void selectImage() {
        startActivityForResult(
                new Intent(ImageVideoActivity.this, ImageChooser.class),
                REQUEST_CODE_SELECT_IMG);
    }

    private void startAutoPlayImage() {
        mUIHandler.sendEmptyMessageDelayed(MSG_AUTO_PLAY_IMAGE, mImageIntervalMills);
    }

    private void stopAutoPlayImage() {
        mUIHandler.removeMessages(MSG_AUTO_PLAY_IMAGE);
    }

    private void playLastImage() {
        if (mImageList == null || mImageList.size() == 0) return;

        mImageIndex--;
        if (mImageIndex < 0) {
            mImageIndex = mImageList.size() - 1;
        }
        mCurrentIndexTV.setText((mImageIndex + 1) + "/" + mImageList.size());
        mWeImageVideoView.setImagePath(mImageList.get(mImageIndex));
    }

    private void playFirstImage() {
        if (mImageList == null || mImageList.size() == 0) return;

        mCurrentIndexTV.setText((mImageIndex + 1) + "/" + mImageList.size());
        mWeImageVideoView.setImagePath(mImageList.get(0));
    }

    private void playNextImage() {
        if (mImageList == null || mImageList.size() == 0) return;

        mImageIndex++;
        if (mImageIndex >= mImageList.size()) {
            mImageIndex = 0;
        }
        mCurrentIndexTV.setText((mImageIndex + 1) + "/" + mImageList.size());
        mWeImageVideoView.setImagePath(mImageList.get(mImageIndex));
    }

    private void record() {
        if (isRecording) {
            stopRecord();
        } else {
            startRecord();
        }
    }

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            LogUtils.d(TAG, "mBroadcastReceiver onReceive: " + action);
            if (FileChooser.ACTION_FILE_CHOOSE_RESULT.equals(action)) {
                int code = intent.getIntExtra(FileChooser.RESULT_REQUEST_CODE, -1);
                if (code == mSelectMusicRequestCode) {
                    String url = intent.getStringExtra(FileChooser.RESULT_FILE_PATH);
                    LogUtils.d(TAG, "clickImageItem music url: " + url);
                    if (!TextUtils.isEmpty(url)) {
                        mMusicUrl = url;
                        openMusic(mMusicUrl);
                    }
                }
            }
        }
    };

    private void openMusic(String url) {
        if (TextUtils.isEmpty(url)) {
            return;
        }
        isMusicPrepared = false;
        isMusicLoading = false;
        if (mWePlayer == null) {
            mWePlayer = new WePlayer(true);
            mWePlayer.enablePCMDataCall(true);//打开 PCM 数据回调
            mWePlayer.setOnPCMDataCallListener(this);
            mWePlayer.setOnPreparedListener(new WePlayer.OnPreparedListener() {
                @Override
                public void onPrepared() {
                    LogUtils.d(TAG, "WePlayer onPrepared");
                    mSampleRate = mWePlayer.getAudioSampleRate();
                    mChannelNums = mWePlayer.getAudioChannelNums();
                    mBitsPerSample = mWePlayer.getAudioBitsPerSample();
                    mPcmMaxBytesPerCallback = mWePlayer.getPcmMaxBytesPerCallback();
                    isMusicPrepared = true;

                    if (mSeekPosition > 0) {
                        mWePlayer.seekTo(mSeekPosition);
                        mSeekPosition = 0;
                    }
                    mWePlayer.start();
                    if (startEncodeOnPrepared) {
                        startEncodeOnPrepared = false;
                        startVideoEncode(true);
                    }

                    mMusicDuration = mWePlayer.getDuration();
                    LogUtils.d(TAG, "mMusicDuration=" + mMusicDuration);
                    mMusicSeekBar.setMax(mMusicDuration);
                    mMusicTotalTimeView.setText(DateTimeUtil.changeRemainTimeToMs(mMusicDuration));
                    startUpdateTime();
                    mControlMusicButton.setText(R.string.pause_music);
                }
            });
            mWePlayer.setOnPlayLoadingListener(new WePlayer.OnPlayLoadingListener() {
                @Override
                public void onPlayLoading(boolean isLoading) {
                    LogUtils.d(TAG, "WePlayer onPlayLoading: " + isLoading);
                    isMusicLoading = isLoading;
                    if (isMusicLoading) {
                        stopUpdateTime();
                    } else {
                        startUpdateTime();
                    }
                }
            });
            mWePlayer.setOnSeekCompleteListener(new WePlayer.OnSeekCompleteListener() {
                @Override
                public void onSeekComplete() {
                    LogUtils.d(TAG, "mWePlayer onSeekComplete");
                    isMusicSeeking = false;
                }
            });
            mWePlayer.setOnErrorListener(new WePlayer.OnErrorListener() {
                @Override
                public void onError(int code, String msg) {
                    LogUtils.e(TAG, "WePlayer onError: " + code + "; " + msg);
                }
            });
            mWePlayer.setOnCompletionListener(new WePlayer.OnCompletionListener() {
                @Override
                public void onCompletion() {
                    LogUtils.d(TAG, "WePlayer onCompletion");
                    mWePlayer.start();
                }
            });
        } else {
            mWePlayer.reset();
        }
        String name = url;
        int index = url.lastIndexOf("/");
        if (index >= 0) {
            name = url.substring(index + 1);
        }
        mMusicNameView.setText(name);

        mWePlayer.setDataSource(url);
        mWePlayer.prepareAsync();
    }

    private void playPauseMusic() {
        if (TextUtils.isEmpty(mMusicUrl)) {
            Toast.makeText(this, "请选择音乐", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isMusicPrepared) {
            openMusic(mMusicUrl);
        } else {
            if (mWePlayer.isPlaying()) {
                pauseMusic();
            } else {
                startMusic();
            }
        }
    }

    private void startMusic() {
        if (mWePlayer != null) {
            mWePlayer.start();
        }
        startUpdateTime();
        mControlMusicButton.setText(R.string.pause_music);
    }

    private void pauseMusic() {
        if (mWePlayer != null) {
            mWePlayer.pause();
        }
        stopUpdateTime();
        mControlMusicButton.setText(R.string.play_music);
    }

    private void stopMusic() {
        if (mWePlayer != null) {
            mWePlayer.stop();
        }
        isMusicPrepared = false;
        mSeekPosition = 0;
        stopUpdateTime();
        mControlMusicButton.setText(R.string.play_music);
        mMusicSeekBar.setProgress(0);
        mMusicPlayTimeView.setText(R.string.zero_time_ms);
    }

    private void startUpdateTime() {
        mUIHandler.sendEmptyMessage(MSG_UPDATE_MUSIC_TIME);
    }

    private void stopUpdateTime() {
        mUIHandler.removeMessages(MSG_UPDATE_MUSIC_TIME);
    }

    private void updateMusicTime() {
        if (mWePlayer == null || isMusicLoading) return;

        if (isMusicSeeking) {
            // seek 时 seekbar 会自动更新位置，只需要根据 seek 位置更新时间
            String currentPosition = DateTimeUtil.changeRemainTimeToMs(mMusicSeekBar.getProgress());
            mMusicPlayTimeView.setText(currentPosition);
        } else if (mWePlayer.isPlaying()) {
            // 没有 seek 时，如果还在播放中，就正常按实际播放时间更新时间和 seekbar
            int position = mWePlayer.getCurrentPosition();
            String currentPosition = DateTimeUtil.changeRemainTimeToMs(position);
            mMusicPlayTimeView.setText(currentPosition);
            mMusicSeekBar.setProgress(position);
        } else {
            // 既没有 seek，也没有播放，那就不更新
        }
    }

    private void startRecord() {
        if (mImageList == null) {
            Toast.makeText(this, R.string.please_select_image, Toast.LENGTH_SHORT).show();
            return;
        }

        parseEditTime(mPlayImageIntevalET.getText().toString());
        if (mTimeUnitIndex == INDEX_TIME_UNIT_SECOND) {
            mImageIntervalMills = mImageIntervalValue * 1000;
        } else if (mTimeUnitIndex == INDEX_TIME_UNIT_MILLIS) {
            mImageIntervalMills = mImageIntervalValue;
        }
        if (mImageIntervalMills < MIN_IMAGE_INTERVAL_MILLIS) {
            String text = getString(R.string.image_interval_too_small, MIN_IMAGE_INTERVAL_MILLIS);
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
            return;
        }

        isRecording = true;
        fixCurrentDirection();

        if (isAutoPlayImage) {
            startAutoPlayImage();
        }

        if (isMusicPrepared) {
            if (!mWePlayer.isPlaying()) {
                mWePlayer.start();
            }
            startVideoEncode(true);
        } else if (!TextUtils.isEmpty(mMusicUrl)) {
            startEncodeOnPrepared = true;
            openMusic(mMusicUrl);
        } else {
            startVideoEncode(false);
        }

        mRecordButton.setText(R.string.stop_record);
        mIndicatorLayout.setVisibility(View.VISIBLE);
        startUpdateRecordInfo();
    }

    private void parseEditTime(String time) {
        if (TextUtils.isEmpty(time)) {
            mImageIntervalValue = 0;
        } else {
            try {
                mImageIntervalValue = (int) (Float.parseFloat(time));
            } catch (NumberFormatException e) {
                e.printStackTrace();
            }
        }
    }

    private void startVideoEncode(boolean hasAudio) {
        if (hasAudio) {
            mWeVideoRecorder.setAudioParams(mSampleRate, mChannelNums, mBitsPerSample, mPcmMaxBytesPerCallback);
        }
        mWeVideoRecorder.setExternalTextureId(mWeImageVideoView.getScreenTextureId());
        mWeVideoRecorder.startEncode(mWeImageVideoView.getSharedEGLContext(),
                mWeImageVideoView.getWidth(), mWeImageVideoView.getHeight());
    }

    @Override
    public void onPCMDataCall(byte[] bytes, int size) {
        if (mWeVideoRecorder != null) {
            mWeVideoRecorder.onAudioPCMDataCall(bytes, size);
        }
    }

    private void stopRecord() {
        isRecording = false;
        startEncodeOnPrepared = false;
        mWeVideoRecorder.stopEncode();
        stopMusic();
        stopAutoPlayImage();

        stopUpdateRecordInfo();
        mRecordButton.setText(R.string.start_record);
        mIndicatorLayout.setVisibility(View.GONE);
        mIndicatorTime.setText(R.string.zero_time_hms);

        resumeUserDirection();
    }

    private void startUpdateRecordInfo() {
        mUIHandler.sendEmptyMessage(MSG_UPDATE_RECORD_INFO);
    }

    private void stopUpdateRecordInfo() {
        mUIHandler.removeMessages(MSG_UPDATE_RECORD_INFO);
    }


    private void fixCurrentDirection() {
        int currentAngle = getRotationAngle();
        switch (currentAngle) {
            case Surface.ROTATION_90:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                break;

            case Surface.ROTATION_270:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
                break;

            case Surface.ROTATION_0:
            default:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                break;
        }
    }

    private void resumeUserDirection() {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER);
    }

    private int getRotationAngle() {
        if (mDisplay == null) {
            mDisplay = ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        }
        return mDisplay.getRotation();
    }

    private void updateRecordInfo() {
        if (mWeVideoRecorder != null) {
            String time = DateTimeUtil.changeRemainTimeToHms(mWeVideoRecorder.getEncodeTimeMills());
            mIndicatorTime.setText(time);
            if (mIndicatorLight.getVisibility() == View.INVISIBLE) {
                mIndicatorLight.setVisibility(View.VISIBLE);
            } else {
                mIndicatorLight.setVisibility(View.INVISIBLE);
            }
        }
    }

    @Override
    public void onCheckedChanged(RadioGroup group, int checkedId) {
        LogUtils.d(TAG, "onCheckedChanged " + checkedId);
//        switch (checkedId) {
//            case R.id.rb_normal:
//                break;
//
//            case R.id.rb_gray:
//                break;
//
//            case R.id.rb_color_reverse:
//                break;
//        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    @Override
    public void onScreenTextureChanged(int textureId) {
        LogUtils.d(TAG, "onScreenTextureChanged:" + textureId);
        if (mWeVideoRecorder != null) {
            mWeVideoRecorder.setExternalTextureId(textureId);
        }
    }

    @Override
    protected void onPause() {
        LogUtils.d(TAG, "onPause");
        super.onPause();
    }

    @Override
    protected void onStop() {
        LogUtils.d(TAG, "onStop");
        stopRecord();
        stopMusic();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        LogUtils.d(TAG, "onDestroy");
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mBroadcastReceiver);
        mContentView.getViewTreeObserver().removeOnGlobalLayoutListener(mOnGlobalLayoutListener);
        mUIHandler.removeCallbacksAndMessages(null);

        mWeVideoRecorder.release();
        mWeVideoRecorder = null;
        mWeImageVideoView.release();
        mWeImageVideoView = null;

        if (mWePlayer != null) {
            mWePlayer.release();
            mWePlayer = null;
        }

        if (mPermissionHandler != null) {
            mPermissionHandler.destroy();
            mPermissionHandler = null;
        }
        super.onDestroy();
    }

}
