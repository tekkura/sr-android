package jp.oist.abcvlib.basicassembler

import android.content.Context
import android.os.Handler
import jp.oist.abcvlib.core.inputs.TimeStepDataBuffer.TimeStepData
import jp.oist.abcvlib.core.learning.ActionSpace
import jp.oist.abcvlib.core.learning.CommAction
import jp.oist.abcvlib.core.learning.MetaParameters
import jp.oist.abcvlib.core.learning.MotionAction
import jp.oist.abcvlib.core.learning.StateSpace
import jp.oist.abcvlib.core.learning.Trial
import jp.oist.abcvlib.core.outputs.ActionSelector
import jp.oist.abcvlib.util.Logger
import jp.oist.abcvlib.util.RecordingWithoutTimeStepBufferException
import java.io.IOException
import java.util.concurrent.BrokenBarrierException
import java.util.concurrent.ExecutionException

class MyTrial(
    context: Context,
    private val guiUpdater: GuiUpdater,
    metaParameters: MetaParameters,
    actionSpace: ActionSpace,
    stateSpace: StateSpace
) : Trial(metaParameters, actionSpace, stateSpace), ActionSelector {
    private val mainHandler: Handler = Handler(context.mainLooper)

    override fun forward(data: TimeStepData) {

        // Use data as input to your policy and select action here
        // Just using default actions of each set as an example but this
        // should be replaced by your policy's decision process
        val motionAction: MotionAction = motionActionSet.motionActions[1]!!
        val commAction: CommAction = commActionSet.commActions[0]!!

        // Add your selected actions to the TimeStepDataBuffer for record
        data.actions.add(motionAction, commAction)

        Logger.i("myTrail", "Current motionAction: " + motionAction.actionName)
        outputs.setWheelOutput(
            motionAction.leftWheelPWM,
            motionAction.rightWheelPWM,
            motionAction.leftWheelBrake,
            motionAction.rightWheelBrake
        )

        // Note this will never be called when the myStepHandler.getTimeStep() >= myStepHandler.getMaxTimeStep() as the forward method will no longer be called
        mainHandler.post { guiUpdater.updateGUIValues(data, timeStep, episodeCount) }
    }

    // If you want to do things at the start/end of the episode/trail you can override these methods from Trail
    public override fun startTrail() {
        // Do stuff here
        super.startTrail()
    }

    public override fun startEpisode() {
        // Do stuff here
        super.startEpisode()
    }

    @Throws(
        BrokenBarrierException::class,
        InterruptedException::class,
        IOException::class,
        RecordingWithoutTimeStepBufferException::class,
        ExecutionException::class
    )
    override fun endEpisode() {
        // Do stuff here
        super.endEpisode()
    }

    @Throws(RecordingWithoutTimeStepBufferException::class, InterruptedException::class)
    override fun endTrial() {
        // Do stuff here
        super.endTrial()
    }

    override fun isLastTimestep(): Boolean {
        val result: Boolean = timeStep >= maxTimeStepCount
        // Do some logic here to modify result based on how you want to determine if this is the last timestep (e.g. currentTimeStep > maxTimeStep, reward > maxReward, etc.).
        return result
    }

    override fun isLastEpisode(): Boolean {
        val result: Boolean = episodeCount >= maxEpisodeCount
        // Do some logic here to modify result based on how you want to determine if this is the last episode (e.g. currentEpisode > maxEpisode, etc.).
        return result
    }
}
