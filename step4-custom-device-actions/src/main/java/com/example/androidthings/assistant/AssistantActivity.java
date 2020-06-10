/*
 * Copyright 2018, The Android Open Source Project
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

package com.example.androidthings.assistant;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Camera;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.StrictMode;
import android.os.Trace;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.util.Size;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.Toast;

import com.example.androidthings.assistant.env.ImageUtils;
import com.example.androidthings.assistant.env.Logger;
import com.example.androidthings.assistant.shared.BoardDefaults;
import com.example.androidthings.assistant.shared.Credentials;
import com.example.androidthings.assistant.shared.MyDevice;
import com.google.android.things.contrib.driver.button.Button;
import com.google.android.things.contrib.driver.voicehat.Max98357A;
import com.google.android.things.contrib.driver.voicehat.VoiceHat;
import com.google.android.things.pio.Gpio;
import com.google.android.things.pio.PeripheralManager;
import com.google.assistant.embedded.v1alpha2.AssistConfig;
import com.google.assistant.embedded.v1alpha2.AssistRequest;
import com.google.assistant.embedded.v1alpha2.AssistResponse;
import com.google.assistant.embedded.v1alpha2.AudioInConfig;
import com.google.assistant.embedded.v1alpha2.AudioOutConfig;
import com.google.assistant.embedded.v1alpha2.DeviceConfig;
import com.google.assistant.embedded.v1alpha2.DialogStateIn;
import com.google.assistant.embedded.v1alpha2.EmbeddedAssistantGrpc;
import com.google.assistant.embedded.v1alpha2.SpeechRecognitionResult;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.auth.MoreCallCredentials;
import io.grpc.stub.StreamObserver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.tensorflow.Session;

import com.google.android.things.contrib.driver.rainbowhat.RainbowHat;

public class AssistantActivity extends Activity implements  ImageReader.OnImageAvailableListener,
        Button.OnButtonEventListener, TextToSpeech.OnInitListener {
    private static final String TAG = AssistantActivity.class.getSimpleName();

    // Peripheral and drivers constants.
    private static final boolean USE_VOICEHAT_DAC = false;
    private static final int BUTTON_DEBOUNCE_DELAY_MS = 20;

    // Camera Variables
    private CameraHandler mCameraHandler;
    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;

    private Context mContext;
    // Camera Image Capture Size
    private int previewWidth = 640;
    private int previewHeight = 480;
    private static final Size MODEL_IMAGE_SIZE = new Size(640, 480);
    private static final Logger LOGGER = new Logger();

    //detect variable
    TextToSpeech tts;
    private Runnable imageConverter;
    private int[] rgbBytes;
    Image image = null;

    // Audio constants.
    private static final int SAMPLE_RATE = 16000;
    private static int mVolumePercentage = 100;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static AudioInConfig.Encoding ENCODING_INPUT = AudioInConfig.Encoding.LINEAR16;
    private static AudioOutConfig.Encoding ENCODING_OUTPUT = AudioOutConfig.Encoding.LINEAR16;
    private static final AudioInConfig ASSISTANT_AUDIO_REQUEST_CONFIG =
            AudioInConfig.newBuilder()
                    .setEncoding(ENCODING_INPUT)
                    .setSampleRateHertz(SAMPLE_RATE)
                    .build();
    private static final AudioOutConfig ASSISTANT_AUDIO_RESPONSE_CONFIG =
            AudioOutConfig.newBuilder()
                    .setEncoding(ENCODING_OUTPUT)
                    .setSampleRateHertz(SAMPLE_RATE)
                    .setVolumePercentage(mVolumePercentage)
                    .build();
    private static final AudioFormat AUDIO_FORMAT_STEREO =
            new AudioFormat.Builder()
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .setEncoding(ENCODING)
                    .setSampleRate(SAMPLE_RATE)
                    .build();
    private static final AudioFormat AUDIO_FORMAT_OUT_MONO =
            new AudioFormat.Builder()
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(ENCODING)
                    .setSampleRate(SAMPLE_RATE)
                    .build();
    private static final AudioFormat AUDIO_FORMAT_IN_MONO =
            new AudioFormat.Builder()
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(ENCODING)
                    .setSampleRate(SAMPLE_RATE)
                    .build();
    private static final int SAMPLE_BLOCK_SIZE = 1024;
    private int mOutputBufferSize;

    // Google Assistant API constants.
    private static final String ASSISTANT_ENDPOINT = "embeddedassistant.googleapis.com";
    public ImageView imageView;


    // gRPC client and stream observers.
    private EmbeddedAssistantGrpc.EmbeddedAssistantStub mAssistantService;
    private StreamObserver<AssistRequest> mAssistantRequestObserver;
    private StreamObserver<AssistResponse> mAssistantResponseObserver =
            new StreamObserver<AssistResponse>() {
                @Override
                public void onNext(AssistResponse value) {
                    if (value.getEventType() != null) {
                        Log.d(TAG, "converse response event: " + value.getEventType());
                    }
                    if (value.getSpeechResultsList() != null && value.getSpeechResultsList().size() > 0) {
                        for (SpeechRecognitionResult result : value.getSpeechResultsList()) {
                            final String spokenRequestText = result.getTranscript();
                            if (!spokenRequestText.isEmpty()) {
                                Log.i(TAG, "assistant request text: " + spokenRequestText);
                                mMainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        mAssistantRequestsAdapter.add(spokenRequestText);
                                    }
                                });
                            }
                        }
                    }
                    if (value.getDialogStateOut() != null) {
                        int volume = value.getDialogStateOut().getVolumePercentage();
                        if (volume > 0) {
                            mVolumePercentage = volume;
                            Log.i(TAG, "assistant volume changed: " + mVolumePercentage);
                            mAudioTrack.setVolume(AudioTrack.getMaxVolume() *
                                    mVolumePercentage / 100.0f);
                        }
                        mConversationState = value.getDialogStateOut().getConversationState();
                    }
                    if (value.getAudioOut() != null) {
                        final ByteBuffer audioData =
                                ByteBuffer.wrap(value.getAudioOut().getAudioData().toByteArray());

                        Log.d(TAG, "converse audio size: " + audioData.remaining());

                        if (mLed != null) {
                            try {
                                mLed.setValue(!mLed.getValue());
                            } catch (IOException e) {
                                Log.w(TAG, "error toggling LED:", e);
                            }
                            Log.d(TAG, "converse audio size: " + audioData.remaining());
                            mAssistantResponses.add(audioData);
                            if (mLed != null) {
                                try {
                                    mLed.setValue(!mLed.getValue());
                                } catch (IOException e) {
                                    Log.w(TAG, "error toggling LED:", e);
                                }
                            }
                        }
                    }
                    if (value.getDeviceAction() != null &&
                            !value.getDeviceAction().getDeviceRequestJson().isEmpty()) {
                        // Iterate through JSON object
                        try {
                            JSONObject deviceAction =
                                    new JSONObject(value.getDeviceAction().getDeviceRequestJson());
                            JSONArray inputs = deviceAction.getJSONArray("inputs");
                            for (int i = 0; i < inputs.length(); i++) {
                                if (inputs.getJSONObject(i).getString("intent")
                                        .equals("action.devices.EXECUTE")) {
                                    JSONArray commands = inputs.getJSONObject(i)
                                            .getJSONObject("payload")
                                            .getJSONArray("commands");
                                    for (int j = 0; j < commands.length(); j++) {
                                        JSONArray execution = commands.getJSONObject(j)
                                                .getJSONArray("execution");
                                        for (int k = 0; k < execution.length(); k++) {
                                            String command = execution.getJSONObject(k)
                                                    .getString("command");
                                            JSONObject params = execution.getJSONObject(k)
                                                    .optJSONObject("params");
                                            handleDeviceAction(command, params);
                                        }
                                    }
                                }
                            }
                        } catch (JSONException | IOException e) {
                            e.printStackTrace();
                        }
                    }
                }

                @Override
                public void onError(Throwable t) {
                    Log.e(TAG, "converse error:", t);
                }

                @Override
                public void onCompleted() {
                    mAudioTrack = new AudioTrack.Builder()
                            .setAudioFormat(AUDIO_FORMAT_OUT_MONO)
                            .setBufferSizeInBytes(mOutputBufferSize)
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .build();
                    if (mAudioOutputDevice != null) {
                        mAudioTrack.setPreferredDevice(mAudioOutputDevice);
                    }
                    mAudioTrack.play();
                    if (mDac != null) {
                        try {
                            mDac.setSdMode(Max98357A.SD_MODE_LEFT);
                        } catch (IOException e) {
                            Log.e(TAG, "unable to modify dac trigger", e);
                        }
                    }
                    for (ByteBuffer audioData : mAssistantResponses) {
                        final ByteBuffer buf = audioData;
                        Log.d(TAG, "Playing a bit of audio");
                        mAudioTrack.write(buf, buf.remaining(),
                                AudioTrack.WRITE_BLOCKING);
                    }
                    mAssistantResponses.clear();
                    mAudioTrack.stop();
                    if (mDac != null) {
                        try {
                            mDac.setSdMode(Max98357A.SD_MODE_SHUTDOWN);
                        } catch (IOException e) {
                            Log.e(TAG, "unable to modify gpio peripherals", e);
                        }
                    }
                    Log.i(TAG, "assistant response finished");
                    if (mLed != null) {
                        try {
                            mLed.setValue(false);
                        } catch (IOException e) {
                            Log.e(TAG, "error turning off LED:", e);
                        }
                    }
                }
            };

    // Audio playback and recording objects.
    private AudioTrack mAudioTrack;
    private AudioRecord mAudioRecord;

    // Audio routing configuration: use default routing.
    private AudioDeviceInfo mAudioInputDevice;
    private AudioDeviceInfo mAudioOutputDevice;

    // Hardware peripherals.
    private Button mButton;
    private Gpio mLed;
    private Gpio mBlueLed;
    private Max98357A mDac;
    private Handler mLedHandler = new Handler(Looper.getMainLooper());

    // Assistant Thread and Runnables implementing the push-to-talk functionality.
    private ByteString mConversationState = null;
    private HandlerThread mAssistantThread;
    private Handler mAssistantHandler;
    private ArrayList<ByteBuffer> mAssistantResponses = new ArrayList<>();
    private Runnable mStartAssistantRequest = new Runnable() {
        @Override
        public void run() {
            Log.i(TAG, "starting assistant request");
            mAudioRecord.startRecording();
            mAssistantRequestObserver = mAssistantService.assist(mAssistantResponseObserver);
            AssistConfig.Builder converseConfigBuilder = AssistConfig.newBuilder()
                    .setAudioInConfig(ASSISTANT_AUDIO_REQUEST_CONFIG)
                    .setAudioOutConfig(ASSISTANT_AUDIO_RESPONSE_CONFIG)
                    .setDeviceConfig(DeviceConfig.newBuilder()
                            .setDeviceModelId(MyDevice.MODEL_ID)
                            .setDeviceId(MyDevice.INSTANCE_ID)
                            .build());
            DialogStateIn.Builder dialogStateInBuilder = DialogStateIn.newBuilder()
                    .setLanguageCode(MyDevice.LANGUAGE_CODE);
            if (mConversationState != null) {
                dialogStateInBuilder.setConversationState(mConversationState);
            }
            converseConfigBuilder.setDialogStateIn(dialogStateInBuilder.build());
            mAssistantRequestObserver.onNext(
                    AssistRequest.newBuilder()
                            .setConfig(converseConfigBuilder.build())
                            .build());
            mAssistantHandler.post(mStreamAssistantRequest);
        }
    };
    private Runnable mStreamAssistantRequest = new Runnable() {
        @Override
        public void run() {
            ByteBuffer audioData = ByteBuffer.allocateDirect(SAMPLE_BLOCK_SIZE);
            if (mAudioInputDevice != null) {
                mAudioRecord.setPreferredDevice(mAudioInputDevice);
            }
            int result =
                    mAudioRecord.read(audioData, audioData.capacity(), AudioRecord.READ_BLOCKING);
            if (result < 0) {
                Log.e(TAG, "error reading from audio stream:" + result);
                return;
            }
            Log.d(TAG, "streaming ConverseRequest: " + result);
            mAssistantRequestObserver.onNext(AssistRequest.newBuilder()
                    .setAudioIn(ByteString.copyFrom(audioData))
                    .build());
            mAssistantHandler.post(mStreamAssistantRequest);
        }
    };
    private Runnable mStopAssistantRequest = new Runnable() {
        @Override
        public void run() {
            Log.i(TAG, "ending assistant request");
            mAssistantHandler.removeCallbacks(mStreamAssistantRequest);
            if (mAssistantRequestObserver != null) {
                mAssistantRequestObserver.onCompleted();
                mAssistantRequestObserver = null;
            }
            mAudioRecord.stop();
            mAudioTrack.play();
        }
    };
    private Handler mMainHandler;

    // List & adapter to store and display the history of Assistant Requests.
    private ArrayList<String> mAssistantRequests = new ArrayList<>();
    private ArrayAdapter<String> mAssistantRequestsAdapter;
    private Classifier detector;
    private static final int TF_OD_API_INPUT_SIZE = 300;
    private static final String TF_OD_API_MODEL_FILE =
            "file:///android_asset/ssd_mobilenet_v1_android_export.pb";
    private static final String TF_OD_API_LABELS_FILE = "file:///android_asset/coco_labels_list.txt";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        init();
        mContext = this;
        tts = new TextToSpeech(this,this);
        StrictMode.VmPolicy.Builder builder = new StrictMode.VmPolicy.Builder();
        StrictMode.setVmPolicy(builder.build());
        Log.i(TAG, "starting assistant demo");

        setContentView(R.layout.activity_main);
        ListView assistantRequestsListView = findViewById(R.id.assistantRequestsListView);
        mAssistantRequestsAdapter =
                new ArrayAdapter<>(this, android.R.layout.simple_list_item_1,
                        mAssistantRequests);
        assistantRequestsListView.setAdapter(mAssistantRequestsAdapter);
        mMainHandler = new Handler(getMainLooper());

        mAssistantThread = new HandlerThread("assistantThread");
        mAssistantThread.start();
        mAssistantHandler = new Handler(mAssistantThread.getLooper());

        // Use I2S with the Voice HAT.
        if (USE_VOICEHAT_DAC) {
            Log.d(TAG, "enumerating devices");
            mAudioInputDevice = findAudioDevice(AudioManager.GET_DEVICES_INPUTS,
                    AudioDeviceInfo.TYPE_BUS);
            if (mAudioInputDevice == null) {
                Log.e(TAG, "failed to found preferred audio input device, using default");
            }
            mAudioOutputDevice = findAudioDevice(AudioManager.GET_DEVICES_OUTPUTS,
                    AudioDeviceInfo.TYPE_BUS);
            if (mAudioOutputDevice == null) {
                Log.e(TAG, "failed to found preferred audio output device, using default");
            }
        }

        try {
            if (USE_VOICEHAT_DAC) {
                Log.i(TAG, "initializing DAC trigger");
                mDac = VoiceHat.openDac();
                mDac.setSdMode(Max98357A.SD_MODE_SHUTDOWN);

                mButton = VoiceHat.openButton();
                mLed = VoiceHat.openLed();
            } else {
                mButton = new Button(BoardDefaults.getGPIOForButton(),
                        Button.LogicState.PRESSED_WHEN_LOW);
                mLed = PeripheralManager.getInstance().openGpio(BoardDefaults.getGPIOForLED());
                mBlueLed = RainbowHat.openLedBlue();
            }

            mButton.setDebounceDelay(BUTTON_DEBOUNCE_DELAY_MS);
            mButton.setOnButtonEventListener(this);

            mLed.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
            mLed.setActiveType(Gpio.ACTIVE_HIGH);

            mBlueLed.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
            mBlueLed.setActiveType(Gpio.ACTIVE_HIGH);


        } catch (IOException e) {
            Log.e(TAG, "error configuring peripherals:", e);
            return;
        }

        AudioManager manager = (AudioManager) this.getSystemService(Context.AUDIO_SERVICE);
        int maxVolume = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        Log.i(TAG, "setting volume to: " + maxVolume);
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0);
        mOutputBufferSize = AudioTrack.getMinBufferSize(AUDIO_FORMAT_OUT_MONO.getSampleRate(),
                AUDIO_FORMAT_OUT_MONO.getChannelMask(),
                AUDIO_FORMAT_OUT_MONO.getEncoding());
        mAudioTrack = new AudioTrack.Builder()
                .setAudioFormat(AUDIO_FORMAT_OUT_MONO)
                .setBufferSizeInBytes(mOutputBufferSize)
                .build();
        mAudioTrack.play();
        int inputBufferSize = AudioRecord.getMinBufferSize(AUDIO_FORMAT_STEREO.getSampleRate(),
                AUDIO_FORMAT_STEREO.getChannelMask(),
                AUDIO_FORMAT_STEREO.getEncoding());
        mAudioRecord = new AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AUDIO_FORMAT_IN_MONO)
                .setBufferSizeInBytes(inputBufferSize)
                .build();

        ManagedChannel channel = ManagedChannelBuilder.forTarget(ASSISTANT_ENDPOINT).build();
        try {
            mAssistantService = EmbeddedAssistantGrpc.newStub(channel)
                    .withCallCredentials(MoreCallCredentials.from(
                            Credentials.fromResource(this, R.raw.credentials)
                    ));
            Log.e(TAG,"service created");
        } catch (IOException | JSONException e) {
            Log.e(TAG, "error creating assistant service:", e);
        }

        try {
            detector = TensorFlowObjectDetectionAPIModel.create(
                    mContext.getAssets(), TF_OD_API_MODEL_FILE, TF_OD_API_LABELS_FILE, TF_OD_API_INPUT_SIZE);
        }catch (Exception e)
        {
            LOGGER.e("Detect: %s", "error");
        }
    }

    private AudioDeviceInfo findAudioDevice(int deviceFlag, int deviceType) {
        AudioManager manager = (AudioManager) this.getSystemService(Context.AUDIO_SERVICE);
        AudioDeviceInfo[] adis = manager.getDevices(deviceFlag);
        for (AudioDeviceInfo adi : adis) {
            if (adi.getType() == deviceType) {
                return adi;
            }
        }
        return null;
    }

    @Override
    public void onButtonEvent(Button button, boolean pressed) {
        try {
            if (mLed != null) {
                mLed.setValue(pressed);
            }
        } catch (IOException e) {
            Log.d(TAG, "error toggling LED:", e);
        }
        if (pressed) {
            mAssistantHandler.post(mStartAssistantRequest);
        } else {
            mAssistantHandler.post(mStopAssistantRequest);
        }
    }

    public void handleDeviceAction(String command, JSONObject params)
            throws JSONException, IOException {
        mLedHandler.removeCallbacksAndMessages(null);
        if (command.equals("action.devices.traits.OnOff")) {
            mLedHandler.post(() -> {
                try {
                    mBlueLed.setValue(params.getBoolean("on"));
                } catch (IOException | JSONException e) {
                    throw new RuntimeException(e);
                }
            });
        } else if (command.equals("com.example.commands.BlinkLight")) {
            int delay = 1000;
            int blinkCount = params.getInt("number");
            String speed = params.getString("speed");
            if (speed.equals("slowly")) {
                delay = 2000;
            } else if (speed.equals("quickly")) {
                delay = 500;
            }
            for (int i = 0; i < blinkCount * 2; i++) {
                mLedHandler.postDelayed(() -> {
                    try {
                        mBlueLed.setValue(!mBlueLed.getValue());
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }, i * delay);
            }
        } else if (command.equals("com.nagarro.commands.OpenCamera")) {
            mBackgroundHandler.post(mBackgroudCommandListener);
            Log.e(TAG, "Received camera open command");
        }
        else if(command.equals("com.nagarro.commands.GetLocation")){
            tts.speak("Your current location is Nagarro 371, Gurgaon", TextToSpeech.QUEUE_FLUSH,null,null);
            Log.e(TAG,"Received current location command");
        }
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "destroying assistant demo");

        try {
            if (mBackgroundThread != null) mBackgroundThread.quit();
        } catch (Throwable t) {
            // close quietly
        }
        mBackgroundThread = null;
        mBackgroundHandler = null;


        if (mAudioRecord != null) {
            mAudioRecord.stop();
            mAudioRecord = null;
        }
        if (mAudioTrack != null) {
            mAudioTrack.stop();
            mAudioTrack = null;
        }
        if (mLed != null) {
            try {
                mLed.close();
                mBlueLed.close();
            } catch (IOException e) {
                Log.w(TAG, "error closing LED", e);
            }
            mLed = null;
            mBlueLed = null;
        }
        if (mButton != null) {
            try {
                mButton.close();
            } catch (IOException e) {
                Log.w(TAG, "error closing button", e);
            }
            mButton = null;
        }
        if (mDac != null) {
            try {
                mDac.close();
            } catch (IOException e) {
                Log.w(TAG, "error closing voice hat trigger", e);
            }
            mDac = null;
        }
        mAssistantHandler.post(() -> mAssistantHandler.removeCallbacks(mStreamAssistantRequest));
        mAssistantThread.quitSafely();
    }

    /**
     * Initialize the background thread and handler for camera  .
     */
    private void init() {
        mBackgroundThread = new HandlerThread("BackgroundThread");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(Looper.getMainLooper());
        mBackgroundHandler.post(mInitializeOnBackground);
    }

    private Runnable mInitializeOnBackground = new Runnable() {
        @Override
        public void run() {
            mCameraHandler = CameraHandler.getInstance();
            try {
                mCameraHandler.initializeCamera(AssistantActivity.this,
                        mBackgroundHandler, MODEL_IMAGE_SIZE, AssistantActivity.this);
                CameraHandler.dumpFormatInfo(AssistantActivity.this);
            } catch (CameraAccessException e) {
                throw new RuntimeException(e);
            }
            Size cameraCaptureSize = mCameraHandler.getImageDimensions();

        }
    };
    protected int[] getRgbBytes() {
        imageConverter.run();
        return rgbBytes;
    }

    @Override
    public void onImageAvailable(ImageReader reader) {
        final Bitmap bitmap;
        DetectorActivity detectorActivity = new DetectorActivity(mContext,detector,this);
        //We need wait until we have some size from onPreviewSizeChosen
        if (previewWidth == 0 || previewHeight == 0) {
            return;
        }
        try {
            byte[][] yuvBytes = new byte[3][];
            image = reader.acquireLatestImage();
            imageView = findViewById(R.id.imageView);
            final Image.Plane[] planes = image.getPlanes();
            fillBytes(planes, yuvBytes);
            int yRowStride = planes[0].getRowStride();
            int uvRowStride = planes[1].getRowStride();
            int uvPixelStride = planes[1].getPixelStride();
            rgbBytes = new int[previewWidth * previewHeight];
            imageConverter = new Runnable() {
                @Override
                public void run() {
                    ImageUtils.convertYUV420ToARGB8888(
                            yuvBytes[0],
                            yuvBytes[1],
                            yuvBytes[2],
                            previewWidth,
                            previewHeight,
                            yRowStride,
                            uvRowStride,
                            uvPixelStride,
                            rgbBytes);
                }
            };


            runOnUiThread(()->{
                  List<Classifier.Recognition> results = detectorActivity.processImageRGBbytes(rgbBytes);
                    if(results.size()>0) {
                        for (Classifier.Recognition result : results) {

                            tts.speak(result.getTitle(), TextToSpeech.QUEUE_FLUSH, null, null);
                        }
                    }
                    else
                        mBackgroundHandler.post(mBackgroudCommandListener);



            image.close();
            mCameraHandler.mImageReader.close();
            mCameraHandler.RefreshImageReader();
            });
        }catch (Exception e){
            System.out.print("Error");
            image.close();
        }
    }

    protected void fillBytes(final Image.Plane[] planes, final byte[][] yuvBytes) {
        // Because of the variable row stride it's not possible to know in
        // advance the actual necessary dimensions of the yuv planes.
        for (int i = 0; i < planes.length; ++i) {
            final ByteBuffer buffer = planes[i].getBuffer();
            if (yuvBytes[i] == null) {
                LOGGER.d("Initializing buffer %d at size %d", i, buffer.capacity());
                yuvBytes[i] = new byte[buffer.capacity()];
            }
            buffer.get(yuvBytes[i]);
        }
    }

    private Runnable mBackgroudCommandListener = ()-> {
        mCameraHandler.takePicture();
    };

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int ttsLang = tts.setLanguage(Locale.US);
            if (ttsLang == TextToSpeech.LANG_MISSING_DATA
                    || ttsLang == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS", "The Language is not supported!");
            } else {
                Log.i("TTS", "Language Supported.");
            }
            Log.i("TTS", "Initialization success.");
        }
    }
}
