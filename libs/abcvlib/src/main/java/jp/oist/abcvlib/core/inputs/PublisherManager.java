package jp.oist.abcvlib.core.inputs;

import android.util.Log;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Phaser;

/**
 * Manages the permission lifecycle of a group of publishers
 * In order to synchronize the lifecycle of all publishers, this creates a Phaser that waits for
 * each phase to finish for all publishers before allowing the next phase to start.
 * phase 0 = permissions of publisher objects
 * phase 1 = initialization of publisher object streams/threads
 * phase 2 = initialize publisher objects (i.e. initialize recording data)
 */
public class PublisherManager {
    private final ArrayList<Publisher<?>> publishers = new ArrayList<>();
    private final Phaser phaser = new Phaser(1);
    private final String TAG = getClass().getName();

    public PublisherManager(){
    }

    //========================================Phase 0===============================================
    public PublisherManager add(Publisher<?> publisher){
        Log.i(TAG, "Adding publisher: " + publisher.getClass().getName());
        publishers.add(publisher);
        phaser.register();
        return this;
    }

    public void onPublisherPermissionsGranted(Publisher<?> grantedPublisher) { // Accept the publisher
        Log.i(TAG, "Publisher permissions granted for: " + grantedPublisher.getClass().getName());
        phaser.arriveAndDeregister();
    }

    //========================================Phase 1===============================================
    private void initialize(@NotNull Publisher<?> publisher){
        Log.i(TAG, "Registering publisher for phase 1: " + publisher.getClass().getName());
        phaser.register();
        publisher.start();
    }

    public void onPublisherInitialized() {
        Log.i(TAG, "Publisher deregistering: " + Thread.currentThread().getStackTrace()[2].getClassName());
        phaser.arriveAndDeregister();
    }

    public void initializePublishers(){
        phaser.arrive();
        Log.i(TAG, "Starting initializePublishers with " + publishers.size() + " publishers");
        Log.i(TAG, "Waiting on all publishers to initialize before starting");
        phaser.awaitAdvance(0); // Waits to initialize if not finished with initPhase
        Log.i(TAG, "Phase 0 complete, starting publisher initialization");
        for (Publisher<?> publisher: publishers){
            Log.i(TAG, "Initializing publisher: " + publisher.getClass().getName());
            initialize(publisher);
        }
    }

    //========================================Phase 2===============================================
    public void startPublishers(){
        phaser.arrive();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            Log.i(TAG, "Waiting on phase 1 to finish before starting");
            phaser.awaitAdvance(1);
            Log.i(TAG, "All publishers initialized. Starting publishers");
            for (Publisher<?> publisher: publishers){
                publisher.resume();
            }
            executor.shutdown(); // Shut down the executor after the task is completed
        });
    }

    //====================================Non-phase Related=========================================
    public void pausePublishers(){
        for (Publisher<?> publisher: publishers){
            publisher.pause();
        }
    }
    public void resumePublishers(){
        for (Publisher<?> publisher: publishers){
            publisher.resume();
        }
    }
    public void stopPublishers(){
        for (Publisher<?> publisher: publishers){
            publisher.stop();
        }
    }
    @SuppressWarnings("unused")
    public ArrayList<Publisher<?>> getPublishers() {
        return publishers;
    }
}
