package jp.oist.abcvlib.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.android.material.slider.Slider
import jp.oist.abcvlib.core.R
import jp.oist.abcvlib.tests.BalancePIDController
import jp.oist.abcvlib.util.ErrorHandler
import org.json.JSONException
import org.json.JSONObject
import java.math.RoundingMode
import java.text.DecimalFormat

/**
 * A simple [Fragment] subclass.
 * Use the [PidGuiFragament.newInstance] factory method to
 * create an instance of this fragment.
 */
class PidGuiFragament : Fragment() {
    private lateinit var setPoint: Slider
    private lateinit var pTilt: Slider
    private lateinit var dTilt: Slider
    private lateinit var pWheel: Slider
    private lateinit var expWeight: Slider
    private lateinit var maxAbsTilt: Slider

    private lateinit var balancePIDController: BalancePIDController
    private val sliderChangeListener = Slider.OnChangeListener { slider, value, fromUser ->
        updatePID()
    }

    var controls: MutableMap<String, Slider> = HashMap()
        private set

    companion object {
        private val TAG = PidGuiFragament::class.java.simpleName

        /**
         * Use this factory method to create a new instance of
         * this fragment using the provided parameters.
         *
         * @param balancePIDController
         * @return A new instance of fragment pid_gui.
         */
        // TODO: Rename and change types and number of parameters
        @JvmStatic
        fun newInstance(balancePIDController: BalancePIDController): PidGuiFragament {
            return PidGuiFragament().apply {
                this.balancePIDController = balancePIDController
            }
        }
    }


    fun updatePID() {
        try {
            balancePIDController.setPID(
                pTilt.value.toDouble(),
                0.0,
                dTilt.value.toDouble(),
                setPoint.value.toDouble(),
                pWheel.value.toDouble(),
                expWeight.value.toDouble(),
                maxAbsTilt.value.toDouble()
            )
        } catch (e: InterruptedException) {
            ErrorHandler.eLog(TAG, "Error when getting slider gui values", e, true)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val rootView = inflater.inflate(R.layout.pid_gui, container, false)

        setPoint = rootView.findViewById(R.id.seekBarSetPoint)
        pTilt = rootView.findViewById(R.id.seekBarTiltP)
        dTilt = rootView.findViewById(R.id.seekBarTiltD)
        pWheel = rootView.findViewById(R.id.seekBarWheelSpeedP)
        expWeight = rootView.findViewById(R.id.seekBarExponentialWeight)
        maxAbsTilt = rootView.findViewById(R.id.seekBarMaxAbsTilt)
        controls["sp"] = setPoint
        controls["pt"] = pTilt
        controls["dt"] = dTilt
        controls["pw"] = pWheel
        controls["ew"] = expWeight
        controls["mt"] = maxAbsTilt

        for (entry in controls.entries) {
            entry.value.addOnChangeListener(sliderChangeListener)
        }
        // Inflate the layout for this fragment
        return rootView
    }

    fun getControls(): String {
        val controlValues = JSONObject()

        val controls = mutableMapOf<String, Double>(
            "sp" to 2.8,
            "pt" to -24.0,
            "dt" to 1.0,
            "pw" to 0.4,
            "ew" to 0.25,
            "mt" to 6.5
        )

        // Take the value from each slider and store it in a new HashMap
        for (entry in controls.entries) {
            val df = DecimalFormat("#.##")
            df.roundingMode = RoundingMode.CEILING
            val value = df.format(entry.value)
            try {
                controlValues.put(entry.key, value)
            } catch (e: JSONException) {
                ErrorHandler.eLog(TAG, "Error when processing hashmap for controls", e, true)
            }
        }
        return controlValues.toString()
    }
}