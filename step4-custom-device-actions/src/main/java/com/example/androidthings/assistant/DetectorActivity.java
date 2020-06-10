/*
 * Copyright 2016 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package  com.example.androidthings.assistant;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;

import com.example.androidthings.assistant.env.ImageUtils;
import com.example.androidthings.assistant.env.Logger;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public class DetectorActivity extends CameraHandler {
    private static final Logger LOGGER = new Logger();

    private static final int TF_OD_API_INPUT_SIZE = 300;
    private static final Size DESIRED_PREVIEW_SIZE = new Size(640, 480);
    private static final boolean SAVE_PREVIEW_BITMAP = false;
    private Classifier detector;
    private int previewWidth = 640;
    private int previewHeight = 480;
    private List<Classifier.Recognition> mappedRecognitions =
            new LinkedList<>();
    private Context mContext;
    private Handler handler;
    private HandlerThread handlerThread;
    List<Classifier.Recognition> results = new ArrayList<Classifier.Recognition>() ;
    Bitmap croppedBitmap = null;
    //private MultiBoxTracker tracker;
    private AssistantActivity assistantActivityInstance;

    DetectorActivity(Context context, Classifier detectionInstance, AssistantActivity assistantActivity){
        super();
        mContext = context;
        detector = detectionInstance;
        handlerThread = new HandlerThread("inference");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
        //tracker = new MultiBoxTracker(context);
        assistantActivityInstance = assistantActivity;
    }

    protected synchronized void runInBackground(final Runnable r) {
        if (handler != null) {
            handler.post(r);
        }
    }

    @Override
    protected List<Classifier.Recognition> processImageRGBbytes(int[] rgbBytes) {

        Bitmap rgbFrameBitmap = null;

        Matrix frameToCropTransform;
        Matrix cropToFrameTransform;

        frameToCropTransform =
                ImageUtils.getTransformationMatrix(
                        previewWidth, previewHeight,
                        TF_OD_API_INPUT_SIZE, TF_OD_API_INPUT_SIZE,
                        90, false);

        cropToFrameTransform = new Matrix();
        frameToCropTransform.invert(cropToFrameTransform);

        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Config.ARGB_8888);
        croppedBitmap = Bitmap.createBitmap(TF_OD_API_INPUT_SIZE, TF_OD_API_INPUT_SIZE, Config.ARGB_8888);
        rgbFrameBitmap.setPixels(assistantActivityInstance.getRgbBytes(), 0, previewWidth, 0, 0, previewWidth, previewHeight);

        final Canvas canvas = new Canvas(croppedBitmap);
        canvas.drawBitmap(rgbFrameBitmap, frameToCropTransform, null);
        // For examining the actual TF input.
        if (SAVE_PREVIEW_BITMAP) {
            ImageUtils.saveBitmap(croppedBitmap);
        }
        assistantActivityInstance.imageView.setImageBitmap(croppedBitmap);
        results = detector.recognizeImage(croppedBitmap);

        LOGGER.i("Detect: %s", results);
        mappedRecognitions.clear();
        for (final Classifier.Recognition result : results) {
            final RectF location = result.getLocation();
            if (location != null && !result.getTitle().equalsIgnoreCase("???") && result.getConfidence() >= 0.6f){
                result.setLocation(location);
                mappedRecognitions.add(result);
            }
        }
        return mappedRecognitions;
    }


    @Override
    protected Size getDesiredPreviewFrameSize() {
        return DESIRED_PREVIEW_SIZE;
    }

}
