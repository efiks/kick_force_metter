package com.forcemetter;

import android.annotation.SuppressLint;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.Message;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import static java.lang.Math.ceil;
import android.os.Process;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;

/**
 * An example full-screen activity that shows and hides the system UI (i.e.
 * status bar and navigation/system bar) with user interaction.
 */
public class KickMetter extends AppCompatActivity implements SensorEventListener {
    /**
     * Whether or not the system UI should be auto-hidden after
     * {@link #AUTO_HIDE_DELAY_MILLIS} milliseconds.
     */
    private static final boolean AUTO_HIDE = true;

    /**
     * If {@link #AUTO_HIDE} is set, the number of milliseconds to wait after
     * user interaction before hiding the system UI.
     */
    private static final int AUTO_HIDE_DELAY_MILLIS = 3000;

    /**
     * Some older devices needs a small delay between UI widget updates
     * and a change of the status and navigation bar.
     */
    private static final int UI_ANIMATION_DELAY = 300;

    private View mContentView;
    private View mControlsView;
    private boolean mVisible;

    // Accelerometer work
    private SensorManager sensorManager;
    private long  lastUpdate;
    static int    MaxCalSamples = 100;
    private int   currSample;
    private float avgAcceleration;
    private float minAcceleration;
    private float maxAcceleration;

    private float avgDeltaT;
    private static float epsilon_start = 5.f;
    private static float epsilon_stop = 1.f;

    // Circular buffer used to fill the collected data
    private float[] DataBuffer;
    private long[] TimeStampBuffer;
    private int     currIdx;
    private boolean isListening;

    private static float mass = 40; // Mass in KG
    private enum MeterStates {
        IDLE,
        CALIBRATING,
        WAITING_FOR_IMPACT,
        COLLECTING_IMPACT,
        PROCESSING
    }

