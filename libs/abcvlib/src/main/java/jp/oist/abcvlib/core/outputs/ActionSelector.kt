package jp.oist.abcvlib.core.outputs

import jp.oist.abcvlib.core.inputs.TimeStepDataBuffer.TimeStepData

interface ActionSelector {
    fun forward(data: TimeStepData)
}
