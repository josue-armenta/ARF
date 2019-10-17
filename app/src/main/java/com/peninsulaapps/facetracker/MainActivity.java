package com.peninsulaapps.facetracker;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.ml.vision.FirebaseVision;
import com.google.firebase.ml.vision.common.FirebaseVisionImage;
import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata;
import com.google.firebase.ml.vision.face.FirebaseVisionFace;
import com.google.firebase.ml.vision.face.FirebaseVisionFaceDetector;
import com.google.firebase.ml.vision.face.FirebaseVisionFaceDetectorOptions;
import com.otaliastudios.cameraview.CameraView;
import com.otaliastudios.cameraview.size.Size;
import com.otaliastudios.cameraview.size.SizeSelector;
import com.otaliastudios.cameraview.size.SizeSelectors;
import com.peninsulaapps.facetracker.services.BluetoothService;

import java.util.List;
import java.util.concurrent.ExecutionException;

public class MainActivity extends AppCompatActivity {

    // Debugging
    private static final String TAG = "MainActivity";
    private static final boolean D = true;

    // Message types sent from the BluetoothService Handler
    public static final int MESSAGE_STATE_CHANGE = 1;
    public static final int MESSAGE_READ = 2;
    public static final int MESSAGE_WRITE = 3;
    public static final int MESSAGE_DEVICE_NAME = 4;
    public static final int MESSAGE_TOAST = 5;

    // Key names received from the BluetoothService Handler
    public static final String DEVICE_NAME = "device_name";
    public static final String TOAST = "toast";

    private int REQUEST_CODE_PERMISSIONS = 101;

    private final String[] REQUIRED_PERMISSIONS =
            new String[]{"android.permission.CAMERA",
                    "android.permission.ACCESS_FINE_LOCATION"};

    private TextView yTextView, zTextView, pitchTextView, rollTextView;
    private CameraView cameraView;

    private BluetoothService mBluetoothService = null;
    private boolean isConnected;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Get local Bluetooth adapter
        BluetoothAdapter mBluetoothAdapter
                = BluetoothAdapter.getDefaultAdapter();

