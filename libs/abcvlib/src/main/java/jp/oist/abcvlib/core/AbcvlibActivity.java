package jp.oist.abcvlib.core;

import android.app.AlertDialog;
import android.content.Context;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import jp.oist.abcvlib.util.Logger;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.IOException;
import java.util.concurrent.Executors;

import jp.oist.abcvlib.core.outputs.Outputs;
import jp.oist.abcvlib.util.ProcessPriorityThreadFactory;
import jp.oist.abcvlib.util.SerialCommManager;
import jp.oist.abcvlib.util.UsbSerial;
import jp.oist.abcvlib.util.SerialReadyListener;

/**
 * AbcvlibActivity is where all of the other classes are initialized into objects. The objects
 * are then passed to one another in order to coordinate the various shared values between them.
 *
 * Android app MainActivity can start Motion by extending AbcvlibActivity and then running
 * any of the methods within the object instance Motion within an infinite threaded loop
 * e.g:
 *
 * @author Christopher Buckley https://github.com/topherbuckley
 *
 */
public abstract class AbcvlibActivity extends AppCompatActivity implements SerialReadyListener {

    protected Outputs outputs;
    private Switches switches = new Switches();
    private static final String TAG = "abcvlib";
    private IOReadyListener ioReadyListener;
    protected UsbSerial usbSerial;
    private SerialCommManager serialCommManager;
    private Runnable android2PiWriter = null;
    private Runnable pi2AndroidReader = null;
    AlertDialog alertDialog = null;
    private long initialDelay = 0;
    // Note anything less than 10ms will result in no GET_STATE commands being called and all
    // being overrides by whatever commands are sent in the main loop
    private long delay = 5;
    private boolean isCreated = false;

    protected void onCreate(Bundle savedInstanceState) {
        isCreated = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        usbInitialize();
        super.onCreate(savedInstanceState);

        ViewCompat.setOnApplyWindowInsetsListener(getWindow().getDecorView(), (v, insets) -> {
            // Get the insets for system bars (status bar, navigation bar)
            View contentView = findViewById(android.R.id.content);
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());

            // Apply the top inset as padding to your view
            contentView.setPadding(
                    contentView.getPaddingLeft(),
                    systemBars.top,
                    contentView.getPaddingRight(),
                    systemBars.bottom
            );

            // Return the insets to allow the system to continue processing them
            return insets;
        });
    }

    private void usbInitialize(){
        try {
            this.usbSerial = new UsbSerial(this,
                    (UsbManager) getSystemService(Context.USB_SERVICE),
                    this);
        } catch (IOException e) {
            e.printStackTrace();
            showCustomDialog();
        }
    }

    public void onSerialReady(UsbSerial usbSerial) {
        if (serialCommManager == null){
            Logger.w(TAG, "Default SerialCommManager being used. If you intended to create your " +
                    "own, make sure you initialize it in onCreate prior to calling super.onCreate().");
            serialCommManager = new SerialCommManager(usbSerial);
        }
        serialCommManager.start();
        initializeOutputs();
        onOutputsReady();

        Executors.newSingleThreadScheduledExecutor(new ProcessPriorityThreadFactory(
                Thread.MAX_PRIORITY, // Needs to be > 5 in order for object detector not to overwhelm cpu
                "AbcvlibActivityMainLoop")
                ).scheduleWithFixedDelay(new AbcvlibActivityRunnable(), this.initialDelay, this.delay,
                java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    protected void onOutputsReady(){
        // Override this method in your MainActivity to do anything that requires the outputs
        Logger.w(TAG, "onOutputsReady not overridden. Override this method in your MainActivity to " +
                "do anything that requires the outputs.");
    }

    public Outputs getOutputs() {
        // Throw runtime error here if outputs is null
        if (outputs == null){
            throw new RuntimeException("Outputs is null. You are trying to access outputs before " +
                    "it has been initialized. Make sure to call super.onCreate() in your onCreate " +
                    "method.");
        }
        return outputs;
    }

    protected void setSerialCommManager(SerialCommManager serialCommManager){
        this.serialCommManager = serialCommManager;
    }

    protected void setInitialDelay(long initialDelay){
        if (isCreated){
            throw new RuntimeException("setInitialDelay must be called before onCreate");
        }
        this.initialDelay = initialDelay;
    }
    protected void setDelay(long delay){
        if (isCreated){
            throw new RuntimeException("setDelay must be called before onCreate");
        }
        this.delay = delay;
    }

    protected void abcvlibMainLoop(){
        // Throw runtime error if this is called and indicate to user that this needs to be overriden
        throw new RuntimeException("runAbcvlibActivityMainLoop must be overridden");
    }

    private class AbcvlibActivityRunnable implements Runnable{
        @Override
        public void run() {
            abcvlibMainLoop();
        }
    }

    public void onEncoderCountsRec(int left, int right){
        Logger.d("serial", "Left encoder count: " + left);
        Logger.d("serial", "Right encoder count: " + right);
    }

    protected void setAndroi2PiWriter(Runnable android2PiWriter){
        this.android2PiWriter = android2PiWriter;
    }

    protected void setPi2AndroidReader(Runnable pi2AndroidReader){
        this.pi2AndroidReader = pi2AndroidReader;
    }

    private void showCustomDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.missing_robot, null);
        builder.setView(dialogView);

        // Find the TextView and Button in the dialog layout
        Button confirmButton = dialogView.findViewById(R.id.confirmButton);

        // Set a click listener for the Confirm button
        confirmButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Dismiss the dialog
                if (alertDialog != null){
                    alertDialog.dismiss();
                }

                usbInitialize();
            }
        });

        // Create the AlertDialog
        alertDialog = builder.create();

        // Show the dialog
        alertDialog.show();
    }

    @Override
    protected void onStop() {
        super.onStop();
        Logger.v(TAG, "End of AbcvlibActivity.onStop");
    }

    @Override
    public void onPause()
    {
        super.onPause();
        if (serialCommManager != null){
            serialCommManager.setMotorLevels(0, 0, true, true);
            serialCommManager.stop();
        }
        Logger.i(TAG, "End of AbcvlibActivity.onPause");
    }

    public Switches getSwitches() {
        return switches;
    }

    public void setSwitches(Switches switches){
        this.switches = switches;
    }

    private void initializeOutputs(){
        outputs = new Outputs(switches, serialCommManager);
    }
}