    private MeterStates currState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_kick_metter);

        mVisible = true;
        mControlsView = findViewById(R.id.fullscreen_content_controls);
        mContentView = findViewById(R.id.fullscreen_content);


        // Set up the user interaction to manually show or hide the system UI.
        mContentView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggle();
            }
        });

        // Upon interacting with UI controls, delay any scheduled hide()
        // operations to prevent the jarring behavior of controls going away
        // while interacting with the UI.
        findViewById(R.id.callibrate_button).setOnTouchListener(mDelayHideTouchListener);


        currState = MeterStates.IDLE;
        // Setup accelerometer service
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

        isListening = false;
    }

    /**
    @Override
    protected void onStop() {
        // A service can be "started" and/or "bound". In this case, it's "started" by this Activity
        // and "bound" to the JobScheduler (also called "Scheduled" by the JobScheduler). This call
        // to stopService() won't prevent scheduled jobs to be processed. However, failing
        // to call stopService() would keep it alive indefinitely.
        stopService(new Intent(this, NotificationService.class));
        super.onStop();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Start service and provide it a way to communicate with this class.
        Intent startServiceIntent = new Intent(this, NotificationService.class);
        //startServiceIntent.putExtra(MESSENGER_INTENT_KEY, messengerIncoming);
        startService(startServiceIntent);
    }
    */

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);

        // Trigger the initial hide() shortly after the activity has been
        // created, to briefly hint to the user that UI controls
        // are available.
        delayedHide(100);
    }

    @Override
    protected  void onStart() {
        super.onStart();
        OnCalibrate(mContentView);
    }
    @Override
    protected  void onStop() {
        if ( isListening ) {
            sensorManager.unregisterListener(this,
                    sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER));
            isListening = false;
        }
        super.onStop();
    }
    /**
     * Touch listener to use for in-layout UI controls to delay hiding the
     * system UI. This is to prevent the jarring behavior of controls going away
     * while interacting with activity UI.
     */
    private final View.OnTouchListener mDelayHideTouchListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View view, MotionEvent motionEvent) {
            if (AUTO_HIDE) {
                delayedHide(AUTO_HIDE_DELAY_MILLIS);
            }
            return false;
        }
    };

    private void toggle() {
        if (mVisible) {
            hide();
        } else {
            show();
        }
    }

    private void hide() {
        // Hide UI first
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.hide();
        }
        mControlsView.setVisibility(View.GONE);
        mVisible = false;

        // Schedule a runnable to remove the status and navigation bar after a delay
        mHideHandler.removeCallbacks(mShowPart2Runnable);
        mHideHandler.postDelayed(mHidePart2Runnable, UI_ANIMATION_DELAY);
    }

    private final Runnable mHidePart2Runnable = new Runnable() {
        @SuppressLint("InlinedApi")
        @Override
        public void run() {
            // Delayed removal of status and navigation bar

            // Note that some of these constants are new as of API 16 (Jelly Bean)
            // and API 19 (KitKat). It is safe to use them, as they are inlined
            // at compile-time and do nothing on earlier devices.
            mContentView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        }
    };

    @SuppressLint("InlinedApi")
    private void show() {
        // Show the system bar
        mContentView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        mVisible = true;

        // Schedule a runnable to display UI elements after a delay
        mHideHandler.removeCallbacks(mHidePart2Runnable);
        mHideHandler.postDelayed(mShowPart2Runnable, UI_ANIMATION_DELAY);
    }

    private final Runnable mShowPart2Runnable = new Runnable() {
        @Override
        public void run() {
            // Delayed display of UI elements
            ActionBar actionBar = getSupportActionBar();
            if (actionBar != null) {
                actionBar.show();
            }
            mControlsView.setVisibility(View.VISIBLE);
        }
    };

    private final Handler mHideHandler = new Handler();
    private final Runnable mHideRunnable = new Runnable() {
        @Override
        public void run() {
            hide();
        }
    };

    /**
     * Schedules a call to hide() in delay milliseconds, canceling any
     * previously scheduled calls.
     */
    private void delayedHide(int delayMillis) {
        mHideHandler.removeCallbacks(mHideRunnable);
        mHideHandler.postDelayed(mHideRunnable, delayMillis);
    }


    public void OnCalibrate(android.view.View view) {
        TextView textView = (TextView) mContentView;

        textView.setText("Calibrating");
        int clr = 0xFFefdc08;
        textView.setTextColor(clr);

        currSample = 0;
        avgAcceleration = 0.f;
        avgDeltaT = 0.f;
        lastUpdate = -1;

        currState = MeterStates.CALIBRATING;
        // register this class as a listener for the orientation and
        // accelerometer sensors
        if ( !isListening ) {
            sensorManager.registerListener(this,
                    sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                    SensorManager.SENSOR_DELAY_FASTEST);
            isListening = true;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            switch ( currState ) {
                case CALIBRATING:
                    HandleCallibrationEvent(event);
                    break;

                case WAITING_FOR_IMPACT: case COLLECTING_IMPACT:
                    HandleDataCollection(event);
                    break;
            }
        }

    }
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void HandleCallibrationEvent(SensorEvent event) {
        float[] values = event.values;
        // Movement
        float x = values[0];
        float y = values[1];
        float z = values[2];

        float accelationSquareRoot = (x * x + y * y + z * z) /
                 (SensorManager.GRAVITY_EARTH * SensorManager.GRAVITY_EARTH);
        long actualTime = event.timestamp;
        if ( lastUpdate == -1 ) {
            lastUpdate = actualTime;
            minAcceleration = accelationSquareRoot;
            maxAcceleration = accelationSquareRoot;
        }
        float dT = (float)(actualTime - lastUpdate) / 1e6f;
        //if (actualTime - lastUpdate < 200) {
        //    return;
        //}
        lastUpdate = actualTime;
        /*
        Toast.makeText(this, "Device was shuffed", Toast.LENGTH_SHORT)
                .show();
        if (color) {
            view.setBackgroundColor(Color.GREEN);
        } else {
            view.setBackgroundColor(Color.RED);
        }
        color = !color;
        */
        if ( currSample < MaxCalSamples ) {
            avgAcceleration += accelationSquareRoot;
            maxAcceleration = Math.max(maxAcceleration, accelationSquareRoot);
            minAcceleration = Math.min(minAcceleration, accelationSquareRoot);
            avgDeltaT += dT;
            /*
            String str = Float.toString(accelationSquareRoot) +
                    " dT=" + Float.toString(dT) + "[mSec]" +
                    ", avg. dT=" + Float.toString(avgDeltaT);
            Log.d("CAL", str);
            */
            ++currSample;
        }

        if ( currSample >= MaxCalSamples ) {
            avgAcceleration /= (float)MaxCalSamples;
            if ( (maxAcceleration - minAcceleration) > 0.1f*avgAcceleration ) {
                lastUpdate = -1;
                currSample = 0;
                Log.d("CAL", "Incomplete, AVG="+Float.toString(avgAcceleration) +
                        ",max=" + Float.toString(maxAcceleration) +
                        ",min=" + Float.toString(minAcceleration));
                return;
            }
            avgDeltaT /= (float)(MaxCalSamples-1); // don't take in account the first sample

            // Update text
            TextView textView = (TextView) mContentView;

            textView.setText("GO");
            int clr = 0xFF00FF00;
            textView.setTextColor(clr);

            // Allocate buffer for execution
            int samples = (int)ceil(1e3f / avgDeltaT);
            Log.d("CAL", "Done, AVG="+Float.toString(avgAcceleration) +
                    ",max=" + Float.toString(maxAcceleration) +
                    ",min=" + Float.toString(minAcceleration) +
                    ",avg dT=" + Float.toString(avgDeltaT) +
                    ",Samples=" + Integer.toString(samples));

            DataBuffer = new float[samples];
            TimeStampBuffer = new long[samples];
            currIdx = 0;
            currState = MeterStates.WAITING_FOR_IMPACT;
        }
    }

    private void HandleDataCollection(SensorEvent event) {

        float[] values = event.values;
        // Movement
        float x = values[0];
        float y = values[1];
        float z = values[2];

        float accelationSquareRoot = (x * x + y * y + z * z) /
                (SensorManager.GRAVITY_EARTH * SensorManager.GRAVITY_EARTH);

        float absValue = Math.abs(accelationSquareRoot - avgAcceleration);
        switch ( currState ) {
            case WAITING_FOR_IMPACT:
                if ( absValue <= epsilon_start ) {
                    DataBuffer[0] = accelationSquareRoot;
                    TimeStampBuffer[0] = event.timestamp;
                    return;
                }
                currIdx = 1;
                currState = MeterStates.COLLECTING_IMPACT;
            case COLLECTING_IMPACT:
                if ( absValue <= epsilon_stop ) {
                    // Stop Events
                    sensorManager.unregisterListener(this,
                            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER));
                    isListening = false;
                    currState = MeterStates.PROCESSING;
                    TextView textView = (TextView) mContentView;
                    textView.setText("PROCESSING");
                    textView.setTextColor(0xFFFF0000);

                    float force = computeImpact(DataBuffer, TimeStampBuffer, currIdx);

                    // Update result
                    textView.setText(Double.toString(Math.round((double)force)));
                    textView.setTextColor(0xFF000000);

                    // Set the timer for showing the result
                    new UpdateScreenTask().execute(DataBuffer);

                    return;
                }
                if ( currIdx >= DataBuffer.length ) break;

                DataBuffer[currIdx] = absValue;
                TimeStampBuffer[currIdx] = event.timestamp;
                ++currIdx;
                String str = Float.toString(absValue) +
                        " T=" + Long.toString(event.timestamp) + "[mSec]";
                Log.d("DATA", str);
        }
    }


    private class UpdateScreenTask extends AsyncTask<float[], Void, Void> {
        @Override
        protected void onPostExecute(Void result) {
            OnCalibrate(mContentView);
        }

        @Override
        protected Void doInBackground(float[]... params) {
            try {
                FileWriter file = getStorageFile();

                if ( file != null ) {
                    try {
                        int i = 0;
                        while (i < params[0].length && (params[0][i] > 0.f)) {
                            file.append( Float.toString(params[0][i]) + ",");
                            ++i;
                            params[0][i - 1] = 0.f;
                        }
                        file.append("\n");
                        file.flush();
                        file.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                Thread.sleep(4000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return null;
        }
    }
    float computeImpact(float[] data, long[] times, int num) {
        float speed = 0.f;
        float distance = 0.f;
        float totalForce = 0.f;
        float time = (times[num-1] - times[0]) * 1e-9f ;

        for(int i=1; i<num; ++i) {
            float dT = (times[i] - times[i-1]) * 1e-9f;
            float dV = data[i] * dT;
            float dD = dV * dT;
            distance += dD;
            speed += dV;
            // Based on F * d = 1/2 * m * v^2
            float F = 0.5f * mass * (dV * dV) / dD;
            totalForce += F;
        }
        float impact = totalForce/time;
        Log.d("RES", "I=" + Float.toString(impact) +
                ",T=" + Float.toString(time) +
                ",F="+Float.toString(totalForce) +
                ",D=" + Float.toString(distance) +
                ",V=" + Float.toString(speed) );
        return totalForce;
    }

    public FileWriter getStorageFile() {
        // Get the directory for the user's public pictures directory. Environment.DIRECTORY_DOCUMENTS
        String fileName = Environment.getExternalStorageDirectory().getAbsolutePath() + "/kick_meter.txt";

        FileWriter strm = null;
        try {
            strm = new FileWriter(fileName, true);
        } catch (IOException e) {
            e.printStackTrace();
        }

        return strm;
    }
}