        // If the adapter is null, then Bluetooth is not supported
        if (mBluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not available", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Initialize the BluetoothService to perform bluetooth connections
        mBluetoothService = new BluetoothService(this, mHandler);

        cameraView = findViewById(R.id.camera);
        yTextView = findViewById(R.id.yTextView);
        zTextView = findViewById(R.id.zTextView);
        pitchTextView = findViewById(R.id.pitchTextView);
        rollTextView = findViewById(R.id.rollTextView);

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat
                    .requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }
    }

    private void startCamera() {

        cameraView.setLifecycleOwner(this);
        cameraView.setPreviewStreamSize(getSize());

        FirebaseVisionFaceDetectorOptions options =
                new FirebaseVisionFaceDetectorOptions.Builder().build();

        cameraView.addFrameProcessor(frame -> {

            byte[] data = frame.getData();
            Size size = frame.getSize();

            FirebaseVisionImageMetadata metadata = new FirebaseVisionImageMetadata.Builder()
                    .setWidth(size.getWidth())
                    .setHeight(size.getHeight())
                    .setFormat(FirebaseVisionImageMetadata.IMAGE_FORMAT_NV21)
                    .setRotation(FirebaseVisionImageMetadata.ROTATION_270)
                    .build();

            FirebaseVisionImage image
                    = FirebaseVisionImage.fromByteArray(data, metadata);

            FirebaseVisionFaceDetector detector = FirebaseVision.getInstance()
                    .getVisionFaceDetector(options);

            try {

                List<FirebaseVisionFace> faceList = Tasks.await(detector.detectInImage(image));

                for (FirebaseVisionFace face : faceList) {

                    float y = face.getHeadEulerAngleY();
                    float z = face.getHeadEulerAngleZ();

                    updateLabels(y, z);
                    sendLecture(y, z);
                }


            } catch (ExecutionException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

        });

    }

    private void sendLecture(float y, float z) {
        if (isConnected) {
            String msg = y + "," + z + "\r\n";
            sendMessage(msg);
        }
    }

    private void updateLabels(float y, float z) {

        String yText = getString(R.string.euler_angle,
                "Y", y);
        String zText = getString(R.string.euler_angle,
                "Z", z);

        runOnUiThread(() -> {
            yTextView.setText(yText);
            zTextView.setText(zText);
        });
    }

    private SizeSelector getSize() {

        SizeSelector width = SizeSelectors.minWidth(360);
        SizeSelector sizeSelector = SizeSelectors.smallest();
        return SizeSelectors.and(width, sizeSelector);

    }

    // The Handler that gets information back from the BluetoothService
    @SuppressLint("HandlerLeak")
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_STATE_CHANGE:
                    if (D) Log.i(TAG, "MESSAGE_STATE_CHANGE: " + msg.arg1);
                    switch (msg.arg1) {
                        case BluetoothService.STATE_CONNECTED:
                            isConnected = true;
                            break;
                        case BluetoothService.STATE_CONNECTING:
                            break;
                        case BluetoothService.STATE_LISTEN:
                            isConnected = false;
                            break;
                        case BluetoothService.STATE_NONE:
                            isConnected = false;
                            break;
                    }
                    break;
                case MESSAGE_WRITE:
                    //byte[] writeBuf = (byte[]) msg.obj;
                    // construct a string from the buffer
                    //String writeMessage = new String(writeBuf);
                    break;
                case MESSAGE_READ:
                    String message = (String) msg.obj;
                    processMessage(message);
                    break;
                case MESSAGE_DEVICE_NAME:
                    // save the connected device's name
                    String mConnectedDeviceName = msg.getData().getString(DEVICE_NAME);
                    Toast.makeText(getApplicationContext(), "Connected to "
                            + mConnectedDeviceName, Toast.LENGTH_SHORT).show();
                    break;
                case MESSAGE_TOAST:
                    Toast.makeText(getApplicationContext(), msg.getData().getString(TOAST),
                            Toast.LENGTH_SHORT).show();
                    break;
            }
        }
    };

    private void processMessage(String message) {

        String[] parts = message.split(",");

        if (parts.length == 2) {

            double lastPitch, lastRoll;

            try {

                lastPitch = Double.parseDouble(parts[0]);
                lastRoll = Double.parseDouble(parts[1]);

            } catch (Exception e) {

                lastPitch = 0;
                lastRoll = 0;

            }

            updateSensorLabels(lastPitch, lastRoll);

        }
    }

    private void updateSensorLabels(double eulerAnglePitch, double eulerAngleRoll) {
        runOnUiThread(() -> {
            String yText = getString(R.string.euler_angle,
                    "Pitch", eulerAnglePitch);
            String zText = getString(R.string.euler_angle,
                    "Roll", eulerAngleRoll);

            pitchTextView.setText(yText);
            rollTextView.setText(zText);
        });
    }


    private void sendMessage(String message) {
        // Check that we're actually connected before trying anything
        if (mBluetoothService.getState() != BluetoothService.STATE_CONNECTED) {
            Toast.makeText(this, R.string.not_connected, Toast.LENGTH_SHORT).show();
            return;
        }

        // Check that there's actually something to send
        if (message.length() > 0) {
            // Get the message bytes and tell the BluetoothService to write
            byte[] send = message.getBytes();
            mBluetoothService.write(send);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Stop the Bluetooth chat services
        if (mBluetoothService != null) mBluetoothService.stop();
        if (D) Log.e(TAG, "--- ON DESTROY ---");
    }

    @Override
    public synchronized void onResume() {
        super.onResume();
        if (D) Log.e(TAG, "+ ON RESUME +");

        // Performing this check in onResume() covers the case in which BT was
        // not enabled during onStart(), so we were paused to enable it...
        // onResume() will be called when ACTION_REQUEST_ENABLE activity returns.
        if (mBluetoothService != null) {
            // Only if the state is STATE_NONE, do we know that we haven't started already
            if (mBluetoothService.getState() == BluetoothService.STATE_NONE) {
                // Start the Bluetooth chat services
                mBluetoothService.start();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {

        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private boolean allPermissionsGranted() {

        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }
}
