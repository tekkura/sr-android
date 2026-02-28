package jp.oist.abcvlib.core.learning

import android.content.Context
import jp.oist.abcvlib.core.inputs.TimeStepDataBuffer
import jp.oist.abcvlib.core.outputs.Outputs
import java.net.InetSocketAddress

class MetaParameters(
    val context: Context,
    val timeStepLength: Int,
    val maxTimeStepCount: Int,
    val maxReward: Int,
    val maxEpisodeCount: Int,
    val inetSocketAddress: InetSocketAddress?,
    val timeStepDataBuffer: TimeStepDataBuffer,
    val outputs: Outputs,
    val robotID: Int
)
